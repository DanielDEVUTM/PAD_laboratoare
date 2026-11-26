package com.pubsub.broker.grpc;

import com.pubsub.broker.registry.TopicRegistry;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import pubsub.Ack;
import pubsub.BrokerGrpc;
import pubsub.Message;
import pubsub.SubRequest;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class GrpcBrokerServiceImpl extends BrokerGrpc.BrokerImplBase {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private final TopicRegistry topicRegistry;

    public GrpcBrokerServiceImpl(TopicRegistry topicRegistry) {
        this.topicRegistry = topicRegistry;
    }

    private void log(String msg) {
        String timestamp = LocalDateTime.now().format(FORMATTER);
        System.out.println("[" + timestamp + "] [gRPC] " + msg);
    }

    @Override
    public void publish(Message request, StreamObserver<Ack> responseObserver) {
        if (request.getTopic() == null || request.getTopic().isEmpty()) {
            responseObserver.onNext(Ack.newBuilder()
                    .setSuccess(false)
                    .setDetail("Topic-ul nu poate fi gol")
                    .build());
            responseObserver.onCompleted();
            return;
        }

        log("Mesaj gRPC publicat pe topicul: " + request.getTopic());

        // Convertim din pubsub.Message (gRPC) în com.pubsub.broker.model.Message (Domeniu)
        com.pubsub.broker.model.Message domainMsg = new com.pubsub.broker.model.Message(
                request.getId(),
                request.getTopic(),
                request.getPayload(),
                request.getTimestamp()
        );

        topicRegistry.broadcast(request.getTopic(), domainMsg);

        responseObserver.onNext(Ack.newBuilder()
                .setSuccess(true)
                .setDetail("OK")
                .build());
        responseObserver.onCompleted();
    }

    @Override
    public void subscribe(SubRequest request, StreamObserver<Message> responseObserver) {
        String topic = request.getTopic();
        log("Subscriber gRPC nou conectat pe topicul: " + topic);

        GrpcConnectionHandler handler = new GrpcConnectionHandler(responseObserver);
        topicRegistry.addSubscriber(topic, handler);

        if (responseObserver instanceof ServerCallStreamObserver<Message> serverCallStreamObserver) {
            serverCallStreamObserver.setOnCancelHandler(() -> {
                log("Subscriber gRPC deconectat (cancel) de pe topicul: " + topic);
                topicRegistry.removeSubscriber(topic, handler);
            });
        }
    }
}
