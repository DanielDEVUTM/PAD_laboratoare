package com.pubsub.broker;

import com.pubsub.broker.admin.AdminHttpServer;
import com.pubsub.broker.grpc.GrpcBrokerServiceImpl;
import com.pubsub.broker.handler.ConnectionHandlerImpl;
import com.pubsub.broker.registry.TopicRegistry;
import io.grpc.Server;
import io.grpc.ServerBuilder;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class BrokerServer {
    private static final int TCP_PORT = 5050;
    private static final int GRPC_PORT = 5051;
    private static final int ADMIN_PORT = 5052;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static void log(String msg) {
        String timestamp = LocalDateTime.now().format(FORMATTER);
        System.out.println("[" + timestamp + "] [SERVER] " + msg);
    }

    public static void main(String[] args) {
        String mode = "both";
        for (String arg : args) {
            if (arg.startsWith("--mode=")) {
                mode = arg.substring("--mode=".length()).toLowerCase();
            }
        }

        TopicRegistry registry = new TopicRegistry();
        ExecutorService tcpExecutor = Executors.newCachedThreadPool();

        boolean runTcp = mode.equals("tcp") || mode.equals("both");
        boolean runGrpc = mode.equals("grpc") || mode.equals("both");

        ServerSocket tcpServerSocket = null;
        Server grpcServer = null;

        if (runTcp) {
            try {
                tcpServerSocket = new ServerSocket(TCP_PORT);
                log("Broker TCP pornit pe portul " + TCP_PORT);
            } catch (IOException e) {
                log("Nu s-a putut porni ServerSocket pe portul " + TCP_PORT + ": " + e.getMessage());
                tcpExecutor.shutdown();
                return;
            }
        }

        if (runGrpc) {
            try {
                grpcServer = ServerBuilder.forPort(GRPC_PORT)
                        .addService(new GrpcBrokerServiceImpl(registry))
                        .build()
                        .start();
                log("Broker gRPC pornit pe portul " + GRPC_PORT);
            } catch (IOException e) {
                log("Nu s-a putut porni Serverul gRPC pe portul " + GRPC_PORT + ": " + e.getMessage());
                if (tcpServerSocket != null && !tcpServerSocket.isClosed()) {
                    try {
                        tcpServerSocket.close();
                    } catch (IOException ignored) {}
                }
                tcpExecutor.shutdown();
                return;
            }
        }

        AdminHttpServer adminServer;
        try {
            adminServer = new AdminHttpServer(ADMIN_PORT, registry);
            adminServer.start();
            log("Admin HTTP API (UI) pornit pe portul " + ADMIN_PORT);
        } catch (IOException e) {
            log("Nu s-a putut porni Admin HTTP API pe portul " + ADMIN_PORT + ": " + e.getMessage());
            adminServer = null;
        }

        final ServerSocket finalTcpServerSocket = tcpServerSocket;
        final Server finalGrpcServer = grpcServer;
        final AdminHttpServer finalAdminServer = adminServer;

        // Shutdown hook pentru închidere curată la Ctrl+C
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log("Se oprește BrokerServer...");
            if (finalTcpServerSocket != null && !finalTcpServerSocket.isClosed()) {
                try {
                    finalTcpServerSocket.close();
                } catch (IOException e) {
                    log("Eroare la închiderea TCP ServerSocket: " + e.getMessage());
                }
            }

            if (finalGrpcServer != null) {
                finalGrpcServer.shutdown();
            }

            if (finalAdminServer != null) {
                finalAdminServer.stop();
            }

            tcpExecutor.shutdown();
            try {
                if (!tcpExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    tcpExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                tcpExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log("BrokerServer oprit.");
        }));

        if (runTcp) {
            while (!finalTcpServerSocket.isClosed()) {
                try {
                    Socket clientSocket = finalTcpServerSocket.accept();
                    tcpExecutor.submit(new ConnectionHandlerImpl(clientSocket, registry));
                } catch (IOException e) {
                    if (finalTcpServerSocket.isClosed()) {
                        log("TCP ServerSocket-ul a fost închis.");
                        break;
                    }
                    log("Eroare la acceptarea conexiunii TCP: " + e.getMessage());
                }
            }
        } else if (runGrpc && grpcServer != null) {
            try {
                grpcServer.awaitTermination();
            } catch (InterruptedException e) {
                log("Serverul gRPC a fost întrerupt: " + e.getMessage());
                Thread.currentThread().interrupt();
            }
        }
    }
}
