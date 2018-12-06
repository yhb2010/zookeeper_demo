package com.Zookeeper快速入门.simpleDemo;

import java.util.List;

import org.apache.zookeeper.KeeperException;

import com.Zookeeper快速入门.ConnectionWatcher;

/**成员列表
 * 下面我们实现一个程序来列出一个组中的所有成员。
 * @author dell
 *
 */
public class ListGroup extends ConnectionWatcher {

	public void list(String groupName) throws KeeperException, InterruptedException {
		String path = "/" + groupName;
		//我们在list()方法中通过调用getChildren()方法来获得某一个path下的子节点，然后打印出来。
		//我们这里会试着捕获KeeperException.NoNodeException，当znode不存在时会抛出这个异常。
		try {
			List<String> children = zk.getChildren(path, false);
			if (children.isEmpty()) {
				System.out.printf("No members in group %s\n", groupName);
				System.exit(1);
			}
			children.stream().forEach(System.out::println);
		} catch (KeeperException.NoNodeException e) {
			System.out.printf("Group %s does not exist\n", groupName);
			System.exit(1);
		}
	}

	public static void main(String[] args) throws Exception {
		ListGroup listGroup = new ListGroup();
		listGroup.connect("127.0.0.145:2181");
		listGroup.list("zoo");
		listGroup.close();
	}

}
