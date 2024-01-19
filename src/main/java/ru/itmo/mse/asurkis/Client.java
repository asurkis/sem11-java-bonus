package ru.itmo.mse.asurkis;

import java.io.*;
import java.net.Socket;

public class Client {
    public static void main(String[] args) throws InterruptedException {
        new Client().execute(4 * 1024, 10, 10);
    }

    private void execute(int payloadSize, long delayMs, int nRequests) throws InterruptedException {
        long start, finish;
        try (
                Socket socket = new Socket("localhost", 4444);
                InputStream inputStream = socket.getInputStream();
                OutputStream outputStream = socket.getOutputStream();
                DataInputStream dis = new DataInputStream(inputStream);
                DataOutputStream dos = new DataOutputStream(outputStream)
        ) {
            start = System.currentTimeMillis();
            for (int i = 0; i < nRequests; i++) {
                dos.writeInt(payloadSize);
                for (int x = payloadSize; x > 0; x--) dos.writeInt(x);

                int n = dis.readInt();
                assert n == payloadSize;
                for (int j = 1; j <= payloadSize; j++) {
                    int x = dis.readInt();
                    assert x == j;
                }
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
