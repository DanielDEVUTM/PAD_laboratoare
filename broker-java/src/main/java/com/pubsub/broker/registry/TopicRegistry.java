package com.pubsub.broker.registry;

import com.pubsub.broker.model.Message;
import com.pubsub.broker.net.ConnectionHandler;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class TopicRegistry {
    private final Map<String, List<ConnectionHandler>> subscribers = new ConcurrentHashMap<>();

    public void addSubscriber(String topic, ConnectionHandler handler) {
        if (topic == null || handler == null) {
            return;
        }
        subscribers.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(handler);
    }

    public void removeSubscriber(String topic, ConnectionHandler handler) {
        if (topic == null || handler == null) {
            return;
        }
        List<ConnectionHandler> list = subscribers.get(topic);
        if (list != null) {
            list.remove(handler);
        }
    }

    public void broadcast(String topic, Message message) {
        if (topic == null || message == null) {
            return;
        }
        List<ConnectionHandler> list = subscribers.get(topic);
        if (list == null || list.isEmpty()) {
            return;
        }

        for (ConnectionHandler handler : list) {
            try {
                handler.send(message);
            } catch (IOException e) {
                // Dacă trimiterea eșuează, eliminăm handler-ul din listă și continuăm cu restul
                removeSubscriber(topic, handler);
            } catch (Exception e) {
                // Orice altă excepție neașteptată
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
}
