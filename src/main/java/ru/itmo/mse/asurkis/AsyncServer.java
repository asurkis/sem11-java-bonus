package ru.itmo.mse.asurkis;

import com.google.protobuf.CodedOutputStream;
import ru.itmo.mse.asurkis.Messages.ArrayMessage;

import java.io.Closeable;
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

    private void start() throws IOException, ExecutionException, InterruptedException {
        try (AsynchronousServerSocketChannel serverSocketChannel = AsynchronousServerSocketChannel.open()) {
            serverSocketChannel.bind(new InetSocketAddress(port));

            while (true) {
                Future<AsynchronousSocketChannel> future = serverSocketChannel.accept();
                AsynchronousSocketChannel channel = future.get();
                Client client = new Client(channel);
                client.start();
            }
        }
    }

    private class Client implements Closeable {
        private static final HeaderHandler HEADER_HANDLER = new HeaderHandler();
        private static final BodyHandler BODY_HANDLER = new BodyHandler();
        private static final ResponseHandler RESPONSE_HANDLER = new ResponseHandler();

        private final AsynchronousSocketChannel channel;

        // Выделим заранее память, будем увеличивать количество, если понадобится
        private ByteBuffer buffer = ByteBuffer.allocate(ServerUtil.START_CAPACITY_BYTES);

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
        }

        private void handleHead(int bytesRead) throws IOException {
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

        private void handleBody() throws IOException {
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
                assert client.buffer.remaining() == 0;
                client.handleBody();
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
            assert client.buffer.remaining() == 0;
            client.start();
        }

        @Override
        public void failed(Throwable throwable, Client client) {
            // Не обрабатываем в рамках эксперимента
            throw new RuntimeException(throwable);
        }
    }
}
