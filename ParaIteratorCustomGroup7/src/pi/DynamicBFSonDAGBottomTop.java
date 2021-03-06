package pi;

import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 
 * This class represents a Parallel Breadth First Search (BFS) Iterator which
 * works on Directed Acyclic Graphs (DAGs). It returns nodes mainly in BFS order
 * from bottom to top of the DAG (i.e. Leaf nodes are returned first before the
 * Root).
 * 
 * @author SE750 - 2014 - Group 7 - Amruth Akoju, Mark Tooley, Kyle Jung based
 *         on DFS iterators created by Lama Akeila.
 */
public class DynamicBFSonDAGBottomTop<V> extends ParIteratorAbstract<V> {
	// Stores the object to be retrieved when calling the next method.
	protected Object[][] buffer;

	// Maps each thread id to its local stack which holds the max amount
	// specified by
	// the Chunk size.
	protected ConcurrentHashMap<Integer, LinkedBlockingDeque<V>> localChunkStack;

	// Stores a boolean value for each thread to indicate whether
	// the thread should be assigned with work or not
	protected volatile boolean[] permissionTable;

	protected final int chunkSize;

	protected CountDownLatch latch;

	protected int processedNodesNum = 0;

	protected int numTreeNodes = 0;

	protected AtomicBoolean breakAll = new AtomicBoolean(false);

	protected GraphAdapterInterface graph;

	protected LinkedBlockingDeque<V> freeNodeStack;

	protected ConcurrentLinkedQueue<V> processedNodes;

	protected ConcurrentLinkedQueue<V> waitingList;

	protected AtomicInteger stealingThreads = new AtomicInteger(0);

	protected final ReentrantLock lock = new ReentrantLock();

	/**
	 * 
	 * @param graph
	 *            - DAG graph that is being iterated over
	 * @param root
	 *            - root of the DAG tree
	 * @param freeNodeList
	 *            - Starting nodes that are essentially the initial freeNodes.
	 * @param numOfThreads
	 *            - number of threads running
	 * @param chunkSize
	 *            - max number of nodes assigned to a thread at a time.
	 */
	public DynamicBFSonDAGBottomTop(GraphAdapterInterface graph,
			Collection<V> startNodes, int numOfThreads, int chunkSize, boolean checkForCycles) {
		super(numOfThreads, false);
		if (checkForCycles && graph.hasCycles()) {
			throw new IllegalArgumentException("Graph has cycles");
		}
	
		this.chunkSize = chunkSize;
		this.graph = graph;
		this.freeNodeStack = new LinkedBlockingDeque<V>();
		numTreeNodes = graph.verticesSet().size();

		System.out.println("Total Nodes: " + numTreeNodes);

		buffer = new Object[numOfThreads][1];
		permissionTable = new boolean[numOfThreads];
		permissionTable = initializePermissionTable(permissionTable);
		processedNodes = new ConcurrentLinkedQueue<V>();
		waitingList = new ConcurrentLinkedQueue<V>();
		localChunkStack = new ConcurrentHashMap<Integer, LinkedBlockingDeque<V>>();

		// Initialise freeNodeStack with the nodes from the startNodeList
		for (V n : startNodes) {
			freeNodeStack.add(n);
		}

		System.out.println("Free Node(s): " + freeNodeStack.size());

		for (int i = 0; i < numOfThreads; i++) {
			localChunkStack.put(i, new LinkedBlockingDeque<V>(chunkSize));
		}

		latch = new CountDownLatch(numOfThreads);
	}

	// Give all threads permission at the start.
	protected boolean[] initializePermissionTable(boolean[] permissionTable) {
		for (int i = 0; i < numOfThreads; i++) {
			permissionTable[i] = true;
		}
		return permissionTable;
	}

	@Override
	public boolean hasNext() {
		if (breakAll.get() == false) {
			int id = threadID.get();

			if (localChunkStack.get(id).size() == 0) {
				permissionTable[id] = true;
			} else {
				permissionTable[id] = false;
			}

			// Retrieve free nodes to fill up chunk size quota.
			if (permissionTable[id]) { // Get free nodes.
				for (int i = 0; i < chunkSize; i++) {
					// Prevent retrieval of free nodes if chunk size quota has
					// been filled.
					if (localChunkStack.get(id).size() < chunkSize) {
						lock.lock();
						V node = freeNodeStack.poll();
						lock.unlock();

						if (node != null) {
							if (!processedNodes.contains(node)) {
								localChunkStack.get(id).push(node);
							}
						}
					}
				}
			}

			V nextNode = getLocalNode();
			if (nextNode != null) {
				buffer[id][0] = nextNode;
				processedNodes.add(nextNode);
				checkFreeNodes(nextNode);
				return true;
			}

			if (processedNodes.size() == numTreeNodes) {
				exit(latch);
				return false;
			}

		}
		exit(latch);
		return false;
	}

	/**
	 * Threads call this method to exit.
	 * 
	 * @param latch
	 */
	protected void exit(CountDownLatch latch) {
		latch.countDown(); // Sign off thread.
		try {
			latch.await(); // Wait for other threads to sign off.
		} catch (InterruptedException e) {
			System.out.println("Interrupted Exception");
		}
	}

	/**
	 * @return node from the local stack of the thread.
	 */
	private synchronized V getLocalNode() {
		int id = threadID.get();

		V localNode = localChunkStack.get(id).poll();

		if (localNode != null) {
			if (processedNodes.containsAll(graph.getChildrenList(localNode))
					&& !processedNodes.contains(localNode)) {

				return localNode;
			} else {
				waitingList.add(localNode);
				return getLocalNode();
			}
		} else {
			return null;
		}
	}

	/**
	 * Check if any of the parents (nodes to be processed next) have become free
	 * nodes.
	 * 
	 * @param node
	 */
	protected void checkFreeNodes(V node) {
		int id = threadID.get();

		@SuppressWarnings("unchecked")
		Iterator<V> it = graph.getParentsList(node).iterator();

		V parent;
		while (it.hasNext()) {
			parent = it.next();
			if (processedNodes.containsAll(graph.getChildrenList(parent))
					&& !processedNodes.contains(parent)) {
				// Parent has become a free node.
				freeNodeStack.offerLast(parent);

			}
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public V next() {
		int id = threadID.get();
		V nextNode = (V) buffer[id][0];
		return nextNode;
	}

	@Override
	public boolean localBreak() {
		return false;
	}

}
