package com.ch8curator;

import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.api.CuratorEvent;
import org.apache.curator.framework.api.CuratorEventType;
import org.apache.curator.framework.api.CuratorListener;
import org.apache.curator.framework.api.UnhandledErrorListener;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent.Type;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheListener;
import org.apache.curator.framework.recipes.leader.LeaderLatch;
import org.apache.curator.framework.recipes.leader.LeaderLatchListener;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.retry.ExponentialBackoffRetry;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.zookeeper.CreateMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CuratorMasterLatch implements Closeable, LeaderLatchListener {
	private static final Logger LOG = LoggerFactory
			.getLogger(CuratorMasterLatch.class);

	private String myId;
	private CuratorFramework client;
	private final LeaderLatch leaderLatch;
	private final PathChildrenCache workersCache;
	private final PathChildrenCache tasksCache;

	/**
	 * Creates a new Curator client, setting the the retry policy to
	 * ExponentialBackoffRetry.
	 *
	 * @param myId
	 *            master identifier
	 * @param hostPort
	 *            list of zookeeper servers comma-separated
	 * @param retryPolicy
	 *            Curator retry policy
	 */
	public CuratorMasterLatch(String myId, String hostPort,
			RetryPolicy retryPolicy) {
		LOG.info(myId + ": " + hostPort);

		this.myId = myId;
		this.client = CuratorFrameworkFactory.newClient(hostPort, retryPolicy);
		//群首闩：进行主节点选举的操作
		this.leaderLatch = new LeaderLatch(this.client, "/master", myId);
		this.workersCache = new PathChildrenCache(this.client, "/workers", true);
		this.tasksCache = new PathChildrenCache(this.client, "/tasks", true);
	}

	public void startZK() {
		client.start();
	}

	public void bootstrap() throws Exception {
		client.create().withMode(CreateMode.PERSISTENT).forPath("/workers", new byte[0]);
        client.create().withMode(CreateMode.PERSISTENT).forPath("/assign", new byte[0]);
        client.create().withMode(CreateMode.PERSISTENT).forPath("/tasks", new byte[0]);
        client.create().withMode(CreateMode.PERSISTENT).forPath("/status", new byte[0]);
	}

	public void runForMaster() throws Exception {
		/*
		 * Register listeners
		 */
		client.getCuratorListenable().addListener(masterListener);
		//负责处理后台工作线程捕获的异常时的错误报告
		client.getUnhandledErrorListenable().addListener(errorsListener);

		/*
		 * Start master election
		 */
		LOG.info("Starting master selection: " + myId);
		leaderLatch.addListener(this);
		leaderLatch.start();
	}

	/**
	 * Waits until it becomes leader.
	 *
	 * @throws InterruptedException
	 */
	public void awaitLeadership() throws InterruptedException, EOFException {
		leaderLatch.await();
	}

	CountDownLatch recoveryLatch = new CountDownLatch(0);

	/**
	 * Executed when client becomes leader.
	 *
	 * @throws Exception
	 */
	@Override
	public void isLeader() {
		/*
		 * Start workersCache
		 */
		try {
			workersCache.getListenable().addListener(workersCacheListener);
			workersCache.start();
		} catch (Exception e) {
			LOG.error("Exception when starting leadership", e);
		}
	}

	@Override
	public void notLeader() {
		LOG.info("Lost leadership");
		try {
			close();
		} catch (IOException e) {
			LOG.warn("Exception while closing", e);
		}
	}

	public boolean isConnected() {
		return client.getZookeeperClient().isConnected();
	}

	/*
	 * We use one main listener for the master. The listener processes callback
	 * and watch events from various calls we make. Note that many of the events
	 * related to workers and tasks are processed directly by the workers cache
	 * and the tasks cache.
	 */
	//之前每个回调方法都需要提供一个不同的回调实现，而在curator实例中，回调方法或监视点通知这些细节均被封装为Event类，
	//这也是更适合使用一个事件处理器的实现方式
	CuratorListener masterListener = new CuratorListener() {
		public void eventReceived(CuratorFramework client, CuratorEvent event) {
			try {
				LOG.info("Event path: " + event.getPath());
				switch (event.getType()) {
				case CHILDREN:
					if (event.getPath().contains("/assign")) {
						LOG.info("Succesfully got a list of assignments: "
								+ event.getChildren().size() + " tasks");
						/*
						 * Delete the assignments of the absent worker
						 */
						for (String task : event.getChildren()) {
							deleteAssignment(event.getPath() + "/" + task);
						}

						/*
						 * Delete the znode representing the absent worker in
						 * the assignments.
						 */
						deleteAssignment(event.getPath());

						/*
						 * Reassign the tasks.
						 */
						assignTasks(event.getChildren());
					} else {
						LOG.warn("Unexpected event: " + event.getPath());
					}

					break;
				case CREATE:
					/*
					 * Result of a create operation when assigning a task.
					 */
					if (event.getPath().contains("/assign")) {
						LOG.info("Task assigned correctly: " + event.getName());
						deleteTask(event.getPath().substring(
								event.getPath().lastIndexOf('-') + 1));
					}

					break;
				case DELETE:
					/*
					 * We delete znodes in two occasions: 1- When reassigning
					 * tasks due to a faulty worker; 2- Once we have assigned a
					 * task, we remove it from the list of pending tasks.
					 */
					if (event.getPath().contains("/tasks")) {
						LOG.info("Result of delete operation: "
								+ event.getResultCode() + ", "
								+ event.getPath());
					} else if (event.getPath().contains("/assign")) {
						LOG.info("Task correctly deleted: " + event.getPath());
						break;
					}

					break;
				case WATCHED:
					// There is no case implemented currently.

					break;
				default:
					LOG.error("Default case: " + event.getType());
				}
			} catch (Exception e) {
				LOG.error("Exception while processing event.", e);
				try {
					close();
				} catch (IOException ioe) {
					LOG.error("IOException while closing.", ioe);
				}
			}
		};
	};

	PathChildrenCacheListener workersCacheListener = new PathChildrenCacheListener() {
		public void childEvent(CuratorFramework client,
				PathChildrenCacheEvent event) {
			if (event.getType() == PathChildrenCacheEvent.Type.CHILD_REMOVED) {
				/*
				 * Obtain just the worker's name
				 */
				try {
					getAbsentWorkerTasks(event.getData().getPath()
							.replaceFirst("/workers/", ""));
				} catch (Exception e) {
					LOG.error("Exception while trying to re-assign tasks", e);
				}
			}
		}
	};

	PathChildrenCacheListener tasksCacheListener = new PathChildrenCacheListener() {
		public void childEvent(CuratorFramework client,
				PathChildrenCacheEvent event) {
			if (event.getType() == PathChildrenCacheEvent.Type.CHILD_ADDED) {
				try {
					assignTask(
							event.getData().getPath()
									.replaceFirst("/tasks/", ""), event
									.getData().getData());
				} catch (Exception e) {
					LOG.error("Exception when assigning task.", e);
				}
			}
		}
	};

	private void getAbsentWorkerTasks(String worker) throws Exception {
		//改为异步调用，该方法会立刻返回，需要创建一个或多个监听器来接收znode节点创建后的返回
		client.getChildren().inBackground().forPath("/assign/" + worker);
	}

	void deleteAssignment(String path) throws Exception {
		/*
		 * Delete assignment
		 */
		LOG.info("Deleting assignment: {}", path);
		client.delete().inBackground().forPath(path);
	}

	/*
	 * Random variable we use to select a worker to perform a pending task.
	 */
	Random rand = new Random(System.currentTimeMillis());

	void assignTasks(List<String> tasks) throws Exception {
		for (String task : tasks) {
			assignTask(task, client.getData().forPath("/tasks/" + task));
		}
	}

	void assignTask(String task, byte[] data) throws Exception {
		/*
		 * Choose worker at random.
		 */
		// String designatedWorker =
		// workerList.get(rand.nextInt(workerList.size()));
		List<ChildData> workersList = workersCache.getCurrentData();

		LOG.info("Assigning task {}, data {}", task, new String(data));

		String designatedWorker = workersList
				.get(rand.nextInt(workersList.size())).getPath()
				.replaceFirst("/workers/", "");

		/*
		 * Assign task to randomly chosen worker.
		 */
		String path = "/assign/" + designatedWorker + "/" + task;
		createAssignment(path, data);
	}

	/**
	 * Creates an assignment.
	 *
	 * @param path
	 *            path of the assignment
	 */
	void createAssignment(String path, byte[] data) throws Exception {
		/*
		 * The default ACL is ZooDefs.Ids#OPEN_ACL_UNSAFE
		 */
		client.create().withMode(CreateMode.PERSISTENT).inBackground()
				.forPath(path, data);
	}

	/*
	 * Once assigned, we delete the task from /tasks
	 */
	void deleteTask(String number) throws Exception {
		LOG.info("Deleting task: {}", number);
		client.delete().inBackground().forPath("/tasks/task-" + number);
		recoveryLatch.countDown();
	}

	@Override
	public void close() throws IOException {
		LOG.info("Closing");
		leaderLatch.close();
		client.close();
	}

	UnhandledErrorListener errorsListener = new UnhandledErrorListener() {
		public void unhandledError(String message, Throwable e) {
			LOG.error("Unrecoverable error: " + message, e);
			try {
				close();
			} catch (IOException ioe) {
				LOG.warn("Exception when closing.", ioe);
			}
		}
	};

	public static void main(String[] args) {
		try {
			CuratorMasterLatch master = new CuratorMasterLatch(args[0],
					args[1], new ExponentialBackoffRetry(1000, 5));
			master.startZK();
			master.bootstrap();
			master.runForMaster();
		} catch (Exception e) {
			LOG.error("Exception while running curator master.", e);
		}
	}
}