package ru.itmo.mse.asurkis;

import ru.itmo.mse.asurkis.Messages.ArrayMessage;

import java.io.*;
import java.net.Socket;

public class Client {
    public static void main(String[] args) throws InterruptedException, IOException {
        String serverAddress = args[0];
        int serverPort = Integer.parseInt(args[1]);
        int payloadSize = Integer.parseInt(args[2]);
        long delayMs = Long.parseLong(args[3]);
        int nRequests = Integer.parseInt(args[4]);
        new Client(serverAddress, serverPort).execute(payloadSize, delayMs, nRequests);
    }

    private final String serverAddress;
    private final int serverPort;

    public Client(String serverAddress, int serverPort) {
        this.serverAddress = serverAddress;
        this.serverPort = serverPort;
    }

    public void execute(int payloadSize, long delayMs, int nRequests) throws InterruptedException, IOException {
        long start, finish;

        ArrayMessage.Builder requestBuilder = ArrayMessage.newBuilder();
        for (int x = payloadSize; x > 0; x--)
            requestBuilder.addX(x);
        ArrayMessage requestMessage = requestBuilder.build();
        byte[] requestBytes = requestMessage.toByteArray();
        byte[] responseBytes = new byte[requestBytes.length];

        try (
                Socket socket = new Socket(serverAddress, serverPort);
                InputStream inputStream = socket.getInputStream();
                OutputStream outputStream = socket.getOutputStream();
                BufferedInputStream bufferedInputStream = new BufferedInputStream(inputStream);
                BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(outputStream);
                DataInputStream dis = new DataInputStream(bufferedInputStream);
                DataOutputStream dos = new DataOutputStream(bufferedOutputStream)
        ) {
            start = System.nanoTime();
            for (int i = 0; i < nRequests; i++) {
                dos.writeInt(requestBytes.length);
                dos.write(requestBytes);
                dos.flush();

                int responseSize = dis.readInt();
                assert responseSize == responseBytes.length;
                for (int pos = 0; pos < responseSize; )
                    pos += dis.read(responseBytes, pos, responseSize - pos);

                ArrayMessage responseMessage = ArrayMessage.parseFrom(responseBytes);
                assert responseMessage.getXCount() == payloadSize;
                for (int j = 0; j < payloadSize; j++)
                    assert responseMessage.getX(j) == j + 1;

                // Более точного метода обеспечить ожидание всё равно нет,
                // ScheduledExecutorService даёт точно такие же гарантии
                Thread.sleep(delayMs);
            }
            finish = System.nanoTime();
        }

        System.out.println(finish - start);
        // System.out.println((double) (finish - start) / nRequests);
        // System.out.println((double) (finish - start) / nRequests - delayMs);
    }
}
