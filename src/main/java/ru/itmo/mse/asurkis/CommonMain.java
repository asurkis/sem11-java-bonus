package ru.itmo.mse.asurkis;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

public class CommonMain {
    public static void main(String[] args) throws IOException, InterruptedException, ExecutionException {
        switch (args[0]) {
            case "client" -> {
                String serverAddress = args[1];
                int serverPort = Integer.parseInt(args[2]);
                int payloadSize = Integer.parseInt(args[3]);
                long delayMs = Long.parseLong(args[4]);
                int nRequests = Integer.parseInt(args[5]);
                Client client = new Client(serverAddress, serverPort);
                client.execute(payloadSize, delayMs, nRequests);
            }
            case "blocking" -> {
                int port = Integer.parseInt(args[1]);
                int nClients = Integer.parseInt(args[2]);
                BlockingServer server = new BlockingServer(port);
                server.start(nClients);
            }
            case "nonblocking" -> {
                int port = Integer.parseInt(args[1]);
                int nClients = Integer.parseInt(args[2]);
                NonBlockingServer server = new NonBlockingServer(port);
                server.start(nClients);
            }
            case "async" -> {
                int port = Integer.parseInt(args[1]);
                int nClients = Integer.parseInt(args[2]);
                AsyncServer server = new AsyncServer(port);
                server.start(nClients);
            }
        }
    }
}
