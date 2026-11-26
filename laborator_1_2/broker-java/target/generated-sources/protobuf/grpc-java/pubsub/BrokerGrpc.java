package pubsub;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.62.2)",
    comments = "Source: broker.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class BrokerGrpc {

  private BrokerGrpc() {}

  public static final java.lang.String SERVICE_NAME = "pubsub.Broker";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<pubsub.Message,
      pubsub.Ack> getPublishMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Publish",
      requestType = pubsub.Message.class,
      responseType = pubsub.Ack.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<pubsub.Message,
      pubsub.Ack> getPublishMethod() {
    io.grpc.MethodDescriptor<pubsub.Message, pubsub.Ack> getPublishMethod;
    if ((getPublishMethod = BrokerGrpc.getPublishMethod) == null) {
      synchronized (BrokerGrpc.class) {
        if ((getPublishMethod = BrokerGrpc.getPublishMethod) == null) {
          BrokerGrpc.getPublishMethod = getPublishMethod =
              io.grpc.MethodDescriptor.<pubsub.Message, pubsub.Ack>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Publish"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  pubsub.Message.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  pubsub.Ack.getDefaultInstance()))
              .setSchemaDescriptor(new BrokerMethodDescriptorSupplier("Publish"))
              .build();
        }
      }
    }
    return getPublishMethod;
  }

  private static volatile io.grpc.MethodDescriptor<pubsub.SubRequest,
      pubsub.Message> getSubscribeMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Subscribe",
      requestType = pubsub.SubRequest.class,
      responseType = pubsub.Message.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<pubsub.SubRequest,
      pubsub.Message> getSubscribeMethod() {
    io.grpc.MethodDescriptor<pubsub.SubRequest, pubsub.Message> getSubscribeMethod;
    if ((getSubscribeMethod = BrokerGrpc.getSubscribeMethod) == null) {
      synchronized (BrokerGrpc.class) {
        if ((getSubscribeMethod = BrokerGrpc.getSubscribeMethod) == null) {
          BrokerGrpc.getSubscribeMethod = getSubscribeMethod =
              io.grpc.MethodDescriptor.<pubsub.SubRequest, pubsub.Message>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Subscribe"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  pubsub.SubRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  pubsub.Message.getDefaultInstance()))
              .setSchemaDescriptor(new BrokerMethodDescriptorSupplier("Subscribe"))
              .build();
        }
      }
    }
    return getSubscribeMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static BrokerStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<BrokerStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<BrokerStub>() {
        @java.lang.Override
        public BrokerStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new BrokerStub(channel, callOptions);
        }
      };
    return BrokerStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static BrokerBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<BrokerBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<BrokerBlockingStub>() {
        @java.lang.Override
        public BrokerBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new BrokerBlockingStub(channel, callOptions);
        }
      };
    return BrokerBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static BrokerFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<BrokerFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<BrokerFutureStub>() {
        @java.lang.Override
        public BrokerFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new BrokerFutureStub(channel, callOptions);
        }
      };
    return BrokerFutureStub.newStub(factory, channel);
  }

  /**
   */
  public interface AsyncService {

    /**
     */
    default void publish(pubsub.Message request,
        io.grpc.stub.StreamObserver<pubsub.Ack> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getPublishMethod(), responseObserver);
    }

    /**
     */
    default void subscribe(pubsub.SubRequest request,
        io.grpc.stub.StreamObserver<pubsub.Message> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSubscribeMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service Broker.
   */
  public static abstract class BrokerImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return BrokerGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service Broker.
   */
  public static final class BrokerStub
      extends io.grpc.stub.AbstractAsyncStub<BrokerStub> {
    private BrokerStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected BrokerStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new BrokerStub(channel, callOptions);
    }

    /**
     */
    public void publish(pubsub.Message request,
        io.grpc.stub.StreamObserver<pubsub.Ack> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getPublishMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void subscribe(pubsub.SubRequest request,
        io.grpc.stub.StreamObserver<pubsub.Message> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getSubscribeMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service Broker.
   */
  public static final class BrokerBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<BrokerBlockingStub> {
    private BrokerBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected BrokerBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new BrokerBlockingStub(channel, callOptions);
    }

    /**
     */
    public pubsub.Ack publish(pubsub.Message request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getPublishMethod(), getCallOptions(), request);
    }

    /**
     */
    public java.util.Iterator<pubsub.Message> subscribe(
        pubsub.SubRequest request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getSubscribeMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service Broker.
   */
  public static final class BrokerFutureStub
      extends io.grpc.stub.AbstractFutureStub<BrokerFutureStub> {
    private BrokerFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected BrokerFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new BrokerFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<pubsub.Ack> publish(
        pubsub.Message request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getPublishMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_PUBLISH = 0;
  private static final int METHODID_SUBSCRIBE = 1;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final AsyncService serviceImpl;
    private final int methodId;

    MethodHandlers(AsyncService serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_PUBLISH:
          serviceImpl.publish((pubsub.Message) request,
              (io.grpc.stub.StreamObserver<pubsub.Ack>) responseObserver);
          break;
        case METHODID_SUBSCRIBE:
          serviceImpl.subscribe((pubsub.SubRequest) request,
              (io.grpc.stub.StreamObserver<pubsub.Message>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getPublishMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              pubsub.Message,
              pubsub.Ack>(
                service, METHODID_PUBLISH)))
        .addMethod(
          getSubscribeMethod(),
          io.grpc.stub.ServerCalls.asyncServerStreamingCall(
            new MethodHandlers<
              pubsub.SubRequest,
              pubsub.Message>(
                service, METHODID_SUBSCRIBE)))
        .build();
  }

  private static abstract class BrokerBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    BrokerBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return pubsub.BrokerProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("Broker");
    }
  }

  private static final class BrokerFileDescriptorSupplier
      extends BrokerBaseDescriptorSupplier {
    BrokerFileDescriptorSupplier() {}
  }

  private static final class BrokerMethodDescriptorSupplier
      extends BrokerBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    BrokerMethodDescriptorSupplier(java.lang.String methodName) {
      this.methodName = methodName;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (BrokerGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new BrokerFileDescriptorSupplier())
              .addMethod(getPublishMethod())
              .addMethod(getSubscribeMethod())
              .build();
        }
      }
    }
    return result;
  }
}
