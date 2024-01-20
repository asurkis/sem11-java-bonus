package ru.itmo.mse.asurkis;

import com.google.protobuf.InvalidProtocolBufferException;
import ru.itmo.mse.asurkis.Messages.ArrayMessage;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BlockingServer {
    public static void main(String[] args) throws IOException {
        new BlockingServer(4444).start();
    }

    private final ExecutorService workerPool;
    private final int port;

    private BlockingServer(int port) throws IOException {
        this.port = port;
        Runtime runtime = Runtime.getRuntime();
        int nProcessors = runtime.availableProcessors();
        workerPool = Executors.newFixedThreadPool(nProcessors);
    }

    private void start() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            while (true) {
                Socket socket = serverSocket.accept();
                Thread thread = new Thread(() -> serveClientWrap(socket));
                thread.setDaemon(true);
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
                int size;
                try {
                    size = dis.readInt();
                } catch (EOFException e) {
                    break;
                }

                byte[] buf = new byte[size];
                for (int pos = 0; pos < size;)
                    pos += dis.read(buf, pos, size - pos);

                workerPool.submit(() -> processAndScheduleResponse(buf, dos, responder));
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            responder.shutdown();
        }
    }

    private void processAndScheduleResponse(byte[] buf, DataOutputStream dos, ExecutorService responder) {
        try {
            ArrayMessage payload = ArrayMessage.parseFrom(buf);
            payload = ServerUtil.processPayload(payload);
            byte[] responseBuf = payload.toByteArray();
            responder.submit(() -> respond(responseBuf, dos));
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException(e);
        }
    }

    private void respond(byte[] buf, DataOutputStream dos) {
        try {
            dos.writeInt(buf.length);
            dos.write(buf);
            dos.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
