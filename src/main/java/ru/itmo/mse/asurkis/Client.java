package ru.itmo.mse.asurkis;

import ru.itmo.mse.asurkis.Messages.ArrayMessage;

import java.io.*;
import java.net.Socket;

public class Client {
    public static void main(String[] args) throws InterruptedException {
        new Client().execute(4 * 1024, 10, 10);
    }

    private void execute(int payloadSize, long delayMs, int nRequests) throws InterruptedException {
        long start, finish;

        ArrayMessage.Builder requestBuilder = ArrayMessage.newBuilder();
        for (int x = payloadSize; x > 0; x--)
            requestBuilder.addX(x);
        ArrayMessage requestMessage = requestBuilder.build();
        byte[] requestBytes = requestMessage.toByteArray();
        byte[] responseBytes = new byte[requestBytes.length];

        try (
                Socket socket = new Socket("localhost", 4444);
                InputStream inputStream = socket.getInputStream();
                OutputStream outputStream = socket.getOutputStream();
                BufferedInputStream bufferedInputStream = new BufferedInputStream(inputStream);
                BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(outputStream);
                DataInputStream dis = new DataInputStream(bufferedInputStream);
                DataOutputStream dos = new DataOutputStream(bufferedOutputStream)
        ) {
            start = System.currentTimeMillis();
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
            }
            finish = System.currentTimeMillis();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        System.out.println(finish - start);
        System.out.println((double) (finish - start) / nRequests);
        System.out.println((double) (finish - start) / nRequests - delayMs);
    }
}
