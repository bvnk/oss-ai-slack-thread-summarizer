package org.bvnk.slackbot.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.bvnk.slackbot.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

public class DynamoService {
  private static final Logger logger = LoggerFactory.getLogger(DynamoService.class);
  private static final int TTL_SECONDS = 300; // 5 minutes

  private final DynamoDbClient dynamoDbClient;
  private final String tableName;

  public DynamoService() {
    AppConfig config = AppConfig.getInstance();
    this.tableName = config.getDynamoTableName();
    this.dynamoDbClient = DynamoDbClient.builder().region(Region.of(config.getAwsRegion())).build();
  }

  public boolean checkAndSetEventProcessed(String eventId) {
    if (eventId == null || eventId.isEmpty()) {
      logger.warn("Event ID is null or empty");
      return false;
    }

    try {
      long now = Instant.now().getEpochSecond();
      long ttl = now + TTL_SECONDS;

      Map<String, AttributeValue> item = new HashMap<>();
      item.put("event_id", AttributeValue.builder().s(eventId).build());
      item.put("processed_at", AttributeValue.builder().n(String.valueOf(now)).build());
      item.put("ttl", AttributeValue.builder().n(String.valueOf(ttl)).build());
      item.put("status", AttributeValue.builder().s("processing").build());

      PutItemRequest request =
          PutItemRequest.builder()
              .tableName(tableName)
              .item(item)
              .conditionExpression("attribute_not_exists(event_id)")
              .build();

      dynamoDbClient.putItem(request);
      logger.info("Successfully marked event as processed: {}", eventId);
      return true;

    } catch (ConditionalCheckFailedException e) {
      logger.info("Event already processed: {}", eventId);
      return false;
    } catch (Exception e) {
      logger.error("Error checking/setting event processed status", e);
      return false;
    }
  }

  public void updateEventStatus(String eventId, String status) {
    try {
      Map<String, AttributeValue> key = new HashMap<>();
      key.put("event_id", AttributeValue.builder().s(eventId).build());

      Map<String, AttributeValueUpdate> updates = new HashMap<>();
      updates.put(
          "status",
          AttributeValueUpdate.builder()
              .value(AttributeValue.builder().s(status).build())
              .action(AttributeAction.PUT)
              .build());

      UpdateItemRequest request =
          UpdateItemRequest.builder()
              .tableName(tableName)
              .key(key)
              .attributeUpdates(updates)
              .build();

      dynamoDbClient.updateItem(request);
      logger.info("Updated event status: {} -> {}", eventId, status);

    } catch (Exception e) {
      logger.error("Error updating event status", e);
    }
  }
}
