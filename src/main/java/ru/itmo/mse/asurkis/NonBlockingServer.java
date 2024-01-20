package ru.itmo.mse.asurkis;

import com.google.protobuf.CodedOutputStream;
import ru.itmo.mse.asurkis.Messages.ArrayMessage;

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

public class NonBlockingServer {
    public static void main(String[] args) throws IOException {
        new NonBlockingServer(4444).start();
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

    private void start() throws IOException {
        Thread readThread = new Thread(this::runReadThread);
        readThread.setDaemon(true);
        readThread.start();

        Thread writeThread = new Thread(this::runWriteThread);
        writeThread.setDaemon(true);
        writeThread.start();

        try (
                ServerSocketChannel serverSocketChannel = ServerSocketChannel.open()
        ) {
            serverSocketChannel.bind(new InetSocketAddress(port));
            while (true) {
                SocketChannel channel = serverSocketChannel.accept();
                Client client = new Client(channel);
                client.start();
            }
        }
    }

    private void runReadThread() {
        try {
            while (true) {
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
            while (true) {
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
        }

        private void start() {
            writeKey.interestOps(0);
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
            workerPool.submit(this::processRequest);
        }

        private void processRequest() {
            try {
                buffer.flip();
                ArrayMessage payload = ArrayMessage.parseFrom(buffer);
                payload = ServerUtil.processPayload(payload);

                buffer = ServerUtil.ensureLimit(buffer, 4 + payload.getSerializedSize());
                buffer.putInt(payload.getSerializedSize());
                CodedOutputStream cos = CodedOutputStream.newInstance(buffer);
                payload.writeTo(cos);
                cos.flush();

                buffer.flip();
                nextOp = this::start;

                writeKey.interestOps(SelectionKey.OP_WRITE);
                writeSelector.wakeup();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        private void handleRead() throws IOException {
            channel.read(buffer);
            if (buffer.remaining() == 0)
                nextOp.run();
        }

        private void handleWrite() throws IOException {
            channel.write(buffer);
            if (buffer.remaining() == 0)
                nextOp.run();
        }
    }
}
