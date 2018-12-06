package com.Zookeeper快速入门.simpleDemo;

import java.util.List;

import org.apache.zookeeper.KeeperException;

import com.Zookeeper快速入门.ConnectionWatcher;

/**删除一个分组
 * @author dell
 *
 */
public class DeleteGroup extends ConnectionWatcher {

	/**ZooKeeper的API提供一个delete()方法来删除一个znode。我们通过输入znode的path和版本号（version number）来删除想要删除的znode。
	 * 我们除了使用path来定位我们要删除的znode，还需要一个参数是版本号。只有当我们指定要删除的本版号，与znode当前的版本号一致时，ZooKeeper才允许我们将znode删除掉。
	 * 这是一种optimistic locking机制，用来处理znode的读写冲突。我们也可以忽略版本号一致检查，做法就是版本号赋值为-1。
	 *
	 * 删除一个znode之前，我们需要先删除它的子节点
	 * @param groupName
	 * @throws KeeperException
	 * @throws InterruptedException
	 */
	public void delete(String groupName) throws KeeperException, InterruptedException {
		String path = "/" + groupName;
		try {
			List<String> children = zk.getChildren(path, false);
			children.stream().forEach(child -> {
				try {
					zk.delete(path + "/" + child, -1);
				}catch(KeeperException | InterruptedException e){
					e.printStackTrace();
				}
			});
			zk.delete(path, -1);
		} catch (KeeperException.NoNodeException e) {
			System.out.printf("Group %s does not exist\n", groupName);
			System.exit(1);
		}
	}

	public static void main(String[] args) throws Exception {
		DeleteGroup deleteGroup = new DeleteGroup();
		deleteGroup.connect("127.0.0.145:2181");
		deleteGroup.delete("zoo");
		deleteGroup.close();
	}

}
