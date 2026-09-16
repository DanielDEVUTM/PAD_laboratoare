package com.pubsub.broker.grpc;

import com.pubsub.broker.net.ConnectionHandler;
import com.pubsub.broker.util.JsonUtil;
import io.grpc.stub.StreamObserver;
import pubsub.Message;

import java.io.IOException;

public class GrpcConnectionHandler implements ConnectionHandler {
    private final StreamObserver<Message> responseObserver;

    public GrpcConnectionHandler(StreamObserver<Message> responseObserver) {
        this.responseObserver = responseObserver;
    }

    @Override
    public void send(com.pubsub.broker.model.Message domainMessage) throws IOException {
        try {
            String payloadStr;
            if (domainMessage.getPayload() instanceof String s) {
                payloadStr = s;
            } else {
                payloadStr = JsonUtil.toJson(domainMessage.getPayload());
            }

            Message grpcMsg = Message.newBuilder()
                    .setId(domainMessage.getId() != null ? domainMessage.getId() : "")
                    .setTopic(domainMessage.getTopic() != null ? domainMessage.getTopic() : "")
                    .setPayload(payloadStr != null ? payloadStr : "")
                    .setTimestamp(domainMessage.getTimestamp() != null ? domainMessage.getTimestamp() : "")
                    .build();

            synchronized (responseObserver) {
                responseObserver.onNext(grpcMsg);
            }
        } catch (Exception e) {
            throw new IOException("Failed to send gRPC message: " + e.getMessage(), e);
        }
    }

    public StreamObserver<Message> getResponseObserver() {
        return responseObserver;
    }
}
