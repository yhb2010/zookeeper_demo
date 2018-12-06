package com.Zookeeper快速入门.ConfigurationService;

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.apache.zookeeper.KeeperException;

public class ConfigUpdater {

	public static final String PATH = "/config";
	private ActiveKeyValueStore store;
	private Random random = new Random();

	public ConfigUpdater(String hosts) throws IOException, InterruptedException {
		store = new ActiveKeyValueStore();
		store.connect(hosts);
	}

	public void run() throws InterruptedException, KeeperException {
		while (true) {
			String value = random.nextInt(100) + "";
			store.write(PATH, value);
			System.out.printf("Set %s to %s\n", PATH, value);
			TimeUnit.SECONDS.sleep(random.nextInt(10));
		}
	}

	/**
	 * 在ConfigUpdater的构造函数中，ActiveKeyValueStore对象连接到ZooKeeper服务。
	 * 然后run()不断的循环运行，使用一个随机数不断的随机更新/configznode上的值。
	 * @param args
	 * @throws Exception
	 */
	public static void main(String[] args) throws Exception {
		while (true) {
			try {
				ConfigUpdater configUpdater = new ConfigUpdater("127.0.0.145:2181,127.0.0.145:2182,127.0.0.145:2183");
				configUpdater.run();
			} catch (KeeperException.SessionExpiredException e) {
				// start a new session
				ConfigUpdater configUpdater = new ConfigUpdater("127.0.0.145:2181,127.0.0.145:2182,127.0.0.145:2183");
				configUpdater.run();
			} catch (KeeperException e) {
				// already retried, so exit
				e.printStackTrace();
				break;
			}
		}
	}

}
