package com.pubsub.broker.net;

import com.pubsub.broker.model.Message;
import java.io.IOException;

public interface ConnectionHandler {
    void send(Message message) throws IOException;

    /** Forcibly disconnects this client (used when an admin deletes a topic or a subscriber). */
    void close();
}
