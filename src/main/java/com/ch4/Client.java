package com.ch4;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

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
import org.apache.zookeeper.Watcher.Event;
import org.apache.zookeeper.data.Stat;

/**客户端等待任务的执行结果
 * @author Administrator
 *
 */
public class Client implements Watcher {
	
	ZooKeeper zk;
	String hostPort;
	private volatile boolean connected = false;
    private volatile boolean expired = false;
    
    Client(String hostPort) { 
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
    
    boolean isConnected() {
        return connected;
    }

    boolean isExpired() {
        return expired;
    }
    
    static class TaskObject {
        private String task;
        private String taskName;
        private boolean done = false;
        private boolean succesful = false;
        private CountDownLatch latch = new CountDownLatch(1);
        
        String getTask () {
            return task;
        }
        
        void setTask (String task) {
            this.task = task;
        }
        
        void setTaskName(String name){
            this.taskName = name;
        }
        
        String getTaskName (){
            return taskName;
        }
        
        void setStatus (boolean status){
            succesful = status;
            done = true;
            latch.countDown();
        }
        
        void waitUntilDone () {
            try{
                latch.await();
            } catch (InterruptedException e) {
                System.out.println("InterruptedException while waiting for task to get done");
            }
        }
        
        synchronized boolean isDone(){
            return done;     
        }
        
        synchronized boolean isSuccesful(){
            return succesful;
        }
        
    }
    
    //提交任务，假设客户端已经提交了一个任务，现在客户端需要知道该任务何时被执行，以及任务状态。
    //回忆之前的内容，从节点执行一个任务时，会在/status下创建一个znode节点。
    void submitTask(String task, TaskObject taskCtx){
        taskCtx.setTask(task);
        zk.create("/tasks/task-", 
                task.getBytes(),
                Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT_SEQUENTIAL,
                createTaskCallback,   
                //传递了上下文对象，该对象为我们实现的Task类的实例
                taskCtx);
    }
    
    StringCallback createTaskCallback = new StringCallback() {
        public void processResult(int rc, String path, Object ctx, String name) {
            switch (Code.get(rc)) { 
            case CONNECTIONLOSS:
                //处理连接丢失的情况有点麻烦，有可能会创建重复任务，为了解决这个问题，我们需要添加一些提示信息来标记这个znode节点的创建者，
            	//比如，在任务名称中加入服务器id等信息，通过这个方法，我们就可以通过获取所有任务列表来确认任务是否添加成功。
                submitTask(((TaskObject) ctx).getTask(), (TaskObject) ctx);
                
                break;
            case OK:
                System.out.println("My created task name: " + name);
                ((TaskObject) ctx).setTaskName(name);
                //为这个任务的znode节点设置一个监视点
                watchStatus(name.replace("/tasks/", "/status/"), ctx);
                
                break;
            default:
            	System.out.println("Something went wrong" + KeeperException.create(Code.get(rc), path));
            }
        }
    };
    
    protected ConcurrentHashMap<String, Object> ctxMap = new ConcurrentHashMap<String, Object>();
    void watchStatus(String path, Object ctx){
        ctxMap.put(path, ctx);
        zk.exists(path, 
                statusWatcher, 
                existsCallback, 
                //客户端传递上下文对象，当收到状态节点的通知时，就可以修改这个表示任务的对象
                ctx);
    }
    
    Watcher statusWatcher = new Watcher(){
        public void process(WatchedEvent e){
            if(e.getType() == EventType.NodeCreated) {
                assert e.getPath().contains("/status/task-");
                assert ctxMap.containsKey( e.getPath() );
                
                zk.getData(e.getPath(), 
                        false, 
                        getDataCallback, 
                        ctxMap.get(e.getPath()));
            }
        }
    };
    
    StatCallback existsCallback = new StatCallback(){
        public void processResult(int rc, String path, Object ctx, Stat stat){
            switch (Code.get(rc)) {
            case CONNECTIONLOSS:
                watchStatus(path, ctx);
                
                break;
            case OK:
                if(stat != null){
                	//状态节点已存在，因此客户端获取这个节点信息
                    zk.getData(path, false, getDataCallback, ctx);
                    System.out.println("Status node is there: " + path);
                } 
                
                break;
            case NONODE:
            	//如果状态节点不存在，这是常见情况，客户端不进行任何操作
                break;
            default:     
            	System.out.println("Something went wrong when checking if the status node exists: " + 
                        KeeperException.create(Code.get(rc), path));
                
                break;
            }
        }
    };
        
    DataCallback getDataCallback = new DataCallback(){
        public void processResult(int rc, String path, Object ctx, byte[] data, Stat stat) {
            switch (Code.get(rc)) {
            case CONNECTIONLOSS:
                /*
                 * Try again.
                 */
                zk.getData(path, false, getDataCallback, ctxMap.get(path));
                return;
            case OK:
                /*
                 *  Print result
                 */
                String taskResult = new String(data);
                System.out.println("Task " + path + ", " + taskResult);
                
                /*
                 *  Setting the status of the task
                 */
                assert(ctx != null);
                ((TaskObject) ctx).setStatus(taskResult.contains("done"));
                
                /*
                 *  Delete status znode
                 */
                //zk.delete("/tasks/" + path.replace("/status/", ""), -1, taskDeleteCallback, null);
                zk.delete(path, -1, taskDeleteCallback, null);
                ctxMap.remove(path);
                break;
            case NONODE:
            	System.out.println("Status node is gone!");
                return; 
            default:
            	System.out.println("Something went wrong here, " + KeeperException.create(Code.get(rc), path));               
            }
        }
    };
    
    VoidCallback taskDeleteCallback = new VoidCallback(){
        public void processResult(int rc, String path, Object ctx){
            switch (Code.get(rc)) {
            case CONNECTIONLOSS:
                zk.delete(path, -1, taskDeleteCallback, null);
                break;
            case OK:
                System.out.println("Successfully deleted " + path);
                break;
            default:
            	System.out.println("Something went wrong here, " + KeeperException.create(Code.get(rc), path));
            }
        }
    };
	
	public static void main(String args[]) throws Exception { 
        Client c = new Client("127.0.0.145:2181,127.0.0.145:2182,127.0.0.145:2183");
        c.startZK();
        
        while(!c.isConnected()){
            Thread.sleep(100);
        }   
        
        TaskObject task1 = new TaskObject();
        TaskObject task2 = new TaskObject();
        
        c.submitTask("Sample task", task1);
        c.submitTask("Another sample task", task2);
        
        task1.waitUntilDone();
        task2.waitUntilDone();
    }

}
