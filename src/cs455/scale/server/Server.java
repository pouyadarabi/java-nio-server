package cs455.scale.server;

import cs455.scale.server.task.ReadTask;
import cs455.scale.server.task.WriteTask;
import cs455.scale.util.LoggingUtil;
import cs455.scale.util.ScaleUtil;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;

/**
 * Author: Thilina
 * Date: 2/27/14
 * Main Class of the Server.
 */
public class Server {

    private final int port;
    private final InetAddress serverAddress;
    private final ThreadPool threadPool;
    private Selector selector;

    public Server(int port, int threadPoolSize) {
        this.port = port;
        this.threadPool = new ThreadPool(threadPoolSize);
        this.serverAddress = ScaleUtil.getHostInetAddress();
    }

    public boolean initialize() throws IOException {
        this.selector = Selector.open();
        return threadPool.initialize();
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            LoggingUtil.logError(Server.class, "Missing required arguments. Expecting " +
                    "\'java cs455.scaling.server.Server port-num thread-pool-size\'");
            System.exit(-1);
        }
        int port = Integer.parseInt(args[0]);
        int threadPoolSize = Integer.parseInt(args[1]);

        // Server instance
        Server server = new Server(port, threadPoolSize);
        boolean initialized = false;
        try {
            initialized = server.initialize();
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (initialized) {
            try {
                server.start();
            } catch (IOException e) {
                LoggingUtil.logError(Server.class, e.getMessage(), e);
            }
        } else {
            LoggingUtil.logError(Server.class, "Initialization Error. Server startup is terminated.");
            System.exit(-1);
        }
    }

    private void start() throws IOException {
        // create the server socket channel.
        ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
        // configure it to be non-blocking
        serverSocketChannel.configureBlocking(false);
        // get the server socket from the channel
        ServerSocket serverSocket = serverSocketChannel.socket();
        // bind it to the server address and the provided port
        InetSocketAddress inetSocketAddress = new InetSocketAddress(serverAddress, port);
        serverSocket.bind(inetSocketAddress);

        LoggingUtil.logInfo(this.getClass(), "Server Started on " + serverAddress.getHostName() + ":" + port);

        // register for interest on accepting connections
        serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);

        JobQueue jobQueue = JobQueue.getInstance();

        while (true) {
            // now check for new keys
            int numOfKeys = selector.select();
            // no new selected keys. start the loop again.
            if (numOfKeys == 0) {
                continue;
            }

            // get the keys
            Set keys = selector.selectedKeys();
            Iterator it = keys.iterator();

            while (it.hasNext()) {
                SelectionKey key = (SelectionKey) it.next();
                it.remove();
                if (!key.isValid()) {
                    continue;
                }
                if (key.isAcceptable()) {
                    // Handling accept connections in the selector thread itself.
                    try {
                        LoggingUtil.logInfo(this.getClass(), "New Connection Accept Request!");
                        SocketChannel socketChannel = ((ServerSocketChannel) key.channel()).accept();
                        if (socketChannel != null) {
                            // configure it non-blocking
                            socketChannel.configureBlocking(false);
                            SocketChannelDataHolder socketChannelDataHolder = new SocketChannelDataHolder();
                            // register read/write interests
                            socketChannel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE,
                                    socketChannelDataHolder);
                            LoggingUtil.logInfo(this.getClass(), "Connection Accept Completed!");
                        }
                    } catch (IOException e) {
                        LoggingUtil.logError(this.getClass(), "Error accepting connection.", e);
                    }
                } else if (key.isReadable()) {
                    // handle read tasks to job queue
                    ReadTask readTask = new ReadTask(key, this);
                    jobQueue.addJob(readTask);
                } else if (key.isWritable()) {
                    // handle write tasks to job queue
                    WriteTask writeTask = new WriteTask(key, this);
                    jobQueue.addJob(writeTask);
                }
            }
        }
    }
}
