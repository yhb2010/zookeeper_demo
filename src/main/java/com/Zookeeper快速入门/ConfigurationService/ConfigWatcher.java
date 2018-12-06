package com.Zookeeper快速入门.ConfigurationService;

import java.io.IOException;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.EventType;

/**
 * 做为服务的consumer，ConfigWatcher以观察者身份，创建一个ActiveKeyValueStore对象，
 * 并且在启动以后调用read()函数（在dispalayConfig()函数中）获得相关数据。
 * @author dell
 *
 */
public class ConfigWatcher implements Watcher {

	private ActiveKeyValueStore store;

	public ConfigWatcher(String hosts) throws IOException, InterruptedException {
		store = new ActiveKeyValueStore();
		store.connect(hosts);
	}

	public void displayConfig() throws InterruptedException, KeeperException {
		String value = store.read(ConfigUpdater.PATH, this);
		System.out.printf("Read %s as %s\n", ConfigUpdater.PATH, value);
	}

	@Override
	public void process(WatchedEvent event) {
		//当ConfigUpdater更新znode时，ZooKeeper将触发一个EventType.NodeDataChanged的事件给观察者。
		//ConfigWatcher将在他的process()函数中获得这个时间，并将显示读取到的最新的版本的配置数据。
		if (event.getType() == EventType.NodeDataChanged) {
			try {
				displayConfig();
			} catch (InterruptedException e) {
				System.err.println("Interrupted. Exiting.");
				Thread.currentThread().interrupt();
			} catch (KeeperException e) {
				System.err.printf("KeeperException: %s. Exiting.\n", e);
			}
		}
	}

	public static void main(String[] args) throws Exception {
		ConfigWatcher configWatcher = new ConfigWatcher("127.0.0.145:2181,127.0.0.145:2182,127.0.0.145:2183");
		configWatcher.displayConfig();
		// stay alive until process is killed or thread is interrupted
		Thread.sleep(Long.MAX_VALUE);
	}

}
