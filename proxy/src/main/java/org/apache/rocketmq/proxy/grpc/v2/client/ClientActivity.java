/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.proxy.grpc.v2.client;

import apache.rocketmq.v2.Address;
import apache.rocketmq.v2.AddressScheme;
import apache.rocketmq.v2.ClientType;
import apache.rocketmq.v2.Code;
import apache.rocketmq.v2.Endpoints;
import apache.rocketmq.v2.FilterExpression;
import apache.rocketmq.v2.HeartbeatRequest;
import apache.rocketmq.v2.HeartbeatResponse;
import apache.rocketmq.v2.Metric;
import apache.rocketmq.v2.NotifyClientTerminationRequest;
import apache.rocketmq.v2.NotifyClientTerminationResponse;
import apache.rocketmq.v2.Resource;
import apache.rocketmq.v2.Settings;
import apache.rocketmq.v2.Status;
import apache.rocketmq.v2.SubscriptionEntry;
import apache.rocketmq.v2.TelemetryCommand;
import apache.rocketmq.v2.ThreadStackTrace;
import apache.rocketmq.v2.VerifyMessageResult;
import io.grpc.stub.StreamObserver;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.apache.rocketmq.broker.client.ClientChannelInfo;
import org.apache.rocketmq.broker.client.ConsumerGroupEvent;
import org.apache.rocketmq.broker.client.ConsumerIdsChangeListener;
import org.apache.rocketmq.broker.client.ProducerChangeListener;
import org.apache.rocketmq.broker.client.ProducerGroupEvent;
import org.apache.rocketmq.common.MQVersion;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.filter.FilterAPI;
import org.apache.rocketmq.common.protocol.ResponseCode;
import org.apache.rocketmq.common.protocol.body.CMResult;
import org.apache.rocketmq.common.protocol.body.ConsumeMessageDirectlyResult;
import org.apache.rocketmq.common.protocol.body.ConsumerRunningInfo;
import org.apache.rocketmq.common.protocol.heartbeat.ConsumeType;
import org.apache.rocketmq.common.protocol.heartbeat.MessageModel;
import org.apache.rocketmq.common.protocol.heartbeat.SubscriptionData;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.proxy.common.ProxyContext;
import org.apache.rocketmq.proxy.config.ConfigurationManager;
import org.apache.rocketmq.proxy.config.MetricCollectorMode;
import org.apache.rocketmq.proxy.config.ProxyConfig;
import org.apache.rocketmq.proxy.grpc.v2.AbstractMessingActivity;
import org.apache.rocketmq.proxy.grpc.v2.channel.GrpcChannelManager;
import org.apache.rocketmq.proxy.grpc.v2.channel.GrpcClientChannel;
import org.apache.rocketmq.proxy.grpc.v2.common.GrpcClientSettingsManager;
import org.apache.rocketmq.proxy.grpc.v2.common.GrpcConverter;
import org.apache.rocketmq.proxy.grpc.v2.common.GrpcProxyException;
import org.apache.rocketmq.proxy.grpc.v2.common.ResponseBuilder;
import org.apache.rocketmq.proxy.processor.MessagingProcessor;
import org.apache.rocketmq.proxy.service.relay.ProxyRelayResult;
import org.apache.rocketmq.remoting.protocol.LanguageCode;

public class ClientActivity extends AbstractMessingActivity {

    private static final InternalLogger log = InternalLoggerFactory.getLogger(LoggerName.PROXY_LOGGER_NAME);

    public ClientActivity(MessagingProcessor messagingProcessor,
        GrpcClientSettingsManager grpcClientSettingsManager,
        GrpcChannelManager grpcChannelManager) {
        super(messagingProcessor, grpcClientSettingsManager, grpcChannelManager);
        this.init();
    }

    protected void init() {
        this.messagingProcessor.registerConsumerListener(new ConsumerIdsChangeListenerImpl());
        this.messagingProcessor.registerProducerListener(new ProducerChangeListenerImpl());
    }

    public CompletableFuture<HeartbeatResponse> heartbeat(ProxyContext ctx, HeartbeatRequest request) {
        CompletableFuture<HeartbeatResponse> future = new CompletableFuture<>();

        try {
            String clientId = ctx.getClientID();
            LanguageCode languageCode = LanguageCode.valueOf(ctx.getLanguage());

            Settings clientSettings = grpcClientSettingsManager.getClientSettings(ctx);
            if (clientSettings == null) {
                future.complete(HeartbeatResponse.newBuilder()
                    .setStatus(ResponseBuilder.getInstance().buildStatus(Code.UNRECOGNIZED_CLIENT_TYPE, "cannot find client settings for this client"))
                    .build());
                return future;
            }
            switch (clientSettings.getClientType()) {
                case PRODUCER: {
                    for (Resource topic : clientSettings.getPublishing().getTopicsList()) {
                        String topicName = GrpcConverter.getInstance().wrapResourceWithNamespace(topic);
                        this.registerProducer(ctx, topicName);
                    }
                    break;
                }
                case PUSH_CONSUMER:
                case SIMPLE_CONSUMER: {
                    validateConsumerGroup(request.getGroup());
                    String consumerGroup = GrpcConverter.getInstance().wrapResourceWithNamespace(request.getGroup());
                    this.registerConsumer(ctx, consumerGroup, clientSettings.getClientType(), clientSettings.getSubscription().getSubscriptionsList());
                    break;
                }
                default: {
                    future.complete(HeartbeatResponse.newBuilder()
                        .setStatus(ResponseBuilder.getInstance().buildStatus(Code.UNRECOGNIZED_CLIENT_TYPE, clientSettings.getClientType().name()))
                        .build());
                    return future;
                }
            }
            future.complete(HeartbeatResponse.newBuilder()
                .setStatus(ResponseBuilder.getInstance().buildStatus(Code.OK, Code.OK.name()))
                .build());
            return future;
        } catch (Throwable t) {
            future.completeExceptionally(t);
        }
        return future;
    }

    public CompletableFuture<NotifyClientTerminationResponse> notifyClientTermination(ProxyContext ctx,
        NotifyClientTerminationRequest request) {
        CompletableFuture<NotifyClientTerminationResponse> future = new CompletableFuture<>();

        try {
            String clientId = ctx.getClientID();
            LanguageCode languageCode = LanguageCode.valueOf(ctx.getLanguage());
            Settings clientSettings = grpcClientSettingsManager.removeAndGetClientSettings(ctx);

            switch (clientSettings.getClientType()) {
                case PRODUCER:
                    for (Resource topic : clientSettings.getPublishing().getTopicsList()) {
                        String topicName = GrpcConverter.getInstance().wrapResourceWithNamespace(topic);
                        // user topic name as producer group
                        GrpcClientChannel channel = this.grpcChannelManager.removeChannel(topicName, clientId);
                        if (channel != null) {
                            ClientChannelInfo clientChannelInfo = new ClientChannelInfo(channel, clientId, languageCode, MQVersion.Version.V5_0_0.ordinal());
                            this.messagingProcessor.unRegisterProducer(ctx, topicName, clientChannelInfo);
                        }
                    }
                    break;
                case PUSH_CONSUMER:
                case SIMPLE_CONSUMER:
                    validateConsumerGroup(request.getGroup());
                    String consumerGroup = GrpcConverter.getInstance().wrapResourceWithNamespace(request.getGroup());
                    GrpcClientChannel channel = this.grpcChannelManager.removeChannel(consumerGroup, clientId);
                    if (channel != null) {
                        ClientChannelInfo clientChannelInfo = new ClientChannelInfo(channel, clientId, languageCode, MQVersion.Version.V5_0_0.ordinal());
                        this.messagingProcessor.unRegisterConsumer(ctx, consumerGroup, clientChannelInfo);
                    }
                    break;
                default:
                    future.complete(NotifyClientTerminationResponse.newBuilder()
                        .setStatus(ResponseBuilder.getInstance().buildStatus(Code.UNRECOGNIZED_CLIENT_TYPE, clientSettings.getClientType().name()))
                        .build());
                    return future;
            }
            future.complete(NotifyClientTerminationResponse.newBuilder()
                .setStatus(ResponseBuilder.getInstance().buildStatus(Code.OK, Code.OK.name()))
                .build());
        } catch (Throwable t) {
            future.completeExceptionally(t);
        }
        return future;
    }

    public StreamObserver<TelemetryCommand> telemetry(ProxyContext ctx,
        StreamObserver<TelemetryCommand> responseObserver) {
        return new StreamObserver<TelemetryCommand>() {
            @Override
            public void onNext(TelemetryCommand request) {
                try {
                    switch (request.getCommandCase()) {
                        case SETTINGS: {
                            responseObserver.onNext(processClientSettings(ctx, request, responseObserver));
                            break;
                        }
                        case THREAD_STACK_TRACE: {
                            reportThreadStackTrace(ctx, request.getStatus(), request.getThreadStackTrace());
                            break;
                        }
                        case VERIFY_MESSAGE_RESULT: {
                            reportVerifyMessageResult(ctx, request.getStatus(), request.getVerifyMessageResult());
                            break;
                        }
                    }
                } catch (Throwable t) {
                    responseObserver.onNext(convertToTelemetryCommand(t));
                }
            }

            @Override
            public void onError(Throwable t) {
                log.error("telemetry on error", t);
            }

            @Override
            public void onCompleted() {
                responseObserver.onCompleted();
            }
        };
    }

    protected TelemetryCommand convertToTelemetryCommand(Throwable t) {
        return TelemetryCommand.newBuilder().setStatus(ResponseBuilder.getInstance().buildStatus(t)).build();
    }

    protected TelemetryCommand processClientSettings(ProxyContext ctx, TelemetryCommand request,
        StreamObserver<TelemetryCommand> responseObserver) {
        String clientId = ctx.getClientID();
        Settings settings = request.getSettings();
        // Construct metric according to the proxy config
        final ProxyConfig proxyConfig = ConfigurationManager.getProxyConfig();
        final MetricCollectorMode metricCollectorMode =
            MetricCollectorMode.getEnumByOrdinal(proxyConfig.getMetricCollectorMode());
        final String metricCollectorAddress = proxyConfig.getMetricCollectorAddress();
        final Metric.Builder metricBuilder = Metric.newBuilder();
        switch (metricCollectorMode) {
            case ON:
                final String[] split = metricCollectorAddress.split(":");
                final String host = split[0];
                final int port = Integer.parseInt(split[1]);
                Address address = Address.newBuilder().setHost(host).setPort(port).build();
                final Endpoints endpoints = Endpoints.newBuilder().setScheme(AddressScheme.IPv4)
                    .addAddresses(address).build();
                metricBuilder.setOn(true).setEndpoints(endpoints);
                break;
            case PROXY:
                metricBuilder.setOn(true).setEndpoints(settings.getAccessPoint());
            case OFF:
            default:
                metricBuilder.setOn(false);
                break;
        }
        Metric metric = metricBuilder.build();
        settings = settings.toBuilder().setMetric(metric).build();
        if (settings.hasPublishing()) {
            for (Resource topic : settings.getPublishing().getTopicsList()) {
                validateTopic(topic);
                String topicName = GrpcConverter.getInstance().wrapResourceWithNamespace(topic);
                GrpcClientChannel producerChannel = registerProducer(ctx, topicName);
                producerChannel.setClientObserver(responseObserver);
            }
        }
        if (settings.hasSubscription()) {
            validateConsumerGroup(settings.getSubscription().getGroup());
            String groupName = GrpcConverter.getInstance().wrapResourceWithNamespace(settings.getSubscription().getGroup());
            GrpcClientChannel consumerChannel = registerConsumer(ctx, groupName, settings.getClientType(), settings.getSubscription().getSubscriptionsList());
            consumerChannel.setClientObserver(responseObserver);
        }

        grpcClientSettingsManager.updateClientSettings(clientId, request.getSettings());
        settings = grpcClientSettingsManager.getClientSettings(ctx);
        return TelemetryCommand.newBuilder()
            .setStatus(ResponseBuilder.getInstance().buildStatus(Code.OK, Code.OK.name()))
            .setSettings(settings)
            .build();
    }

    protected GrpcClientChannel registerProducer(ProxyContext ctx, String topicName) {
        String clientId = ctx.getClientID();
        LanguageCode languageCode = LanguageCode.valueOf(ctx.getLanguage());

        GrpcClientChannel channel = this.grpcChannelManager.createChannel(ctx, topicName, clientId);
        // use topic name as producer group
        ClientChannelInfo clientChannelInfo = new ClientChannelInfo(channel, clientId, languageCode, MQVersion.Version.V5_0_0.ordinal());
        this.messagingProcessor.registerProducer(ctx, topicName, clientChannelInfo);
        this.messagingProcessor.addTransactionSubscription(ctx, topicName, topicName);
        return channel;
    }

    protected GrpcClientChannel registerConsumer(ProxyContext ctx, String consumerGroup, ClientType clientType, List<SubscriptionEntry> subscriptionEntryList) {
        String clientId = ctx.getClientID();
        LanguageCode languageCode = LanguageCode.valueOf(ctx.getLanguage());

        GrpcClientChannel channel = this.grpcChannelManager.createChannel(ctx, consumerGroup, clientId);
        ClientChannelInfo clientChannelInfo = new ClientChannelInfo(channel, clientId, languageCode, MQVersion.Version.V5_0_0.ordinal());

        this.messagingProcessor.registerConsumer(
            ctx,
            consumerGroup,
            clientChannelInfo,
            this.buildConsumeType(clientType),
            MessageModel.CLUSTERING,
            ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET,
            this.buildSubscriptionDataSet(subscriptionEntryList)
        );
        return channel;
    }

    protected void reportThreadStackTrace(ProxyContext ctx, Status status, ThreadStackTrace request) {
        String nonce = request.getNonce();
        String threadStack = request.getThreadStackTrace();
        CompletableFuture<ProxyRelayResult<ConsumerRunningInfo>> responseFuture = this.grpcChannelManager.getAndRemoveResponseFuture(nonce);
        if (responseFuture != null) {
            try {
                if (status.getCode().equals(Code.OK)) {
                    ConsumerRunningInfo runningInfo = new ConsumerRunningInfo();
                    runningInfo.setJstack(threadStack);
                    responseFuture.complete(new ProxyRelayResult<>(ResponseCode.SUCCESS, "", runningInfo));
                } else if (status.getCode().equals(Code.VERIFY_FIFO_MESSAGE_UNSUPPORTED)) {
                    responseFuture.complete(new ProxyRelayResult<>(ResponseCode.NO_PERMISSION, "forbidden to verify message", null));
                } else {
                    responseFuture.complete(new ProxyRelayResult<>(ResponseCode.SYSTEM_ERROR, "verify message failed", null));
                }
            } catch (Throwable t) {
                responseFuture.completeExceptionally(t);
            }
        }
    }

    protected void reportVerifyMessageResult(ProxyContext ctx, Status status, VerifyMessageResult request) {
        String nonce = request.getNonce();
        CompletableFuture<ProxyRelayResult<ConsumeMessageDirectlyResult>> responseFuture = this.grpcChannelManager.getAndRemoveResponseFuture(nonce);
        if (responseFuture != null) {
            try {
                ConsumeMessageDirectlyResult result = this.buildConsumeMessageDirectlyResult(status, request);
                responseFuture.complete(new ProxyRelayResult<>(ResponseCode.SUCCESS, "", result));
            } catch (Throwable t) {
                responseFuture.completeExceptionally(t);
            }
        }
    }

    protected ConsumeMessageDirectlyResult buildConsumeMessageDirectlyResult(Status status,
        VerifyMessageResult request) {
        ConsumeMessageDirectlyResult consumeMessageDirectlyResult = new ConsumeMessageDirectlyResult();
        switch (status.getCode().getNumber()) {
            case Code.OK_VALUE: {
                consumeMessageDirectlyResult.setConsumeResult(CMResult.CR_SUCCESS);
                break;
            }
            case Code.FAILED_TO_CONSUME_MESSAGE_VALUE: {
                consumeMessageDirectlyResult.setConsumeResult(CMResult.CR_LATER);
                break;
            }
            case Code.MESSAGE_CORRUPTED_VALUE: {
                consumeMessageDirectlyResult.setConsumeResult(CMResult.CR_RETURN_NULL);
                break;
            }
        }
        consumeMessageDirectlyResult.setRemark("from gRPC client");
        return consumeMessageDirectlyResult;
    }

    protected ConsumeType buildConsumeType(ClientType clientType) {
        switch (clientType) {
            case SIMPLE_CONSUMER:
                return ConsumeType.CONSUME_ACTIVELY;
            case PUSH_CONSUMER:
                return ConsumeType.CONSUME_PASSIVELY;
            default:
                throw new IllegalArgumentException("Client type is not consumer, type: " + clientType);
        }
    }

    protected Set<SubscriptionData> buildSubscriptionDataSet(List<SubscriptionEntry> subscriptionEntryList) {
        Set<SubscriptionData> subscriptionDataSet = new HashSet<>();
        for (SubscriptionEntry sub : subscriptionEntryList) {
            String topicName = GrpcConverter.getInstance().wrapResourceWithNamespace(sub.getTopic());
            FilterExpression filterExpression = sub.getExpression();
            subscriptionDataSet.add(buildSubscriptionData(topicName, filterExpression));
        }
        return subscriptionDataSet;
    }

    protected SubscriptionData buildSubscriptionData(String topicName, FilterExpression filterExpression) {
        String expression = filterExpression.getExpression();
        String expressionType = GrpcConverter.getInstance().buildExpressionType(filterExpression.getType());
        try {
            return FilterAPI.build(topicName, expression, expressionType);
        } catch (Exception e) {
            throw new GrpcProxyException(Code.ILLEGAL_FILTER_EXPRESSION, "expression format is not correct", e);
        }
    }

    protected class ConsumerIdsChangeListenerImpl implements ConsumerIdsChangeListener {

        @Override
        public void handle(ConsumerGroupEvent event, String group, Object... args) {
            if (event == ConsumerGroupEvent.CLIENT_UNREGISTER) {
                if (args == null || args.length < 1) {
                    return;
                }
                if (args[0] instanceof ClientChannelInfo) {
                    ClientChannelInfo clientChannelInfo = (ClientChannelInfo) args[0];
                    grpcChannelManager.removeChannel(group, clientChannelInfo.getClientId());
                    grpcClientSettingsManager.removeClientSettings(clientChannelInfo.getClientId());
                }
            }
        }

        @Override
        public void shutdown() {

        }
    }

    protected class ProducerChangeListenerImpl implements ProducerChangeListener {

        @Override
        public void handle(ProducerGroupEvent event, String group, ClientChannelInfo clientChannelInfo) {
            if (event == ProducerGroupEvent.CLIENT_UNREGISTER) {
                grpcChannelManager.removeChannel(group, clientChannelInfo.getClientId());
                grpcClientSettingsManager.removeClientSettings(clientChannelInfo.getClientId());
            }
        }
    }
}
