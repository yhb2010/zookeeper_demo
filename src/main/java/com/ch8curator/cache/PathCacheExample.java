package com.ch8curator.cache;

import org.apache.curator.utils.CloseableUtils;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheListener;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.utils.ZKPaths;
import org.apache.zookeeper.KeeperException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Arrays;

/**
 * 子节点缓存器
 * 我们使用该类保存从节点列表和任务列表，该缓存器负责保存一份子节点列表的本地拷贝，并会在该列表发生变化时通知我们，
 * 注意，因为时间问题，有可能在特定时间点该缓存的数据集合与zookeeper中保存的信息并不一致，但这些变化最终会反映到
 * zookeeper中。
 * 为了处理每一个缓存器实例的变化情况，我们需要一个PathChildrenCacheListener接口的实现类，该接口中只有一个方法
 * childEvent，对于从节点信息的列表，我们只关心从节点离开的情况，因为我们需要重新分配已经分配给这些节点的任务，而
 * 列表中添加信息对于分配新任务更加重要。
 * @author dell
 *
 */
public class PathCacheExample {

	private static final String     PATH = "/example/cache";

	public static void main(String[] args) throws Exception
	{
	    CuratorFramework    client = null;
	    PathChildrenCache   cache = null;
	    try
	    {
	        client = CuratorFrameworkFactory.newClient("127.0.0.145:2181,127.0.0.145:2182,127.0.0.145:2183", new ExponentialBackoffRetry(1000, 3));
	        client.start();

	        // in this example we will cache data. Notice that this is optional.
	        cache = new PathChildrenCache(client, PATH, true);
	        cache.start();

	        processCommands(client, cache);
	    }
	    finally
	    {
	        CloseableUtils.closeQuietly(cache);
	        CloseableUtils.closeQuietly(client);
	    }
	}

	private static void addListener(PathChildrenCache cache)
	{
	    // a PathChildrenCacheListener is optional. Here, it's used just to log changes
	    PathChildrenCacheListener listener = new PathChildrenCacheListener()
	    {
	        @Override
	        public void childEvent(CuratorFramework client, PathChildrenCacheEvent event) throws Exception
	        {
	            switch ( event.getType() )
	            {
	                case CHILD_ADDED:
	                {
	                    System.out.println("Node added: " + ZKPaths.getNodeFromPath(event.getData().getPath()));
	                    break;
	                }

	                case CHILD_UPDATED:
	                {
	                    System.out.println("Node changed: " + ZKPaths.getNodeFromPath(event.getData().getPath()));
	                    break;
	                }

	                case CHILD_REMOVED:
	                {
	                    System.out.println("Node removed: " + ZKPaths.getNodeFromPath(event.getData().getPath()));
	                    break;
	                }
	            }
	        }
	    };
	    cache.getListenable().addListener(listener);
	}

	private static void processCommands(CuratorFramework client, PathChildrenCache cache) throws Exception
	{
	    // More scaffolding that does a simple command line processor

	    printHelp();

	    try
	    {
	        addListener(cache);

	        BufferedReader  in = new BufferedReader(new InputStreamReader(System.in));
	        boolean         done = false;
	        while ( !done )
	        {
	            System.out.print("> ");

	            String      line = in.readLine();
	            if ( line == null )
	            {
	                break;
	            }

	            String      command = line.trim();
	            String[]    parts = command.split("\\s");
	            if ( parts.length == 0 )
	            {
	                continue;
	            }
	            String      operation = parts[0];
	            String      args[] = Arrays.copyOfRange(parts, 1, parts.length);

	            if ( operation.equalsIgnoreCase("help") || operation.equalsIgnoreCase("?") )
	            {
	                printHelp();
	            }
	            else if ( operation.equalsIgnoreCase("q") || operation.equalsIgnoreCase("quit") )
	            {
	                done = true;
	            }
	            else if ( operation.equals("set") )
	            {
	                setValue(client, command, args);
	            }
	            else if ( operation.equals("remove") )
	            {
	                remove(client, command, args);
	            }
	            else if ( operation.equals("list") )
	            {
	                list(cache);
	            }

	            Thread.sleep(1000); // just to allow the console output to catch up
	        }
	    }
	    finally
	    {
	    	CloseableUtils.closeQuietly(cache);
	        CloseableUtils.closeQuietly(client);
	    }
	}

	private static void list(PathChildrenCache cache)
	{
	    if ( cache.getCurrentData().size() == 0 )
	    {
	        System.out.println("* empty *");
	    }
	    else
	    {
	        for ( ChildData data : cache.getCurrentData() )
	        {
	            System.out.println(data.getPath() + " = " + new String(data.getData()));
	        }
	    }
	}

	private static void remove(CuratorFramework client, String command, String[] args) throws Exception
	{
	    if ( args.length != 1 )
	    {
	        System.err.println("syntax error (expected remove <path>): " + command);
	        return;
	    }

	    String      name = args[0];
	    if ( name.contains("/") )
	    {
	        System.err.println("Invalid node name" + name);
	        return;
	    }
	    String      path = ZKPaths.makePath(PATH, name);

	    try
	    {
	        client.delete().forPath(path);
	    }
	    catch ( KeeperException.NoNodeException e )
	    {
	        // ignore
	    }
	}

	private static void setValue(CuratorFramework client, String command, String[] args) throws Exception
	{
	    if ( args.length != 2 )
	    {
	        System.err.println("syntax error (expected set <path> <value>): " + command);
	        return;
	    }

	    String      name = args[0];
	    if ( name.contains("/") )
	    {
	        System.err.println("Invalid node name" + name);
	        return;
	    }
	    String      path = ZKPaths.makePath(PATH, name);

	    byte[]      bytes = args[1].getBytes();
	    try
	    {
	        client.setData().forPath(path, bytes);
	    }
	    catch ( KeeperException.NoNodeException e )
	    {
	        client.create().creatingParentContainersIfNeeded().forPath(path, bytes);
	    }
	}

	private static void printHelp()
	{
	    System.out.println("An example of using PathChildrenCache. This example is driven by entering commands at the prompt:\n");
	    System.out.println("set <name> <value>: Adds or updates a node with the given name");
	    System.out.println("remove <name>: Deletes the node with the given name");
	    System.out.println("list: List the nodes/values in the cache");
	    System.out.println("quit: Quit the example");
	    System.out.println();
	}

}
