package com.ch3;

import java.io.IOException;
import java.util.Random;

import org.apache.zookeeper.AsyncCallback.StatCallback;
import org.apache.zookeeper.AsyncCallback.StringCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;

/**注册从节点
 * @author dell
 *
 */
public class Worker implements Watcher {

	ZooKeeper zk;
	String hostPort;
	private volatile boolean connected = false;
    private volatile boolean expired = false;
    private String serverId = Integer.toHexString((new Random()).nextInt());
    String name;

    public Worker(String hostPort) {
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

	public void register(){
        name = "worker-" + serverId;
        //我们将从节点的状态信息存入代表从节点的znode节点中
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
            	//因为这个进程是唯一创建表示该进程的临时性znode节点的进程，如果创建节点时连接丢失，进程会简单地重试创建过程
                register();
                break;
            case OK:
            	System.out.println("Registered successfully: " + serverId);
                break;
            case NODEEXISTS:
            	System.out.println("Already registered: " + serverId);
                break;
            default:
            	System.out.println("Something went wrong");
            }
        }
    };

    boolean isConnected() {
        return connected;
    }

    boolean isExpired() {
        return expired;
    }

    StatCallback statusUpdateCallback = new StatCallback() {
        public void processResult(int rc, String path, Object ctx, Stat stat) {
            switch(Code.get(rc)) {
            case CONNECTIONLOSS:
            	//如果收到连接丢失的事件，我们需要用想要更新的状态再次调用updateStatus方法，因为在updateStatus方法里进行了
            	//竞态条件的检查，所以我们在这里就不需要再次检查
                updateStatus((String)ctx);
                return;
            }
        }
    };

    String status;
    synchronized private void updateStatus(String status) {
    	//从节点开始执行任务task-1，因此设置其状态为working on task-1
    	//客户端库尝试通过setData来实现，但此时遇到了网络问题
    	//客户端库确定与zookeeper的连接已丢失，同时在statusUpdateCallback调用前，从节点完成了任务task-1并处于空闲状态
    	//从节点调用客户端库，使用setData方法置状态为Idle
    	//之后客户端处理连接丢失的事件，如果updateStatus方法未检查当前状态，setData调用还是会设置状态为working on task-1
    	//当与zookeeper连接重新建立时，客户端库会按顺序如实的调用这两个setData操作，这就意味着最终状态为working on task-1
    	//在updateStatus方法中，在补发setData之前，先检查当前状态，这样就可以避免以上问题
        if (status == this.status) {
        	//-1表示执行无条件更新
            zk.setData("/workers/" + name, status.getBytes(), -1,
                statusUpdateCallback, status);
        }
    }

    public void setStatus(String status) {
    	//我们将状态信息保存在本地变量中，万一更新失败，可以重试
        this.status = status;
        updateStatus(status);
    }

    public static void main(String args[]) throws Exception {
        Worker w = new Worker("127.0.0.145:2181,127.0.0.145:2182,127.0.0.145:2183");
        w.startZK();

        while(!w.isConnected()){
            Thread.sleep(100);
        }

        w.register();

        while(!w.isExpired()){
            Thread.sleep(1000);
        }
    }

}
