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

    // Identified subscribers (handshake carries an optional "subscriberId"): unlike the
    // anonymous backlog above, which only fills when a topic has zero connected subscribers,
    // each identified subscriber gets its OWN pending queue. So if subscriber "A" disconnects
    // while subscriber "B" stays connected on the same topic, messages published in between
    // still accumulate for "A" specifically and are delivered in full when "A" reconnects
    // (same subscriberId) - matching "same subscriber sees everything it missed" persistence.
    // Purely additive: subscribers that never send a subscriberId are unaffected.
    private final Map<String, Set<String>> knownSubscriberIds = new ConcurrentHashMap<>();
    private final Map<String, Map<String, ConnectionHandler>> identifiedSubscribers = new ConcurrentHashMap<>();
    private final Map<String, Map<String, ConcurrentLinkedQueue<Message>>> identifiedBacklogs = new ConcurrentHashMap<>();

    public record TopicInfo(String name, int subscriberCount, int backlogSize) {}
    public record IdentifiedSubscriberInfo(String topic, String subscriberId, boolean connected, int pendingCount) {}

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

    /** Subscriber identified by a stable subscriberId (survives disconnect/reconnect). */
    public void addIdentifiedSubscriber(String topic, String subscriberId, ConnectionHandler handler) {
        if (topic == null || subscriberId == null || subscriberId.isBlank() || handler == null) {
            return;
        }
        knownSubscriberIds.computeIfAbsent(topic, k -> ConcurrentHashMap.newKeySet()).add(subscriberId);
        identifiedSubscribers.computeIfAbsent(topic, k -> new ConcurrentHashMap<>()).put(subscriberId, handler);
        logTopicState(topic);

        ConcurrentLinkedQueue<Message> backlog = identifiedBacklogs
                .getOrDefault(topic, Map.of())
                .get(subscriberId);
        if (backlog != null && !backlog.isEmpty()) {
            Message msg;
            while ((msg = backlog.poll()) != null) {
                try {
                    handler.send(msg);
                } catch (Exception e) {
                    DeadLetterQueue.addFailedMessage(topic, msg,
                            "Trimitere din backlog personal eșuată (subscriberId=" + subscriberId + "): " + e.getMessage());
                    removeIdentifiedSubscriber(topic, subscriberId, handler);
                    break;
                }
            }
        }
    }

    public void removeIdentifiedSubscriber(String topic, String subscriberId, ConnectionHandler handler) {
        if (topic == null || subscriberId == null) {
            return;
        }
        Map<String, ConnectionHandler> map = identifiedSubscribers.get(topic);
        if (map != null) {
            map.remove(subscriberId, handler);
        }
        logTopicState(topic);
    }

    public void broadcast(String topic, Message message) {
        if (topic == null || message == null) {
            DeadLetterQueue.addMalformedMessage("null", "Topic sau Message null transmis în broadcast");
            return;
        }

        // Anonymous subscribers (no subscriberId): unchanged legacy behaviour -
        // one shared per-topic backlog, filled only while nobody anonymous is
        // connected AND nobody identified is registered either (otherwise this
        // would double-queue messages that identified subscribers already got).
        List<ConnectionHandler> list = subscribers.get(topic);
        Set<String> knownIdsForTopic = knownSubscriberIds.get(topic);
        boolean hasIdentifiedSubscribers = knownIdsForTopic != null && !knownIdsForTopic.isEmpty();

        if (list == null || list.isEmpty()) {
            if (!hasIdentifiedSubscribers) {
                backlogs.computeIfAbsent(topic, k -> new ConcurrentLinkedQueue<>()).add(message);
            }
        } else {
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

        // Identified subscribers: each one gets the message live if connected,
        // or into ITS OWN pending queue if not - independent of anonymous subscribers above.
        Set<String> knownIds = knownSubscriberIds.get(topic);
        if (knownIds != null && !knownIds.isEmpty()) {
            Map<String, ConnectionHandler> connected = identifiedSubscribers.getOrDefault(topic, Map.of());
            Map<String, ConcurrentLinkedQueue<Message>> backlogMap =
                    identifiedBacklogs.computeIfAbsent(topic, k -> new ConcurrentHashMap<>());

            for (String subscriberId : knownIds) {
                ConnectionHandler handler = connected.get(subscriberId);
                if (handler == null) {
                    backlogMap.computeIfAbsent(subscriberId, k -> new ConcurrentLinkedQueue<>()).add(message);
                    continue;
                }
                try {
                    handler.send(message);
                } catch (Exception e) {
                    DeadLetterQueue.addFailedMessage(topic, message,
                            "Eșec livrare identificată (subscriberId=" + subscriberId + "): " + e.getMessage());
                    removeIdentifiedSubscriber(topic, subscriberId, handler);
                    backlogMap.computeIfAbsent(subscriberId, k -> new ConcurrentLinkedQueue<>()).add(message);
                }
            }
        }
    }

    public int getSubscriberCount(String topic) {
        if (topic == null) {
            return 0;
        }
        List<ConnectionHandler> list = subscribers.get(topic);
        int anonymous = list != null ? list.size() : 0;

        Map<String, ConnectionHandler> identified = identifiedSubscribers.get(topic);
        int identifiedCount = identified != null ? identified.size() : 0;

        return anonymous + identifiedCount;
    }

    public int getBacklogSize(String topic) {
        if (topic == null) {
            return 0;
        }
        ConcurrentLinkedQueue<Message> backlog = backlogs.get(topic);
        int anonymousBacklog = backlog != null ? backlog.size() : 0;

        int identifiedBacklogTotal = 0;
        Map<String, ConcurrentLinkedQueue<Message>> idBacklogs = identifiedBacklogs.get(topic);
        if (idBacklogs != null) {
            for (ConcurrentLinkedQueue<Message> queue : idBacklogs.values()) {
                identifiedBacklogTotal += queue.size();
            }
        }

        return anonymousBacklog + identifiedBacklogTotal;
    }

    /**
     * Full roster of every identified subscriber ever seen, across all topics -
     * survives even after its connection (and any UI card for it) is gone, so
     * the admin UI can show "S1 is offline on topic X, 2 messages waiting" and
     * then watch that count drop to 0 the moment S1 reconnects.
     */
    public List<IdentifiedSubscriberInfo> getIdentifiedSubscribersSnapshot() {
        List<IdentifiedSubscriberInfo> result = new ArrayList<>();
        for (Map.Entry<String, Set<String>> topicEntry : knownSubscriberIds.entrySet()) {
            String topic = topicEntry.getKey();
            Map<String, ConnectionHandler> connectedMap = identifiedSubscribers.getOrDefault(topic, Map.of());
            Map<String, ConcurrentLinkedQueue<Message>> backlogMap = identifiedBacklogs.getOrDefault(topic, Map.of());

            for (String subscriberId : topicEntry.getValue()) {
                boolean connected = connectedMap.containsKey(subscriberId);
                ConcurrentLinkedQueue<Message> queue = backlogMap.get(subscriberId);
                int pending = queue != null ? queue.size() : 0;
                result.add(new IdentifiedSubscriberInfo(topic, subscriberId, connected, pending));
            }
        }
        return result;
    }

    /**
     * Deletes a topic entirely: disconnects every subscriber (anonymous and
     * identified) currently on it, and discards its backlog and identity
     * roster. Publishers connected to it simply keep publishing into a topic
     * that gets recreated from scratch the moment anyone touches it again.
     */
    public void removeTopic(String topic) {
        if (topic == null) {
            return;
        }

        List<ConnectionHandler> anonymousHandlers = subscribers.remove(topic);
        if (anonymousHandlers != null) {
            for (ConnectionHandler handler : anonymousHandlers) {
                handler.close();
            }
        }
        backlogs.remove(topic);

        Map<String, ConnectionHandler> identifiedHandlers = identifiedSubscribers.remove(topic);
        if (identifiedHandlers != null) {
            for (ConnectionHandler handler : identifiedHandlers.values()) {
                handler.close();
            }
        }
        knownSubscriberIds.remove(topic);
        identifiedBacklogs.remove(topic);

        System.out.println("[TopicRegistry] Topicul '" + topic + "' a fost șters (subscriberi deconectați, backlog golit).");
    }

    /**
     * Forgets one identified subscriber on a topic: disconnects it if it's
     * currently online and discards its pending backlog. If it reconnects
     * later with the same subscriberId, it starts fresh (no history).
     */
    public boolean forgetIdentifiedSubscriber(String topic, String subscriberId) {
        if (topic == null || subscriberId == null) {
            return false;
        }

        Map<String, ConnectionHandler> connected = identifiedSubscribers.get(topic);
        ConnectionHandler handler = connected != null ? connected.remove(subscriberId) : null;
        if (handler != null) {
            handler.close();
        }

        Set<String> ids = knownSubscriberIds.get(topic);
        boolean existed = ids != null && ids.remove(subscriberId);

        Map<String, ConcurrentLinkedQueue<Message>> backlogMap = identifiedBacklogs.get(topic);
        if (backlogMap != null) {
            backlogMap.remove(subscriberId);
        }

        return existed || handler != null;
    }

    /** Snapshot of every topic seen so far (has subscribers and/or a pending backlog), for the admin UI. */
    public List<TopicInfo> getTopicsSnapshot() {
        Set<String> names = new TreeSet<>();
        names.addAll(subscribers.keySet());
        names.addAll(backlogs.keySet());
        names.addAll(knownSubscriberIds.keySet());

        List<TopicInfo> snapshot = new ArrayList<>();
        for (String name : names) {
            snapshot.add(new TopicInfo(name, getSubscriberCount(name), getBacklogSize(name)));
        }
        return snapshot;
    }
}
