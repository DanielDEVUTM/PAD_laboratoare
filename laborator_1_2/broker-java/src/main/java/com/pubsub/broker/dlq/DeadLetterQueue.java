package com.pubsub.broker.dlq;

import com.pubsub.broker.model.Message;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

public class DeadLetterQueue {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final ConcurrentLinkedQueue<DeadLetterEntry> DLQ = new ConcurrentLinkedQueue<>();

    public record DeadLetterEntry(
            String timestamp,
            String topic,
            Object rawContent,
            String reason
    ) {}

    private DeadLetterQueue() {
    }

    public static void addFailedMessage(String topic, Message message, String reason) {
        String timestamp = LocalDateTime.now().format(FORMATTER);
        DLQ.add(new DeadLetterEntry(timestamp, topic, message, reason));
        System.out.println("[" + timestamp + "] [DLQ] Mesaj adăugat în Dead Letter Queue pe topicul '" + topic + "'. Motiv: " + reason);
    }

    public static void addMalformedMessage(String rawJson, String reason) {
        addMalformedMessage("UNKNOWN", rawJson, reason);
    }

    /** Same as above, but for use when the topic IS already known (e.g. a publisher's
     * handshake succeeded and only the message body that followed was malformed). */
    public static void addMalformedMessage(String topic, String rawJson, String reason) {
        String timestamp = LocalDateTime.now().format(FORMATTER);
        String effectiveTopic = (topic == null || topic.isBlank()) ? "UNKNOWN" : topic;
        DLQ.add(new DeadLetterEntry(timestamp, effectiveTopic, rawJson, reason));
        System.out.println("[" + timestamp + "] [DLQ] Mesaj JSON malformat adăugat în Dead Letter Queue (topic: " + effectiveTopic + "). Motiv: " + reason);
    }

    public static List<DeadLetterEntry> getEntries() {
        return new ArrayList<>(DLQ);
    }

    public static int size() {
        return DLQ.size();
    }

    public static void clear() {
        DLQ.clear();
    }
}
