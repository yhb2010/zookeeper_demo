package com.ch3;

import java.io.IOException;
import java.util.Random;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.ConnectionLossException;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;

/**主从模式
 * @author dell
 *
 */
public class MasterSlave implements Watcher {

	ZooKeeper zk;
	String hostPort;
	Random random = new Random(7);
	String serverID = Integer.toString(random.nextInt());
	boolean isLeader = false;

	//如果是主节点，则返回true
	public boolean checkMaster() throws KeeperException, InterruptedException{
		while(true){
			try{
				Stat stat = new Stat();
				//我们想要获取数据的znode节点路径
				//表示我们是否想要监听后续的数据变更，如果为true，我们就可以通过创建zookeeper句柄时所设置的Watch对象得到事件
				//Stat结构：getData方法会填充znode节点的元数据信息
				//方法返回成功，就会得到znode节点数据的字节数组
				byte data[] = zk.getData("/master", false, stat);//通过获取/master节点的数据来检查活动主节点
				//这行展示了为什么我们需要使用在创建/master节点时保存的数据：如果/master存在，我们使用/master中的数据来确定谁是群首。
				//如果一个进程捕获到ConnectionLossException，这个进程可能就是主节点，因create操作实际上已经处理完，但响应消息却丢失了。
				isLeader = new String(data).equals(serverID);
				return true;
			}catch(NoNodeException e){
				//没有主节点，尝试重新创建
				return false;
			}catch (ConnectionLossException e) {
			}
		}
	}

	//InterruptedException异常源于客户端线程调用了Thread.interrupt，通常是因为应用程序部分关闭，但还在被其他相关的方法使用。
	public void runForMaster() throws KeeperException, InterruptedException{
		while(true){
			try{
				//我们希望在主节点死掉后，master节点会消失，我们可以使用zookeeper的临时性znode节点来达到我们的目的
				//如果这个znode节点存在，create就会失败，同时我们希望在/master节点的数据字段保存对应这个服务器的唯一id
				//数据字段只能存储字节数组的数据
				//使用ACL策略
				//创建的节点类型为EPHEMERAL，临时节点
				zk.create("/master", serverID.getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);//如果成功执行将成为主节点
				isLeader = true;
				break;
			}catch(NodeExistsException e){
				isLeader = false;
				break;
			}catch (ConnectionLossException e) {
				//KeeperException异常的子类，发生在客户端与zookeeper服务端失去连接时，一般常常由于网络原因导致，
				//当发生这个异常，客户端并不知道是在zookeeper服务器处理钱丢失了请求消息，还是在处理后客户端未收到响应消息，
				//如我们之前所述：zookeeper客户端库将会为后续请求重新建立连接。
				//处理ConnectionLossException异常的catch块的代码为空，因为我们并不想中止函数，这样就可以使处理过程继续向下执行
			}
			//检查活动主节点是否存在，如果不存在就重试
			if(checkMaster()){
				break;
			}
		}
	}

	public MasterSlave(String hostPort) {
		super();
		this.hostPort = hostPort;
	}

	void startZK() throws IOException{
		//我们连接到zookeeper后，后台就会有一个线程来维护这个zookeeper会话。
		//该线程为守护线程，也就是说线程即使处于活跃状态，程序也可以退出。
		//因此我们在程序退出前休眠一段时间，以便我们可以看到事件的发生。
		zk = new ZooKeeper(hostPort, 15000, this);
		//zookeeper客户端的实现和环境
		//Client environment:zookeeper.version=3.4.9-1757313, built on 08/23/2016 06:50 GMT
		//当客户端初始化一个到zookeeper服务器的连接时，无论是最初的连接还是随后的重连接，都会产生这些日志消息
		//Initiating client connection, connectString=127.0.0.145:2181 sessionTimeout=15000 watcher=com.ch3.Master@2077d4de
		//展示了连接建立之后，该连接的信息，其中包括客户端所连接的主机和端口信息，以及这个会话与服务器协商的超时时间。
		//如果服务器发现请求的会话超时时间太短或太长，服务器会调整会话超时时间
		//Session establishment complete on server 127.0.0.145/127.0.0.145:2181, sessionid = 0x1593fd1b4a90000, negotiated timeout = 15000
		//这是process函数输出的WatchedEvent对象
		//WatchedEvent state:SyncConnected type:None path:null

		//启动zookeeper服务，然后运行程序，再关闭zookeeper服务，则程序报连接失败，再启动zookeeper服务，则程序可以自动连接上，不用人工处理
	}

	//可以立刻销毁会话，不用等待15秒超时
	void stopZK() throws InterruptedException{
		zk.close();
	}

	@Override
	public void process(WatchedEvent e) {
		System.out.println(e);
	}

	public static void main(String[] args) throws InterruptedException, IOException, KeeperException {
		MasterSlave m = new MasterSlave("127.0.0.145:2181,127.0.0.145:2182,127.0.0.145:2183");
		m.startZK();
		//调用我们之前实现的runForMaster，当前进程成为主节点或另一个进程成为主节点后返回
		m.runForMaster();

		if(m.isLeader){
			//当我们开发主节点的应用逻辑时，我们在此处开始执行这些逻辑，现在仅仅输出成为主节点的信息，然后等待60秒后退出
			System.out.println("I am the leader");
			Thread.sleep(20000);
		}else{
			System.out.println("Someone else is the leader");
		}

		m.stopZK();
	}

}
