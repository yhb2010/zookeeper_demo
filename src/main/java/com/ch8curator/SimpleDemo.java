package com.ch8curator;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryNTimes;
import org.apache.zookeeper.CreateMode;

public class SimpleDemo {

	public static void main(String[] args) throws Exception {
		String path = "/test_path";
		CuratorFramework client = CuratorFrameworkFactory.builder()
				.connectString("127.0.0.145:2181,127.0.0.145:2182,127.0.0.145:2183")
				.namespace("test1")
				.retryPolicy(new RetryNTimes(Integer.MAX_VALUE, 1000))
				.connectionTimeoutMs(5000).build();

		client.start();
		// create a node
		client.create().forPath("/head", new byte[0]);

		// delete a node in background
		//client.delete().inBackground().forPath("/head");

		// create a EPHEMERAL_SEQUENTIAL
		client.create().withMode(CreateMode.EPHEMERAL_SEQUENTIAL)
				.forPath("/head/child", new byte[0]);

		// get the data
		System.out.println(client.getData().watched().inBackground().forPath("/test"));

		// check the path exits
		System.out.println(client.checkExists().forPath(path));
	}

}
