package com.ch3;

import java.io.IOException;

import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;

/**单独主模式
 * @author dell
 *
 */
public class Master implements Watcher {

	ZooKeeper zk;
	String hostPort;

	public Master(String hostPort) {
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

	public static void main(String[] args) throws InterruptedException, IOException {
		Master m = new Master("127.0.0.145:2181");
		m.startZK();
		Thread.sleep(20000);
		m.stopZK();
	}

}
