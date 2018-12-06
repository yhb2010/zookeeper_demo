package com.ch3;

import java.io.IOException;
import java.util.Random;

import org.apache.zookeeper.AsyncCallback.DataCallback;
import org.apache.zookeeper.AsyncCallback.StringCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;

/**异步主从模式
 * @author dell
 *
 */
public class AsyMasterSlave implements Watcher {

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

	public AsyMasterSlave(String hostPort) {
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

	public static void main(String[] args) throws InterruptedException, IOException, KeeperException {
		AsyMasterSlave m = new AsyMasterSlave("127.0.0.145:2181,127.0.0.145:2182,127.0.0.145:2183");
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
