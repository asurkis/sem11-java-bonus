package ru.itmo.mse.asurkis;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BlockingServer {
    public static void main(String[] args) throws IOException {
        new BlockingServer(4444).start();
    }

    private final ServerSocket serverSocket;
    private final ExecutorService workerPool;

    private BlockingServer(int port) throws IOException {
        serverSocket = new ServerSocket(port);
        Runtime runtime = Runtime.getRuntime();
        int nProcessors = runtime.availableProcessors();
        workerPool = Executors.newFixedThreadPool(nProcessors);
    }

    private void start() throws IOException {
        while (true) {
            Socket socket = serverSocket.accept();
            Thread thread = new Thread(() -> serveClientWrap(socket));
            thread.setDaemon(true);
            thread.start();
        }
    }

    private void serveClientWrap(Socket socket) {
        ExecutorService responder = Executors.newSingleThreadExecutor();
        try (
                InputStream inputStream = socket.getInputStream();
                OutputStream outputStream = socket.getOutputStream();
                DataInputStream dis = new DataInputStream(inputStream);
                DataOutputStream dos = new DataOutputStream(outputStream)
        ) {
            while (true) {
                int n;
                try {
                    n = dis.readInt();
                } catch (EOFException e) {
                    break;
                }

                // Если за размером сообщения не идёт полный массив, то это ошибка протокола,
                // поэтому не обрабатываем отдельно EOF
                int[] payload = new int[n];
                for (int i = 0; i < n; i++) payload[i] = dis.readInt();

                workerPool.submit(() -> processAndScheduleResponse(payload, dos, responder));
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            responder.shutdown();
        }
    }

    private void processAndScheduleResponse(int[] payload, DataOutputStream dos, ExecutorService responder) {
        ServerUtil.sortInPlace(payload);
        responder.submit(() -> respond(payload, dos));
    }

    private void respond(int[] payload, DataOutputStream dos) {
        try {
            dos.writeInt(payload.length);
            for (int x : payload) dos.writeInt(x);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
