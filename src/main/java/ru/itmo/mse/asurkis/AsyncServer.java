package ru.itmo.mse.asurkis;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class AsyncServer {
    public static void main(String[] args) throws IOException, ExecutionException, InterruptedException {
        new AsyncServer(4444).start();
    }

    private final ExecutorService workerPool;
    private final int port;

    private AsyncServer(int port) {
        this.port = port;
        Runtime runtime = Runtime.getRuntime();
        int nProcessors = runtime.availableProcessors();
        workerPool = Executors.newFixedThreadPool(nProcessors);
    }

    private AsynchronousServerSocketChannel serverSocketChannel;

    private void start() throws IOException, ExecutionException, InterruptedException {
        serverSocketChannel = AsynchronousServerSocketChannel.open();
        serverSocketChannel.bind(new InetSocketAddress(port));
        scheduleAccept();
    }

    private void scheduleAccept() throws ExecutionException, InterruptedException {
        while (true) {
            Future<AsynchronousSocketChannel> future = serverSocketChannel.accept();
            AsynchronousSocketChannel channel = future.get();
            Client client = new Client(channel);
            client.scheduleHead();
        }
    }

    private class Client {
        private static final HeaderHandler HEADER_HANDLER = new HeaderHandler();
        private static final BodyHandler BODY_HANDLER = new BodyHandler();
        private static final ResponseHandler RESPONSE_HANDLER = new ResponseHandler();

        private final AsynchronousSocketChannel channel;
        // Выделим заранее память, будем увеличивать количество, если понадобится
        private ByteBuffer buffer = ByteBuffer.allocate(1024);

        private Client(AsynchronousSocketChannel channel) {
            this.channel = channel;
            buffer.limit(4);
        }

        private void finish() throws IOException {
            channel.close();
        }

        private void scheduleHead() {
            channel.read(buffer, this, HEADER_HANDLER);
        }

        private void scheduleBody() {
            channel.read(buffer, this, BODY_HANDLER);
        }

        private void scheduleResponse() {
            channel.write(buffer, this, RESPONSE_HANDLER);
        }

        private void handleHead(int bytesRead) throws IOException {
            if (bytesRead == 0) {
                finish();
                return;
            }

            if (buffer.position() < buffer.limit()) {
                scheduleHead();
                return;
            }

            buffer.flip();
            int arrayLength = buffer.getInt();

            int newCapacity = buffer.capacity();
            int newLimit = 4 + 4 * arrayLength;
            while (newCapacity < newLimit)
                newCapacity *= 2;
            if (newCapacity != buffer.capacity())
                buffer = ByteBuffer.allocate(newCapacity);

            buffer.clear();
            buffer.position(4);
            buffer.limit(newLimit);
            scheduleBody();
        }

        private void handleBody(int bytesRead) throws IOException {
            if (bytesRead == 0) {
                finish();
                throw new RuntimeException("Protocol violation");
            }

            if (buffer.position() < buffer.limit()) {
                scheduleBody();
                return;
            }

            workerPool.submit(this::processRequest);
        }

        private void handleResponse(int bytesWritten) throws IOException {
            if (bytesWritten == 0) {
                finish();
                throw new RuntimeException("Protocol violation");
            }

            if (buffer.position() < buffer.limit()) {
                scheduleBody();
                return;
            }

            buffer.clear();
            buffer.limit(4);
            scheduleHead();
        }

        private void processRequest() {
            buffer.flip();
            int[] arr = new int[buffer.limit() / 4];
            for (int i = 0; i < arr.length; i++)
                arr[i] = buffer.getInt();

            ServerUtil.sortInPlace(arr);
            buffer.clear();
            buffer.putInt(arr.length);
            for (int x : arr) buffer.putInt(x);
            buffer.flip();

            scheduleResponse();
        }
    }

    private static class HeaderHandler implements CompletionHandler<Integer, Client> {
        @Override
        public void completed(Integer bytesRead, Client client) {
            try {
                client.handleHead(bytesRead);
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
            try {
                client.handleBody(bytesRead);
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

    private static class ResponseHandler implements CompletionHandler<Integer, Client> {
        @Override
        public void completed(Integer bytesWritten, Client client) {
            try {
                client.handleResponse(bytesWritten);
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
}
