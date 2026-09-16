package com.pubsub.broker;

import com.pubsub.broker.handler.ConnectionHandlerImpl;
import com.pubsub.broker.registry.TopicRegistry;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class BrokerServer {
    private static final int PORT = 5050;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static void log(String msg) {
        String timestamp = LocalDateTime.now().format(FORMATTER);
        System.out.println("[" + timestamp + "] [SERVER] " + msg);
    }

    public static void main(String[] args) {
        TopicRegistry registry = new TopicRegistry();
        ExecutorService executor = Executors.newCachedThreadPool();

        ServerSocket serverSocket = null;
        try {
            serverSocket = new ServerSocket(PORT);
            log("Broker TCP pornit pe portul " + PORT);
        } catch (IOException e) {
            log("Nu s-a putut porni ServerSocket pe portul " + PORT + ": " + e.getMessage());
            executor.shutdown();
            return;
        }

        final ServerSocket finalServerSocket = serverSocket;

        // Shutdown hook pentru închidere curată la Ctrl+C
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log("Se oprește BrokerServer...");
            try {
                if (finalServerSocket != null && !finalServerSocket.isClosed()) {
                    finalServerSocket.close();
                }
            } catch (IOException e) {
                log("Eroare la închiderea ServerSocket: " + e.getMessage());
            }

            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log("BrokerServer oprit.");
        }));

        // Loop infinit pentru acceptare conexiuni
        while (!finalServerSocket.isClosed()) {
            try {
                Socket clientSocket = finalServerSocket.accept();
                executor.submit(new ConnectionHandlerImpl(clientSocket, registry));
            } catch (IOException e) {
                if (finalServerSocket.isClosed()) {
                    log("ServerSocket-ul a fost închis.");
                    break;
                }
                log("Eroare la acceptarea conexiunii: " + e.getMessage());
            }
        }
    }
}
