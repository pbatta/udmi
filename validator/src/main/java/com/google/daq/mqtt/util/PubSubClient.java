package com.google.daq.mqtt.util;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.api.client.util.Base64;
import com.google.cloud.pubsub.v1.AckReplyConsumer;
import com.google.cloud.pubsub.v1.MessageReceiver;
import com.google.cloud.pubsub.v1.Subscriber;
import com.google.cloud.pubsub.v1.SubscriptionAdminClient;
import com.google.cloud.pubsub.v1.SubscriptionAdminClient.ListSubscriptionsPagedResponse;
import com.google.protobuf.Timestamp;
import com.google.pubsub.v1.ProjectName;
import com.google.pubsub.v1.ProjectSubscriptionName;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.SeekRequest;
import com.google.pubsub.v1.Subscription;
import io.grpc.LoadBalancerRegistry;
import io.grpc.internal.PickFirstLoadBalancerProvider;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

public class PubSubClient {

  private static final String CONNECT_ERROR_FORMAT = "While connecting to project %s";

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
      .enable(SerializationFeature.INDENT_OUTPUT)
      .setSerializationInclusion(Include.NON_NULL);
  private static final String SUBSCRIPTION_ERROR_FORMAT = "While accessing subscription %s";

  private static final long SUBSCRIPTION_RACE_DELAY_MS = 10000;
  private static final String WAS_BASE_64 = "wasBase64";

  private final AtomicBoolean active = new AtomicBoolean();
  private final BlockingQueue<PubsubMessage> messages = new LinkedBlockingDeque<>();
  private final long startTimeSec = System.currentTimeMillis() / 1000;
  private final String projectId;

  private Subscriber subscriber;

  {
    // Why this needs to be done there is no rhyme or reason.
    LoadBalancerRegistry.getDefaultRegistry().register(new PickFirstLoadBalancerProvider());
  }

  public PubSubClient(String projectId, String name) {
    try {
      this.projectId = projectId;
      ProjectSubscriptionName subscriptionName = ProjectSubscriptionName.of(projectId, name);
      System.out.println("Resetting and connecting to pubsub subscription " + subscriptionName);
      resetSubscription(subscriptionName);
      subscriber = Subscriber.newBuilder(subscriptionName, new MessageProcessor()).build();
      subscriber.startAsync().awaitRunning();
      active.set(true);
    } catch (Exception e) {
      throw new RuntimeException(String.format(CONNECT_ERROR_FORMAT, projectId), e);
    }
  }

  private SeekRequest getCurrentTimeSeekRequest(String subscription) {
    Timestamp timestamp = Timestamp.newBuilder().setSeconds(System.currentTimeMillis()/1000).build();
    return SeekRequest.newBuilder().setSubscription(subscription).setTime(timestamp).build();
  }

  public boolean isActive() {
    return active.get();
  }

  @SuppressWarnings("unchecked")
  public void processMessage(BiConsumer<Map<String, Object>, Map<String, String>> handler) {
    try {
      PubsubMessage message = messages.take();
      long seconds = message.getPublishTime().getSeconds();
      if (seconds < startTimeSec) {
        System.out.println(String.format("Flushing outdated message from %d seconds ago",
            startTimeSec - seconds));
        return;
      }
      Map<String, String> attributes = message.getAttributesMap();
      byte[] rawData = message.getData().toByteArray();
      final String data;
      boolean base64 = rawData[0] != '{';
      if (base64) {
        data = new String(Base64.decodeBase64(rawData));
      } else {
        data = new String(rawData);
      }
      Map<String, Object> asMap;
      try {
        asMap = OBJECT_MAPPER.readValue(data, TreeMap.class);
      } catch (JsonProcessingException e) {
        asMap = new ErrorContainer(e, data);
      }

      attributes = new HashMap<>(attributes);
      attributes.put(WAS_BASE_64, ""+ base64);

      handler.accept(asMap, attributes);
    } catch (Exception e) {
      throw new RuntimeException("Processing pubsub message for " + getSubscriptionId(), e);
    }
  }

  static class ErrorContainer extends TreeMap<String, Object> {
    ErrorContainer(Exception e, String message) {
      put("exception", e.toString());
      put("message", message);
    }
  }

  private void stop() {
    if (subscriber != null) {
      active.set(false);
      subscriber.stopAsync();
    }
  }

  public String getSubscriptionId() {
    return subscriber.getSubscriptionNameString();
  }

  private class MessageProcessor implements MessageReceiver {
    @Override
    public void receiveMessage(PubsubMessage message, AckReplyConsumer consumer) {
      messages.offer(message);
      consumer.ack();
    }
  }

  private void resetSubscription(ProjectSubscriptionName subscriptionName) {
    try (SubscriptionAdminClient subscriptionAdminClient = SubscriptionAdminClient.create()) {
      if (subscriptionExists(subscriptionAdminClient, subscriptionName)) {
        System.out.println("Resetting existing subscription " + subscriptionName);
        subscriptionAdminClient.seek(getCurrentTimeSeekRequest(subscriptionName.toString()));
        Thread.sleep(SUBSCRIPTION_RACE_DELAY_MS);
      } else {
        throw new RuntimeException("Missing subscription for " + subscriptionName);
      }
    } catch (Exception e) {
      throw new RuntimeException(
          String.format(SUBSCRIPTION_ERROR_FORMAT, subscriptionName), e);
    }
  }

  private boolean subscriptionExists(SubscriptionAdminClient subscriptionAdminClient,
      ProjectSubscriptionName subscriptionName) {
    ListSubscriptionsPagedResponse listSubscriptionsPagedResponse = subscriptionAdminClient
        .listSubscriptions(ProjectName.of(projectId));
    for (Subscription subscription : listSubscriptionsPagedResponse.iterateAll()) {
      if (subscription.getName().equals(subscriptionName.toString())) {
        return true;
      }
    }
    return false;
  }
}
