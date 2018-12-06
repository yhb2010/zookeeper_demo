package com.Zookeeper快速入门.simpleDemo;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;

/**创建一个/zoo的组节点
 * @author Administrator
 *
 */
public class CreateGroup implements Watcher {

	private static final int SESSION_TIMEOUT = 5000;
    private ZooKeeper zk;
    private CountDownLatch connectedSignal = new CountDownLatch(1);

    public void connect(String hosts) throws IOException, InterruptedException {
    	//当main()执行时，首先创建了一个CreateGroup的对象，然后调用connect()方法，通过zookeeper的API与zookeeper服务器连接。
    	//创建连接我们需要3个参数：一是服务器端主机名称以及端口号，二是客户端连接服务器session的超时时间，
    	//三是Watcher接口的一个实例。Watcher实例负责接收Zookeeper数据变化时产生的事件回调。
        zk = new ZooKeeper(hosts, SESSION_TIMEOUT, this);
        connectedSignal.await();
    }

    /* 在连接函数中创建了zookeeper的实例，然后建立与服务器的连接。建立连接函数会立即返回，所以我们需要等待连接建立成功后再进行其他的操作。
     * 我们使用CountDownLatch来阻塞当前线程，直到zookeeper准备就绪。
     * 这时，我们就看到Watcher的作用了。我们实现了Watcher接口的一个方法：
     */
    @Override
    public void process(WatchedEvent event) {
    	//当客户端连接上了zookeeper服务器，Watcher将由process()函数接收一个连接成功的事件。我们接下来调用CountDownLatch，释放之前的阻塞。
        if (event.getState() == KeeperState.SyncConnected) {
            connectedSignal.countDown();
        }
    }

    /**连接成功后，我们调用create()方法。我们在这个方法中调用zookeeper实例的create()方法来创建一个znode。
     * 参数包括：一是znode的path；二是znode的内容（一个二进制数组），
     * 三是一个access control list(ACL，访问控制列表，这里使用完全开放模式)，最后是znode的性质。
     * @param groupName
     * @throws KeeperException
     * @throws InterruptedException
     */
    public void create(String groupName) throws KeeperException,
            InterruptedException {
        String path = "/" + groupName;
        //znode的性质分为ephemeral和persistent两种。ephemeral性质的znode在创建他的客户端的会话结束，或者客户端以其他原因断开与服务器的连接时，会被自动删除。
        //而persistent性质的znode就不会被自动删除，除非客户端主动删除，而且不一定是创建它的客户端可以删除它，其他客户端也可以删除它。这里我们创建一个persistent的znode。
        //create()将返回znode的path。
        String createdPath = zk.create(path, null/*data*/, Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        System.out.println("Created " + createdPath);
    }

    public void close() throws InterruptedException {
        zk.close();
    }

    public static void main(String[] args) throws Exception {
        CreateGroup createGroup = new CreateGroup();
        createGroup.connect("127.0.0.145:2181");
        createGroup.create("zoo");
        createGroup.close();
    }

}
