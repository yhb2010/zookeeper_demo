package com.ch4;

import java.io.IOException;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.zookeeper.AsyncCallback.ChildrenCallback;
import org.apache.zookeeper.AsyncCallback.DataCallback;
import org.apache.zookeeper.AsyncCallback.StatCallback;
import org.apache.zookeeper.AsyncCallback.StringCallback;
import org.apache.zookeeper.AsyncCallback.VoidCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;

/**从节点等待分配新任务
 * @author Administrator
 *
 */
public class Worker implements Watcher {
	
	ZooKeeper zk;
	String hostPort;
	Random random = new Random(7);
	String serverID = Integer.toString(random.nextInt());
	private volatile boolean connected = false;
    private volatile boolean expired = false;
    
    /*
     * In general, it is not a good idea to block the callback thread
     * of the ZooKeeper client. We use a thread pool executor to detach
     * the computation from the callback.
     */
    private ThreadPoolExecutor executor;
    
    /**
     * Creates a new Worker instance.
     * 
     * @param hostPort 
     */
    public Worker(String hostPort) { 
    	super();
        this.hostPort = hostPort;
        this.executor = new ThreadPoolExecutor(1, 1, 
                1000L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(200));
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
    
    /**
     * Bootstrapping here is just creating a /assign parent
     * znode to hold the tasks assigned to this worker.
     */
    public void bootstrap(){
    	//我们需要先创建/assign/worker-id节点，而且从节点需要在/assign/worker-id节点上设置监视点来接受新任务分配的通知
        createAssignNode();
    }
    
    void createAssignNode(){
        zk.create("/assign/worker-" + serverID, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT,
                createAssignCallback, null);
    }
    
    StringCallback createAssignCallback = new StringCallback() {
        public void processResult(int rc, String path, Object ctx, String name) {
            switch (Code.get(rc)) { 
            case CONNECTIONLOSS:
            	//再次注册自己不会有问题，因为如果znode节点已经存在，我们会收到NODEEXISTS事件
                createAssignNode();
                break;
            case OK:
                System.out.println("Assign node created");
                break;
            case NODEEXISTS:
            	System.out.println("Assign node already registered");
                break;
            default:
            	System.out.println("Something went wrong: " + KeeperException.create(Code.get(rc), path));
            }
        }
    };
    
    String name;
    /**
     * Registering the new worker, which consists of adding a worker
     * znode to /workers.
     */
    public void register(){
        name = "worker-" + serverID;
        //首先向zookeeper注册自己
        //添加该znode节点会通知主节点，这个从节点的状态是活跃的，且已准备好了处理任务
        zk.create("/workers/" + name,
                "Idle".getBytes(), 
                Ids.OPEN_ACL_UNSAFE, 
                CreateMode.EPHEMERAL,
                createWorkerCallback, null);
    }
    
    StringCallback createWorkerCallback = new StringCallback() {
        public void processResult(int rc, String path, Object ctx, String name) {
            switch (Code.get(rc)) { 
            case CONNECTIONLOSS:
                //再次注册自己不会有问题，因为如果znode节点已经存在，我们会收到NODEEXISTS事件
                register();
                
                break;
            case OK:
                System.out.println("Registered successfully: " + serverID);
                
                break;
            case NODEEXISTS:
            	System.out.println("Already registered: " + serverID);
                
                break;
            default:
            	System.out.println("Something went wrong: ");
            }
        }
    };
    
    void getTasks(){
    	//从节点需要在/assign/worker-id节点上设置监视点来接受新任务分配的通知
        zk.getChildren("/assign/worker-" + serverID, 
                newTaskWatcher, 
                tasksGetChildrenCallback, 
                null);
    }
    
    Watcher newTaskWatcher = new Watcher(){
        public void process(WatchedEvent e) {
            if(e.getType() == EventType.NodeChildrenChanged) {
                assert new String("/assign/worker-"+ serverID ).equals( e.getPath() );
                
                getTasks();
            }
        }
    };
    
    protected ChildrenCache assignedTasksCache = new ChildrenCache();
    ChildrenCallback tasksGetChildrenCallback = new ChildrenCallback() {
        public void processResult(int rc, String path, Object ctx, List<String> children){
            switch(Code.get(rc)) { 
            case CONNECTIONLOSS:
                getTasks();
                break;
            case OK:
            	//一旦有任务列表分配给从节点，从节点就会从/assign/worker-id后去任务信息并执行任务。
            	//从节点从本地列表中获取每个任务的信息并验证任务是否在待执行的队列中，从节点保存一个本地待执行
            	//任务的列表就是为了这个目的。注意，为了释放回调方法的线程，我们在单独的线程对从节点的已分配任务
            	//进行循环，否则，我们会阻塞其他的回调方法的执行。
                if(children != null){
                    executor.execute(new Runnable() {
                        List<String> children;
                        DataCallback cb;
                        
                        public Runnable init (List<String> children, DataCallback cb) {
                            this.children = children;
                            this.cb = cb;
                            
                            return this;
                        }
                        
                        public void run() {
                            if(children == null) {
                                return;
                            }
    
                            System.out.println("Looping into tasks");
                            setStatus("Working");
                            for(String task : children){
                                zk.getData("/assign/worker-" + serverID  + "/" + task,
                                        false,
                                        cb,
                                        task);   
                            }
                        }
                    }.init(assignedTasksCache.addedAndSet(children), taskDataCallback));
                } 
                break;
            default:
                System.out.println("getChildren failed: " + KeeperException.create(Code.get(rc), path));
            }
        }
    };
    
    String status;
    synchronized private void updateStatus(String status) {
        if (status == this.status) {
            zk.setData("/workers/" + name, status.getBytes(), -1,
                statusUpdateCallback, status);
        }
    }
    
    StatCallback statusUpdateCallback = new StatCallback() {
        public void processResult(int rc, String path, Object ctx, Stat stat) {
            switch(Code.get(rc)) {
            case CONNECTIONLOSS:
                updateStatus((String)ctx);
                return;
            }
        }
    };

    public void setStatus(String status) {
        this.status = status;
        updateStatus(status);
    }
    
    DataCallback taskDataCallback = new DataCallback() {
        public void processResult(int rc, String path, Object ctx, byte[] data, Stat stat){
            switch(Code.get(rc)) {
            case CONNECTIONLOSS:
                zk.getData(path, false, taskDataCallback, null);
                break;
            case OK:
                /*
                 *  Executing a task in this example is simply printing out
                 *  some string representing the task.
                 */
                executor.execute( new Runnable() {
                    byte[] data;
                    Object ctx;
                    
                    /*
                     * Initializes the variables this anonymous class needs
                     */
                    public Runnable init(byte[] data, Object ctx) {
                        this.data = data;
                        this.ctx = ctx;
                        
                        return this;
                    }
                    
                    public void run() {
                        System.out.println("Executing your task: " + new String(data));
                        zk.create("/status/" + (String) ctx, "done".getBytes(), Ids.OPEN_ACL_UNSAFE, 
                                CreateMode.PERSISTENT, taskStatusCreateCallback, null);
                        zk.delete("/assign/worker-" + serverID + "/" + (String) ctx, 
                                -1, taskVoidCallback, null);
                    }
                }.init(data, ctx));
                
                break;
            default:
            	System.out.println("Failed to get task data: ");
            }
        }
    };
    
    StringCallback taskStatusCreateCallback = new StringCallback(){
        public void processResult(int rc, String path, Object ctx, String name) {
            switch(Code.get(rc)) {
            case CONNECTIONLOSS:
                zk.create(path + "/status", "done".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT,
                        taskStatusCreateCallback, null);
                break;
            case OK:
            	System.out.println("Created status znode correctly: " + name);
                break;
            case NODEEXISTS:
            	System.out.println("Node exists: " + path);
                break;
            default:
            	System.out.println("Failed to create task data: ");
            }
            
        }
    };
    
    VoidCallback taskVoidCallback = new VoidCallback(){
        public void processResult(int rc, String path, Object rtx){
            switch(Code.get(rc)) {
            case CONNECTIONLOSS:
                break;
            case OK:
                System.out.println("Task correctly deleted: " + path);
                break;
            default:
            	System.out.println("Failed to delete task data");
            } 
        }
    };
    
    public static void main(String args[]) throws Exception { 
        Worker w = new Worker("127.0.0.145:2181,127.0.0.145:2182,127.0.0.145:2183");
        w.startZK();
        
        while(!w.isConnected()){
            Thread.sleep(100);
        }   
        /*
         * bootstrap() create some necessary znodes.
         */
        w.bootstrap();
        
        /*
         * Registers this worker so that the leader knows that
         * it is here.
         */
        w.register();
        
        /*
         * Getting assigned tasks.
         */
        w.getTasks();
        
        while(!w.isExpired()){
            Thread.sleep(1000);
        }
        
    }

}
