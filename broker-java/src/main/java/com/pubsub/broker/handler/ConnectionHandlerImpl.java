package com.pubsub.broker.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.pubsub.broker.dlq.DeadLetterQueue;
import com.pubsub.broker.exception.InvalidMessageException;
import com.pubsub.broker.model.Message;
import com.pubsub.broker.net.ConnectionHandler;
import com.pubsub.broker.registry.TopicRegistry;
import com.pubsub.broker.util.JsonUtil;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

public class ConnectionHandlerImpl implements ConnectionHandler, Runnable {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final Socket socket;
    private final TopicRegistry topicRegistry;
    private BufferedReader reader;
    private BufferedWriter writer;

    public ConnectionHandlerImpl(Socket socket, TopicRegistry topicRegistry) {
        this.socket = socket;
        this.topicRegistry = topicRegistry;
    }

    private void log(String msg) {
        String timestamp = LocalDateTime.now().format(FORMATTER);
        String clientInfo = socket.getRemoteSocketAddress() != null ? socket.getRemoteSocketAddress().toString() : "unknown";
        System.out.println("[" + timestamp + "] [" + clientInfo + "] " + msg);
    }

    @Override
    public void run() {
        log("Conexiune nouă acceptată");

        try {
            this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

            // Step 1: Read Handshake
            String line = reader.readLine();
            if (line == null) {
                log("Conexiune închisă înainte de handshake");
                closeQuietly();
                return;
            }

            JsonNode handshakeNode;
            try {
                handshakeNode = JsonUtil.fromJson(line, JsonNode.class);
            } catch (InvalidMessageException e) {
                DeadLetterQueue.addMalformedMessage(line, "Handshake JSON invalid: " + e.getMessage());
                sendRawResponse(JsonUtil.toJson(Map.of("status", "error", "reason", "Handshake JSON invalid: " + e.getMessage())));
                closeQuietly();
                return;
            }

            if (handshakeNode == null || !handshakeNode.has("role") || !handshakeNode.has("topic")) {
                DeadLetterQueue.addMalformedMessage(line, "Handshake incomplet - lipsește role sau topic");
                sendRawResponse(JsonUtil.toJson(Map.of("status", "error", "reason", "Handshake-ul trebuie să conțină role și topic")));
                closeQuietly();
                return;
            }

            String role = handshakeNode.get("role").asText();
            String topic = handshakeNode.get("topic").asText();
            String subscriberId = handshakeNode.has("subscriberId") && !handshakeNode.get("subscriberId").isNull()
                    ? handshakeNode.get("subscriberId").asText()
                    : null;

            log("Handshake primit: role=" + role + ", topic=" + topic
                    + (subscriberId != null ? ", subscriberId=" + subscriberId : ""));

            if ("subscriber".equalsIgnoreCase(role)) {
                handleSubscriber(topic, subscriberId);
            } else if ("publisher".equalsIgnoreCase(role)) {
                handlePublisher(topic);
            } else {
                DeadLetterQueue.addMalformedMessage(line, "Role invalid în handshake: " + role);
                sendRawResponse(JsonUtil.toJson(Map.of("status", "error", "reason", "Role invalid: " + role)));
                closeQuietly();
            }

        } catch (IOException e) {
            log("IOException pe conexiune: " + e.getMessage());
            closeQuietly();
        }
    }

    private void handleSubscriber(String topic, String subscriberId) throws IOException {
        boolean identified = subscriberId != null && !subscriberId.isBlank();
        if (identified) {
            topicRegistry.addIdentifiedSubscriber(topic, subscriberId, this);
            log("Abonat identificat cu succes la topicul: " + topic + " (subscriberId=" + subscriberId + ")");
        } else {
            topicRegistry.addSubscriber(topic, this);
            log("Abonat cu succes la topicul: " + topic);
        }

        try {
            // Rămâne blocat citind linii pentru a detecta deconectarea
            while (true) {
                String line = reader.readLine();
                if (line == null) {
                    break;
                }
            }
        } finally {
            if (identified) {
                topicRegistry.removeIdentifiedSubscriber(topic, subscriberId, this);
            } else {
                topicRegistry.removeSubscriber(topic, this);
            }
            log("Subscriber deconectat de la topicul: " + topic);
            closeQuietly();
        }
    }

    private void handlePublisher(String topic) throws IOException {
        log("Publisher conectat pentru topicul: " + topic);

        try {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    Message message = JsonUtil.fromJson(line, Message.class);
                    log("Mesaj publicat pe topicul: " + topic);
                    topicRegistry.broadcast(topic, message);
                    sendRawResponse(JsonUtil.toJson(Map.of("status", "ok")));
                } catch (InvalidMessageException e) {
                    log("Eroare parsare mesaj publicat: " + e.getMessage());
                    DeadLetterQueue.addMalformedMessage(topic, line, "Mesaj malformat de la publisher: " + e.getMessage());
                    sendRawResponse(JsonUtil.toJson(Map.of("status", "error", "reason", e.getMessage())));
                }
            }
        } finally {
            log("Publisher deconectat pentru topicul: " + topic);
            closeQuietly();
        }
    }

    @Override
    public synchronized void send(Message message) throws IOException {
        String json = JsonUtil.toJson(message);
        writer.write(json);
        writer.newLine();
        writer.flush();
    }

    private synchronized void sendRawResponse(String rawJson) throws IOException {
        writer.write(rawJson);
        writer.newLine();
        writer.flush();
    }

    private void closeQuietly() {
        try {
            if (!socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() {
        closeQuietly();
    }
}
