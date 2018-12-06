package com.ch4;

import java.util.Arrays;
import java.util.List;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.OpResult;
import org.apache.zookeeper.Transaction;
import com.Zookeeper快速入门.ConnectionWatcher;

public class Multiop extends ConnectionWatcher {

	public void multiDelete(String z) throws KeeperException, InterruptedException {
		//multi也有异步版本
		List<OpResult> results = zk.multi(Arrays.asList(deleteZnode(z + "/monkey"), deleteZnode(z)));
	}

	//创建op对象，该对象表示你想通过multiop方法执行的每个zookeeper操作，zookeeper提供了每个改变状态操作的op对象的实现：create、delete、setData
	//通过op对象中提供的一个静态方法调用进行操作
	//将op对象添加到java的iterable类型的对象中，如list
	//使用列表对象调用multi方法
	private Op deleteZnode(String z){
		z = "/" + z;
		return Op.delete(z, -1);
	}

	public void multiDelete2(String z) throws KeeperException, InterruptedException {
		z = "/" + z;
		Transaction t = zk.transaction();
		t.delete(z + "/monkey", -1);
		t.delete(z, -1);
		List<OpResult> results = t.commit();

	}

	public static void main(String[] args) throws Exception {
		Multiop deleteGroup = new Multiop();
		deleteGroup.connect("127.0.0.145:2181");
		deleteGroup.multiDelete2("zoo");
		deleteGroup.close();
	}

}
