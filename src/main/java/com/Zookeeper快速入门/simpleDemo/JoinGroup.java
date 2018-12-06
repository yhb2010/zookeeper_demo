package com.Zookeeper快速入门.simpleDemo;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs.Ids;

import com.Zookeeper快速入门.ConnectionWatcher;

/**接下来我们实现如何在一个组中注册成员。我们将使用ephemeral znode来创建这些成员节点。
 * 那么当客户端程序退出时，这些成员将被删除。
 * @author dell
 *
 */
public class JoinGroup extends ConnectionWatcher {

	public void join(String groupName, String memberName) throws KeeperException, InterruptedException {
		String path = "/" + groupName + "/" + memberName;
		String createdPath = zk.create(path, null/*data*/, Ids.OPEN_ACL_UNSAFE,
		        CreateMode.EPHEMERAL);
		System.out.println("Created " + createdPath);
	}

	public static void main(String[] args) throws Exception {
		JoinGroup joinGroup = new JoinGroup();
		joinGroup.connect("127.0.0.145:2181");
		joinGroup.join("zoo", "monkey");
		//stay alive until process is killed or thread is interrupted
		Thread.sleep(Long.MAX_VALUE);
	}

}
