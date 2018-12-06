package com.Zookeeper快速入门.ConfigurationService;

import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.Stat;

import com.Zookeeper快速入门.ConnectionWatcher;

/**
 * 一个基本的ZooKeeper实现的服务就是“配置服务”，集群中的服务器可以通过ZooKeeper共享一个通用的配置数据。
 * 从表面上，ZooKeeper可以理解为一个配置数据的高可用存储服务，为应用提供检索和更新配置数据服务。
 * 我们可以使用ZooKeeper的观察模式实现一个活动的配置服务，当配置数据发生变化时，可以通知与配置相关客户端。
 * 接下来，我们来实现一个这样的活动配置服务。首先，我们设计用znode来存储key-value对，
 * 我们在znode中存储一个String类型的数据作为value，用znode的path来表示key。然后，我们实现一个client，
 * 这个client可以在任何时候对数据进行跟新操作。那么这个设计的ZooKeeper数据模型应该是：master来更新数据，
 * 其他的worker也随之将数据更新
 * @author dell
 *
 */
public class ActiveKeyValueStore extends ConnectionWatcher {
	private static final Charset CHARSET = Charset.forName("UTF-8");

	public void write(String path, String value) throws InterruptedException, KeeperException {
		int retries = 0;
		while (true) {
			try {
				Stat stat = zk.exists(path, false);
				if (stat == null) {
					zk.create(path, value.getBytes(CHARSET),
							Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
				} else {
					zk.setData(path, value.getBytes(CHARSET), stat.getVersion());
				}
				return;
			} catch (KeeperException.SessionExpiredException e) {
				throw e;
			} catch (KeeperException e) {
				//我们并没有在捕获KeeperException.SessionExpiredException时继续重新尝试操作，这是因为当session过期后，ZooKeeper会变为CLOSED状态，就不能再重新连接了。我们只是简单的抛出一个异常，通知调用者去创建一个新的ZooKeeper实例，所以write()方法可以不断的尝试执行。
				//一个简单的方式来创建一个ZooKeeper实例就是重新new一个ConfigUpdater实例。
				if (retries++ == 5) {//尝试5次
					throw e;
				}
				// sleep then retry
				//这只是一个重复尝试的策略。还有很多的策略，比如指数补偿策略，每次尝试之间的间隔时间会被乘以一个常数，间隔时间会逐渐变长，直到与集群建立连接为止间隔时间才会恢复到一个正常值，来预备一下次连接异常使用。
				//译者：为什么要使用指数补偿策略呢？这是为了避免反复的尝试连接而消耗资源。在一次较短的时间后第二次尝试连接不成功后，延长第三次尝试的等待时间，这期间服务恢复的几率可能会更大。第四次尝试的机会就变小了，从而达到减少尝试的次数。
				TimeUnit.SECONDS.sleep(5);//等待5秒后尝试
			}
		}
	}

	/**
	 * ZooKeeper的getData()方法的参数包含：path，一个Watcher对象和一个Stat对象。
	 * Stat对象中含有从getData()返回的值，并且负责接收回调信息。
	 * 这种方式下，调用者不仅可以获得数据，还能够获得znode的metadata。
	 * @param path
	 * @param watcher
	 * @return
	 * @throws InterruptedException
	 * @throws KeeperException
	 */
	public String read(String path, Watcher watcher) throws InterruptedException, KeeperException {
		byte[] data = zk.getData(path, watcher, null/* stat */);
		return new String(data, CHARSET);
	}

}