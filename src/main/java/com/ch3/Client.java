package com.ch3;

import java.io.IOException;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.KeeperException.ConnectionLossException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.ZooDefs.Ids;

//客户端给从节点下任务，任务队列化
public class Client implements Watcher {

	ZooKeeper zk;
	String hostPort;
	private volatile boolean connected = false;
    private volatile boolean expired = false;

	public Client(String hostPort) {
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

	String queueCommand(String command) throws Exception {
		while(true){
			String name = null;
			try{
				//在/tasks节点下创建znode节点来标识一个任务，节点名称前缀为task-。
				//因为我们使用的是PERSISTENT_SEQUENTIAL模式的节点，task-后面会跟随一个单调递增的数字，这样就可以保证为每个任务创建的znode节点
				//的名称是唯一的，同时zookeeper会确定任务的顺序。
				///tasks节点创建的znode节点不是临时节点，因此即使client程序结束了，这个节点依然会存在。
				name = zk.create("/task/task-", command.getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
				//我们无法确定PERSISTENT_SEQUENTIAL的序列号，create方法会返回新创建的节点的名称
				return name;
			}catch(NodeExistsException e){
				throw new Exception(name + " already appears to be running");
			}catch(ConnectionLossException e){
				//如果在执行create时遇到连接丢失，我么你需要重试create，多次执行创建操作，也许会为一个任务建立多个znode节点，
				//对于大多数至少执行一次策略的应用程序，也没什么问题，对于某些最多执行一次策略的应用，我们就需要多一些额外工作，
				//我们需要为每一个任务指定一个唯一的id(会话id)，并将其编码到znode节点名中，在遇到连接丢失的异常时，我们只有在/tasks下
				//不存在以这个会话id命名的节点时才会重试命令
			}
		}
	}

	public static void main(String[] args) throws Exception {
		Client c = new Client("127.0.0.145:2181,127.0.0.145:2182,127.0.0.145:2183");
		c.startZK();

		String name = c.queueCommand(args[0]);
		System.out.println("create " + name);
	}

}
