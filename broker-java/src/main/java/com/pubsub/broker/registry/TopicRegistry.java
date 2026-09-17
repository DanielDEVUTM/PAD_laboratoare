package com.pubsub.broker.registry;

import com.pubsub.broker.dlq.DeadLetterQueue;
import com.pubsub.broker.model.Message;
import com.pubsub.broker.net.ConnectionHandler;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

public class TopicRegistry {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private final Map<String, List<ConnectionHandler>> subscribers = new ConcurrentHashMap<>();
    private final Map<String, ConcurrentLinkedQueue<Message>> backlogs = new ConcurrentHashMap<>();

    public record TopicInfo(String name, int subscriberCount, int backlogSize) {}

    private void logTopicState(String topic) {
        String timestamp = LocalDateTime.now().format(FORMATTER);
        int count = getSubscriberCount(topic);
        System.out.println("[" + timestamp + "] Topic '" + topic + "' has " + count + " subscriber(s)");
    }

    public void addSubscriber(String topic, ConnectionHandler handler) {
        if (topic == null || handler == null) {
            return;
        }
        subscribers.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(handler);
        logTopicState(topic);

        // Flushes accumulated backlog messages to the new subscriber
        ConcurrentLinkedQueue<Message> backlog = backlogs.get(topic);
        if (backlog != null && !backlog.isEmpty()) {
            Message msg;
            while ((msg = backlog.poll()) != null) {
                try {
                    handler.send(msg);
                } catch (IOException e) {
                    DeadLetterQueue.addFailedMessage(topic, msg, "Trimitere din backlog eșuată (subscriber deconectat): " + e.getMessage());
                    removeSubscriber(topic, handler);
                    break;
                } catch (Exception e) {
                    DeadLetterQueue.addFailedMessage(topic, msg, "Excepție neașteptată trimitere din backlog: " + e.getMessage());
                    removeSubscriber(topic, handler);
                    break;
                }
            }
        }
    }

    public void removeSubscriber(String topic, ConnectionHandler handler) {
        if (topic == null || handler == null) {
            return;
        }
        List<ConnectionHandler> list = subscribers.get(topic);
        if (list != null) {
            list.remove(handler);
        }
        logTopicState(topic);
    }

    public void broadcast(String topic, Message message) {
        if (topic == null || message == null) {
            DeadLetterQueue.addMalformedMessage("null", "Topic sau Message null transmis în broadcast");
            return;
        }

        if (getSubscriberCount(topic) == 0) {
            backlogs.computeIfAbsent(topic, k -> new ConcurrentLinkedQueue<>()).add(message);
            return;
        }

        List<ConnectionHandler> list = subscribers.get(topic);
        if (list == null || list.isEmpty()) {
            backlogs.computeIfAbsent(topic, k -> new ConcurrentLinkedQueue<>()).add(message);
            return;
        }

        for (ConnectionHandler handler : list) {
            try {
                handler.send(message);
            } catch (IOException e) {
                DeadLetterQueue.addFailedMessage(topic, message, "Eșec livrare către subscriber (deconectat): " + e.getMessage());
                removeSubscriber(topic, handler);
            } catch (Exception e) {
                DeadLetterQueue.addFailedMessage(topic, message, "Excepție neașteptată livrare: " + e.getMessage());
                removeSubscriber(topic, handler);
            }
        }
    }

    public int getSubscriberCount(String topic) {
        if (topic == null) {
            return 0;
        }
        List<ConnectionHandler> list = subscribers.get(topic);
        return list != null ? list.size() : 0;
    }

    public int getBacklogSize(String topic) {
        if (topic == null) {
            return 0;
        }
        ConcurrentLinkedQueue<Message> backlog = backlogs.get(topic);
        return backlog != null ? backlog.size() : 0;
    }

    /** Snapshot of every topic seen so far (has subscribers and/or a pending backlog), for the admin UI. */
    public List<TopicInfo> getTopicsSnapshot() {
        Set<String> names = new TreeSet<>();
        names.addAll(subscribers.keySet());
        names.addAll(backlogs.keySet());

        List<TopicInfo> snapshot = new ArrayList<>();
        for (String name : names) {
            snapshot.add(new TopicInfo(name, getSubscriberCount(name), getBacklogSize(name)));
        }
        return snapshot;
    }
}
