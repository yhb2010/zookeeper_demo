package com.ch3;

import java.io.IOException;
import java.util.Date;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.data.Stat;

//管理客户端
//通过getChildren()和getDate()方法来获取主从系统的运行状态
public class AdminClient implements Watcher {

	ZooKeeper zk;
	String hostPort;
	private volatile boolean connected = false;
    private volatile boolean expired = false;

	public AdminClient(String hostPort) {
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

	void listState() throws KeeperException, InterruptedException{
		try{
			Stat stat = new Stat();
			byte masterData[] = zk.getData("/master", false, stat);
			//获取当前主节点成为主节点的时间
			Date startDate = new Date(stat.getCtime());
			System.out.println("Master: " + new String(masterData) + " since " + startDate);
		}catch(NoNodeException e){
			System.out.println("No Master");
		}

		System.out.println("Workers: ");
		for(String w : zk.getChildren("/workers", false)){
			//临时节点含有两个信息：指示当前从节点正在运行；其数据表示从节点的状态
			byte data[] = zk.getData("/workers/" + w, false, null);
			String state = new String(data);
			System.out.println("\t" + w + ": " + state);
		}

		System.out.println("Tasks: ");
		for(String t : zk.getChildren("/assign",  false)){
			System.out.println("\t" + t);
		}
	}

	public static void main(String[] args) throws IOException, KeeperException, InterruptedException {
		AdminClient c = new AdminClient("127.0.0.145:2181,127.0.0.145:2182,127.0.0.145:2183");
		c.startZK();

		c.listState();
	}

}
