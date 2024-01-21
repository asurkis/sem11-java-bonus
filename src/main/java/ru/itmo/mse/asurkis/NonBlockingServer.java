package ru.itmo.mse.asurkis;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class NonBlockingServer {
    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(args[0]);
        int nClients = Integer.parseInt(args[1]);
        new NonBlockingServer(port).start(nClients);
    }

    private final ExecutorService workerPool;
    private final int port;

    private final Selector readSelector = Selector.open();
    private final Selector writeSelector = Selector.open();

    public NonBlockingServer(int port) throws IOException {
        this.port = port;
        Runtime runtime = Runtime.getRuntime();
        int nProcessors = runtime.availableProcessors();
        workerPool = Executors.newFixedThreadPool(nProcessors);
    }

    private final AtomicInteger remainingClients = new AtomicInteger();

    public void start(int nClients) throws IOException {
        System.out.println("processing_ns,response_ns");
        remainingClients.set(nClients);

        Thread readThread = new Thread(this::runReadThread);
        readThread.start();

        Thread writeThread = new Thread(this::runWriteThread);
        writeThread.start();

        try (
                ServerSocketChannel serverSocketChannel = ServerSocketChannel.open()
        ) {
            serverSocketChannel.bind(new InetSocketAddress(port));
            for (int i = 0; i < nClients; i++) {
                SocketChannel channel = serverSocketChannel.accept();
                Client client = new Client(channel);
                client.start();
            }
        }
    }

    private void runReadThread() {
        try {
            while (remainingClients.get() > 0) {
                readSelector.select();
                Set<SelectionKey> selectedKeys = readSelector.selectedKeys();
                Iterator<SelectionKey> iter = selectedKeys.iterator();
                while (iter.hasNext()) {
                    SelectionKey key = iter.next();
                    Client client = (Client) key.attachment();
                    client.handleRead();
                    iter.remove();
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void runWriteThread() {
        try {
            while (remainingClients.get() > 0) {
                writeSelector.select();
                Set<SelectionKey> selectedKeys = writeSelector.selectedKeys();
                Iterator<SelectionKey> iter = selectedKeys.iterator();
                while (iter.hasNext()) {
                    SelectionKey key = iter.next();
                    Client client = (Client) key.attachment();
                    client.handleWrite();
                    iter.remove();
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private class Client implements Closeable {
        private final SocketChannel channel;
        private final SelectionKey readKey;
        private final SelectionKey writeKey;
        private final Metrics metrics = new Metrics();

        private ByteBuffer buffer = ByteBuffer.allocate(ServerUtil.START_CAPACITY_BYTES);
        private Runnable nextOp = null;

        private Client(SocketChannel channel) throws IOException {
            this.channel = channel;
            channel.configureBlocking(false);
            // Пока не подготовили буфер, не интересуемся событиями
            readKey = channel.register(readSelector, 0, this);
            writeKey = channel.register(writeSelector, 0, this);
        }

        @Override
        public void close() throws IOException {
            readKey.cancel();
            writeKey.cancel();
            channel.close();
            if (remainingClients.decrementAndGet() == 0) {
                readSelector.wakeup();
                writeSelector.wakeup();
                workerPool.shutdown();
            }
        }

        private void start() {
            buffer.clear();
            buffer.limit(4);
            nextOp = this::onReadSize;
            readKey.interestOps(SelectionKey.OP_READ);
            readSelector.wakeup();
        }

        private void onReadSize() {
            buffer.flip();
            int size = buffer.getInt();
            buffer = ServerUtil.ensureLimit(buffer, size);
            nextOp = this::onReadArray;
        }

        private void onReadArray() {
            readKey.interestOps(0);
            nextOp = null;
            metrics.requestReceived = System.nanoTime();
            workerPool.submit(this::processRequest);
        }

        private void onWrite() {
            writeKey.interestOps(0);
            metrics.responseSent = System.nanoTime();
            ServerUtil.printMetrics(metrics);
            start();
        }

        private void processRequest() {
            try {
                metrics.processingStart = System.nanoTime();
                buffer = ServerUtil.processPayload(buffer);
                metrics.processingFinish = System.nanoTime();
                nextOp = this::onWrite;

                writeKey.interestOps(SelectionKey.OP_WRITE);
                writeSelector.wakeup();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        private void handleRead() throws IOException {
            if (channel.read(buffer) == -1) {
                close();
            } else if (buffer.remaining() == 0) {
                nextOp.run();
            }
        }

        private void handleWrite() throws IOException {
            channel.write(buffer);
            if (buffer.remaining() == 0)
                nextOp.run();
        }
    }
}
