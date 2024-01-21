package ru.itmo.mse.asurkis;

import com.google.protobuf.InvalidProtocolBufferException;
import ru.itmo.mse.asurkis.Messages.ArrayMessage;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class BlockingServer {
    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(args[0]);
        int nClients = Integer.parseInt(args[1]);
        System.out.println("processing_ns,response_ns");
        new BlockingServer(port).start(nClients);
    }

    private final ExecutorService workerPool;
    private final int port;

    private BlockingServer(int port) throws IOException {
        this.port = port;
        Runtime runtime = Runtime.getRuntime();
        int nProcessors = runtime.availableProcessors();
        workerPool = Executors.newFixedThreadPool(nProcessors);
    }

    private final AtomicInteger remainingClients = new AtomicInteger();

    private void start(int nClients) throws IOException {
        remainingClients.set(nClients);
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            for (int i = 0; i < nClients; i++) {
                Socket socket = serverSocket.accept();
                Thread thread = new Thread(() -> serveClientWrap(socket));
                thread.start();
            }
        }
    }

    private void serveClientWrap(Socket socket) {
        ExecutorService responder = Executors.newSingleThreadExecutor();
        try (
                InputStream inputStream = socket.getInputStream();
                OutputStream outputStream = socket.getOutputStream();
                BufferedInputStream bufferedInputStream = new BufferedInputStream(inputStream);
                BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(outputStream);
                DataInputStream dis = new DataInputStream(bufferedInputStream);
                DataOutputStream dos = new DataOutputStream(bufferedOutputStream)
        ) {
            while (true) {
                Metrics metrics = new Metrics();

                int size;
                try {
                    size = dis.readInt();
                } catch (EOFException e) {
                    break;
                }

                byte[] requestBuf = new byte[size];
                for (int pos = 0; pos < size; )
                    pos += dis.read(requestBuf, pos, size - pos);

                metrics.requestReceived = System.nanoTime();

                workerPool.submit(() -> processAndScheduleResponse(requestBuf, metrics, dos, responder));
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            responder.shutdown();

            if (remainingClients.decrementAndGet() == 0)
                workerPool.shutdown();
        }
    }

    private void processAndScheduleResponse(byte[] requestBuf, Metrics metrics, DataOutputStream dos, ExecutorService responder) {
        try {
            metrics.processingStart = System.nanoTime();
            ArrayMessage payload = ArrayMessage.parseFrom(requestBuf);
            payload = ServerUtil.processPayload(payload);
            byte[] responseBuf = payload.toByteArray();
            metrics.processingFinish = System.nanoTime();
            responder.submit(() -> respond(responseBuf, metrics, dos));
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException(e);
        }
    }

    private void respond(byte[] buf, Metrics metrics, DataOutputStream dos) {
        try {
            dos.writeInt(buf.length);
            dos.write(buf);
            dos.flush();

            metrics.responseSent = System.nanoTime();
            ServerUtil.printMetrics(metrics);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
