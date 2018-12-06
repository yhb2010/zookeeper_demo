package com.ch4;

import java.io.IOException;
import java.util.List;
import java.util.Random;

import org.apache.zookeeper.AsyncCallback.ChildrenCallback;
import org.apache.zookeeper.AsyncCallback.DataCallback;
import org.apache.zookeeper.AsyncCallback.StatCallback;
import org.apache.zookeeper.AsyncCallback.StringCallback;
import org.apache.zookeeper.AsyncCallback.VoidCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;

/**主从模式的例子
 * 1、管理权变化
 * 2、主节点等待从节点列表的变化
 * 3、主节点等待新任务进行分配
 * 4、从节点等待分配新任务
 * 5、客户端等待任务的执行结果
 * @author dell
 *
 * 观察模式触发器 Watch triggers
 * 读操作，例如：exists、getChildren、getData会在znode上开启观察模式，
 * 并且写操作会触发观察模式事件，例如：create、delete和setData。
 * ACL(Access Control List)操作不会启动观察模式。
 * 观察模式被触发时，会生成一个事件，这个事件的类型取决于触发他的操作：
 * exists启动的观察模式，由创建znode，删除znode和更新znode操作来触发。
 * getData启动的观察模式，由删除znode和更新znode操作触发。创建znode不会触发，是因为getData操作成功的前提是znode必须已经存在。
 * getChildren启动的观察模式，由子节点创建和删除，或者本节点被删除时才会被触发。我们可以通过事件的类型来判断是本节点被删除还是子节点被删除：NodeChildrenChanged表示子节点被删除，而NodeDeleted表示本节点删除。
 *
 */
public class Master implements Watcher {

	ZooKeeper zk;
	String hostPort;
	Random random = new Random(7);
	String serverID = Integer.toString(random.nextInt());
	boolean isLeader = false;
	private volatile boolean connected = false;
    private volatile boolean expired = false;

	StringCallback masterCreateCallback = new StringCallback() {
		//rc：返回调用的结构，返回OK或与KeeperException异常对应的编码值
		//path：我们传给create的path参数值
		//ctx：我们传给create的上下文参数
		//name：创建的znode节点名称
		//回调处理：因为只有一个单独的线程处理所有回调调用，如果回调函数阻塞，所有后续回调调用都会被阻塞，也就是说，
		//一般不要在回调函数中集中操作或阻塞操作
		@Override
        public void processResult(int rc, String path, Object ctx, String name) {
            switch (Code.get(rc)) {
            //如果因连接丢失导致create请求失败，我们会得到CONNECTIONLOSS编码结果，当连接丢失时，我们需要检查
            //系统当前的状态，并判断我们需要如何恢复
            case CONNECTIONLOSS:
                checkMaster();
                break;
            case OK:
            	isLeader = true;
                break;
            case NODEEXISTS:
            	//create操作失败，意味着主节点已经存在，客户端就会执行exists操作来设置/master节点的监视点
            	masterExists();
            	isLeader = false;
                break;
            default:
            	isLeader = false;
            }
            System.out.println("i am " + (isLeader ? "" : "not ") + "the leader");
        }
    };

    DataCallback masterCheckCallback = new DataCallback() {
    	@Override
        public void processResult(int rc, String path, Object ctx, byte[] data, Stat stat) {
            switch (Code.get(rc)) {
            case CONNECTIONLOSS:
                checkMaster();
                break;
            case NONODE:
                runForMaster();
                break;
            case OK:
                if( serverID.equals( new String(data) ) ) {
                	isLeader = true;
                } else {
                	masterExists();
                	isLeader = false;
                }
                break;
            default:
                System.out.println("Error when reading data.");
            }
        }
    };

	public void checkMaster() {
		zk.getData("/master", false, masterCheckCallback, null);
	}

	public void runForMaster() {
		//该方法调用后通常在create请求发送到服务器端前就会立刻返回，回调对象通过传入的上下文参数来获取数据
		zk.create("/master",
                serverID.getBytes(),
                Ids.OPEN_ACL_UNSAFE,
                CreateMode.EPHEMERAL,
                masterCreateCallback,//提供回调方法的对象
                null);//用户指定的上下文信息(回调方法调用时传入的对象实例)
	}

	public Master(String hostPort) {
		super();
		this.hostPort = hostPort;
	}

	void startZK() throws IOException{
		zk = new ZooKeeper(hostPort, 15000, this);
	}

	@Override
	public void process(WatchedEvent e) {
		System.out.println(e);
		if(e.getType() == Event.EventType.None){
            switch (e.getState()) {
            case SyncConnected:
                connected = true;
                break;
            case Disconnected:
                connected = false;
                break;
            case Expired:
                expired = true;
                connected = false;
                System.out.println("Session expiration");
            default:
                break;
            }
        }
	}

	void stopZK() throws InterruptedException{
		zk.close();
	}

	boolean isConnected() {
        return connected;
    }

    boolean isExpired() {
        return expired;
    }

    public void bootstrap(){
    	//没有数据存入这些znode节点，所以只传入空的字节数组
        createParent("/workers", new byte[0]);
        createParent("/assign", new byte[0]);
        createParent("/tasks", new byte[0]);
        createParent("/status", new byte[0]);
    }

    void createParent(String path, byte[] data){
    	//最后传入data，我们可以在回调函数中继续使用这个数据
        zk.create(path,
                data,
                Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT,
                createParentCallback,
                data);
    }

    StringCallback createParentCallback = new StringCallback() {
        public void processResult(int rc, String path, Object ctx, String name) {
            switch (Code.get(rc)) {
            case CONNECTIONLOSS:
                //可以对create操作进行重试，ctx就是create方法传入的数据
                createParent(path, (byte[]) ctx);
                break;
            case OK:
                System.out.println("Parent created");
                break;
            case NODEEXISTS:
            	System.out.println("Parent already registered: " + path);
                break;
            default:
            	System.out.println("Something went wrong");
            }
        }
    };

    //设置主节点监视点
    void masterExists() {
        zk.exists("/master",
                masterExistsWatcher,
                masterExistsCallback,
                null);
    }

    StatCallback masterExistsCallback = new StatCallback() {
        public void processResult(int rc, String path, Object ctx, Stat stat){
            switch (Code.get(rc)) {
            case CONNECTIONLOSS:
            	//连接丢失时，需要再次设置监视点
                masterExists();

                break;
            case OK:
            	//在竞选主节点和执行exists操作之间，也许/master节点已经删除了，这时，如果exists调用返回stat为空，
            	//则说明znode节点不存在，需要竞选主节点
            	if(stat == null){
            		runForMaster();
            	}
                break;
            case NONODE:
                runForMaster();
                System.out.println("It sounds like the previous master is gone, " +
                    		"so let's run for master again.");

                break;
            default:
                checkMaster();
                break;
            }
        }
    };

    Watcher masterExistsWatcher = new Watcher(){
        public void process(WatchedEvent e) {
        	//如果master节点被删除，那么再次竞选主节点
            if(e.getType() == EventType.NodeDeleted) {
                assert "/master".equals( e.getPath() );

                runForMaster();
            }
        }
    };

    //监视从节点列表的变化
    void getWorkers(){
        zk.getChildren("/workers",
                workersChangeWatcher,
                workersGetChildrenCallback,
                null);
    }

    Watcher workersChangeWatcher = new Watcher() {
        public void process(WatchedEvent e) {
            if(e.getType() == EventType.NodeChildrenChanged) {
                assert "/workers".equals(e.getPath());

                getWorkers();
            }
        }
    };

    ChildrenCallback workersGetChildrenCallback = new ChildrenCallback() {
        public void processResult(int rc, String path, Object ctx, List<String> children){
            switch (Code.get(rc)) {
            case CONNECTIONLOSS:
                getWorkers();
                break;
            case OK:
                System.out.println("Succesfully got a list of workers: "
                        + children.size()
                        + " workers");
                //重新分配崩溃从节点任务，并重新设置新的从节点列表
                reassignAndSet(children);
                break;
            default:
            	System.out.println("getChildren failed");
            }
        }
    };

    //用于保存上次获得的从节点列表的本地缓存
    protected ChildrenCache workersCache;
    void reassignAndSet(List<String> children){
        List<String> toProcess;

        if(workersCache == null) {
        	//如果是第一次使用本地缓存这个变量，那么初始化该变量
            workersCache = new ChildrenCache(children);
            //如果第一次获得所有从节点时，不需要做什么其他事
            toProcess = null;
        } else {
            System.out.println( "Removing and setting" );
            //如果不是第一次，那么需要检查是否有从节点已经被移除了
            //返回被删除的节点
            toProcess = workersCache.removedAndSet( children );
        }

        if(toProcess != null) {
            for(String worker : toProcess){
            	//如果有从节点被移除了，我们就需要重新分配任务
                getAbsentWorkerTasks(worker);
            }
        }
    }

    void getAbsentWorkerTasks(String worker){
        zk.getChildren("/assign/" + worker, false, workerAssignmentCallback, null);
    }

    ChildrenCallback workerAssignmentCallback = new ChildrenCallback() {
        public void processResult(int rc, String path, Object ctx, List<String> children){
            switch (Code.get(rc)) {
            case CONNECTIONLOSS:
                getAbsentWorkerTasks(path);

                break;
            case OK:
                System.out.println("Succesfully got a list of assignments: "
                        + children.size()
                        + " tasks");

                /*
                 * Reassign the tasks of the absent worker.
                 */
                for(String task: children) {
                    getDataReassign(path + "/" + task, task);
                }
                break;
            default:
            	System.out.println("getChildren failed");
            }
        }
    };

    /**
     * Get reassigned task data.
     *
     * @param path Path of assigned task
     * @param task Task name excluding the path prefix
     */
    void getDataReassign(String path, String task) {
        zk.getData(path,
                false,
                getDataReassignCallback,
                task);
    }

    /**
     * Get task data reassign callback.
     */
    DataCallback getDataReassignCallback = new DataCallback() {
        public void processResult(int rc, String path, Object ctx, byte[] data, Stat stat)  {
            switch(Code.get(rc)) {
            case CONNECTIONLOSS:
                getDataReassign(path, (String) ctx);

                break;
            case OK:
                recreateTask(new RecreateTaskCtx(path, (String) ctx, data));

                break;
            default:
                System.out.println("Something went wrong when getting data ");
            }
        }
    };

    /**
     * Context for recreate operation.
     *
     */
    class RecreateTaskCtx {
        String path;
        String task;
        byte[] data;

        RecreateTaskCtx(String path, String task, byte[] data) {
            this.path = path;
            this.task = task;
            this.data = data;
        }
    }

    /**
     * Recreate task znode in /tasks
     *
     * @param ctx Recreate text context
     */
    void recreateTask(RecreateTaskCtx ctx) {
        zk.create("/tasks/" + ctx.task,
                ctx.data,
                Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT,
                recreateTaskCallback,
                ctx);
    }

    /**
     * Recreate znode callback
     */
    StringCallback recreateTaskCallback = new StringCallback() {
        public void processResult(int rc, String path, Object ctx, String name) {
            switch(Code.get(rc)) {
            case CONNECTIONLOSS:
                recreateTask((RecreateTaskCtx) ctx);

                break;
            case OK:
                deleteAssignment(((RecreateTaskCtx) ctx).path);

                break;
            case NODEEXISTS:
                System.out.println("Node exists already, but if it hasn't been deleted, " +
                		"then it will eventually, so we keep trying: " + path);
                recreateTask((RecreateTaskCtx) ctx);

                break;
            default:
            	System.out.println("Something wwnt wrong when recreating task");
            }
        }
    };

    /**
     * Delete assignment of absent worker
     *
     * @param path Path of znode to be deleted
     */
    void deleteAssignment(String path){
        zk.delete(path, -1, taskDeletionCallback, null);
    }

    VoidCallback taskDeletionCallback = new VoidCallback(){
        public void processResult(int rc, String path, Object rtx){
            switch(Code.get(rc)) {
            case CONNECTIONLOSS:
                deleteAssignment(path);
                break;
            case OK:
                System.out.println("Task correctly deleted: " + path);
                break;
            default:
            	System.out.println("Failed to delete task data" +
                        KeeperException.create(Code.get(rc), path));
            }
        }
    };

    //主节点等待新任务进行分配
    //主要主节点等待添加到/tasks节点中的新任务。主节点首先获得当前的任务集，并设置变化情况的监视点。
    ///tasks的子节点表示任务集，每个子节点对应一个任务，一旦主节点获取还未
    //分配的任务信息，主节点会随机选择一个从节点，将这个任务分配给从节点
    void getTasks(){
    	//获得任务列表
        zk.getChildren("/tasks",
                tasksChangeWatcher,
                tasksGetChildrenCallback,
                null);
    }

    Watcher tasksChangeWatcher = new Watcher() {
        public void process(WatchedEvent e) {
            if(e.getType() == EventType.NodeChildrenChanged) {
                assert "/tasks".equals( e.getPath() );

                getTasks();
            }
        }
    };

    protected ChildrenCache tasksCache;
    ChildrenCallback tasksGetChildrenCallback = new ChildrenCallback() {
        public void processResult(int rc, String path, Object ctx, List<String> children){
            switch(Code.get(rc)) {
            case CONNECTIONLOSS:
                getTasks();

                break;
            case OK:
                List<String> toProcess;
                if(tasksCache == null) {
                    tasksCache = new ChildrenCache(children);

                    toProcess = children;
                } else {
                    toProcess = tasksCache.addedAndSet( children );
                }

                if(toProcess != null){
                	//分配任务列表里的任务
                    assignTasks(toProcess);
                }

                break;
            default:
                System.out.println("getChildren failed.");
            }
        }
    };

    //该方法简单的分配在/tasks子节点表示的列表中的每个任务，在建立任务分配的znode节点前，首先通过getData方法获取任务信息
    void assignTasks(List<String> tasks) {
        for(String task : tasks){
            getTaskData(task);
        }
    }

    void getTaskData(String task) {
    	//获取任务信息
        zk.getData("/tasks/" + task,
                false,
                taskDataCallback,
                task);
    }

    DataCallback taskDataCallback = new DataCallback() {
        public void processResult(int rc, String path, Object ctx, byte[] data, Stat stat)  {
            switch(Code.get(rc)) {
            case CONNECTIONLOSS:
                getTaskData((String) ctx);

                break;
            case OK:
            	//随机选择一个从节点，分配任务给这个从节点
                List<String> list = workersCache.getList();
                String designatedWorker = list.get(random.nextInt(list.size()));

                String assignmentPath = "/assign/" +
                        designatedWorker +
                        "/" +
                        (String) ctx;
                System.out.println( "Assignment path: " + assignmentPath );
                createAssignment(assignmentPath, data);

                break;
            default:
            	System.out.println("Error when trying to get task data." );
            }
        }
    };

    void createAssignment(String path, byte[] data){
    	//创建分配节点，路径形式为/assign/worker-id/task-num
    	//id为从节点标识符。
    	//当主节点为某个标识符为id的从节点创建任务分配节点时，假设从节点在任务分配节点(/assign/worker-id)上注册了监视点，zookeeper会向从节点发送一个通知。
        zk.create(path,
                data,
                Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT,
                assignTaskCallback,
                data);
    }

    StringCallback assignTaskCallback = new StringCallback() {
        public void processResult(int rc, String path, Object ctx, String name) {
            switch(Code.get(rc)) {
            case CONNECTIONLOSS:
                createAssignment(path, (byte[]) ctx);

                break;
            case OK:
                System.out.println("Task assigned correctly: " + name);
                //如果分配任务成功后，我们会删除/tasks下的这个子节点，这样，主节点就不需要记住分配了哪些任务
                deleteTask(name.substring( name.lastIndexOf("/") + 1));

                break;
            case NODEEXISTS:
            	System.out.println("Task already assigned");

                break;
            default:
            	System.out.println("Error when trying to assign task.");
            }
        }
    };

    void deleteTask(String name){
        zk.delete("/tasks/" + name, -1, taskDeleteCallback, null);
    }

    VoidCallback taskDeleteCallback = new VoidCallback(){
        public void processResult(int rc, String path, Object ctx){
            switch (Code.get(rc)) {
            case CONNECTIONLOSS:
                deleteTask(path);

                break;
            case OK:
                System.out.println("Successfully deleted " + path);

                break;
            case NONODE:
            	System.out.println("Task has been deleted already");

                break;
            default:
            	System.out.println("Something went wrong here");
            }
        }
    };

	public static void main(String[] args) throws InterruptedException, IOException, KeeperException {
		Master m = new Master("127.0.0.145:2181,127.0.0.145:2182,127.0.0.145:2183");
		m.startZK();
		while(!m.isConnected()){
            Thread.sleep(100);
        }

        //创建/tasks,/assign, /workers，我们在系统启动前通过某些系统配置来创建所有目录，或者通过在主节点程序每次启动时都创建这些目录
		m.bootstrap();

        m.runForMaster();

        while(!m.isExpired()){
            Thread.sleep(1000);
        }

        m.stopZK();
	}

}
