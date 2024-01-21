package ru.itmo.mse.asurkis;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class AsyncServer {
    public static void main(String[] args) throws IOException, ExecutionException, InterruptedException {
        int port = Integer.parseInt(args[0]);
        int nClients = Integer.parseInt(args[1]);
        new AsyncServer(port).start(nClients);
    }

    private final ExecutorService workerPool;
    private final int port;
    private final AsynchronousChannelGroup channelGroup;


    public AsyncServer(int port) throws IOException {
        this.port = port;
        Runtime runtime = Runtime.getRuntime();
        int nProcessors = runtime.availableProcessors();
        workerPool = Executors.newFixedThreadPool(nProcessors);

        ExecutorService asyncPool = Executors.newSingleThreadExecutor();
        channelGroup = AsynchronousChannelGroup.withThreadPool(asyncPool);
    }

    private final AtomicInteger remainingClients = new AtomicInteger();

    public void start(int nClients) throws IOException, ExecutionException, InterruptedException {
        System.out.println("processing_ns,response_ns");
        remainingClients.set(nClients);
        try (AsynchronousServerSocketChannel serverSocketChannel = AsynchronousServerSocketChannel.open(channelGroup)) {
            serverSocketChannel.bind(new InetSocketAddress(port));

            for (int i = 0; i < nClients; i++) {
                Future<AsynchronousSocketChannel> future = serverSocketChannel.accept();
                AsynchronousSocketChannel channel = future.get();
                Client client = new Client(channel);
                client.start();
            }
        }

        // Ждём, пока все операции не завершатся
        assert channelGroup.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
    }

    private class Client implements Closeable {
        private static final HeaderHandler HEADER_HANDLER = new HeaderHandler();
        private static final BodyHandler BODY_HANDLER = new BodyHandler();
        private static final ResponseHandler RESPONSE_HANDLER = new ResponseHandler();

        private final AsynchronousSocketChannel channel;

        // Выделим заранее память, будем увеличивать количество, если понадобится
        private ByteBuffer buffer = ByteBuffer.allocate(ServerUtil.START_CAPACITY_BYTES);

        private final Metrics metrics = new Metrics();

        private Client(AsynchronousSocketChannel channel) {
            this.channel = channel;
        }

        private void start() {
            buffer.clear();
            buffer.limit(4);
            channel.read(buffer, this, HEADER_HANDLER);
        }

        @Override
        public void close() throws IOException {
            channel.close();

            if (remainingClients.decrementAndGet() == 0) {
                workerPool.shutdown();
                channelGroup.shutdown();
            }
        }

        private void onReadSize(int bytesRead) throws IOException {
            if (bytesRead == -1) {
                close();
                return;
            }
            assert buffer.remaining() == 0;

            buffer.flip();
            int size = buffer.getInt();
            buffer = ServerUtil.ensureLimit(buffer, size);
            channel.read(buffer, this, BODY_HANDLER);
        }

        private void onReadArray() {
            assert buffer.remaining() == 0;
            metrics.requestReceived = System.nanoTime();
            workerPool.submit(this::processRequest);
        }

        private void onWriteResponse() {
            assert buffer.remaining() == 0;
            metrics.responseSent = System.nanoTime();
            ServerUtil.printMetrics(metrics);
            start();
        }

        private void processRequest() {
            try {
                metrics.processingStart = System.nanoTime();
                buffer = ServerUtil.processPayload(buffer);
                metrics.processingFinish = System.nanoTime();
                channel.write(buffer, this, RESPONSE_HANDLER);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static class HeaderHandler implements CompletionHandler<Integer, Client> {
        @Override
        public void completed(Integer bytesRead, Client client) {
            try {
                client.onReadSize(bytesRead);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void failed(Throwable throwable, Client client) {
            // Не обрабатываем в рамках эксперимента
            throw new RuntimeException(throwable);
        }
    }

    private static class BodyHandler implements CompletionHandler<Integer, Client> {
        @Override
        public void completed(Integer bytesRead, Client client) {
            client.onReadArray();
        }

        @Override
        public void failed(Throwable throwable, Client client) {
            // Не обрабатываем в рамках эксперимента
            throw new RuntimeException(throwable);
        }
    }

    private static class ResponseHandler implements CompletionHandler<Integer, Client> {
        @Override
        public void completed(Integer bytesWritten, Client client) {
            client.onWriteResponse();
        }

        @Override
        public void failed(Throwable throwable, Client client) {
            // Не обрабатываем в рамках эксперимента
            throw new RuntimeException(throwable);
        }
    }
}
