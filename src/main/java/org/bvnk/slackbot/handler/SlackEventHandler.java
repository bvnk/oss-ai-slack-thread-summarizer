package org.bvnk.slackbot.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.bvnk.slackbot.config.AppConfig;
import org.bvnk.slackbot.model.SlackEvent;
import org.bvnk.slackbot.service.DynamoService;
import org.bvnk.slackbot.service.LambdaInvokeService;
import org.bvnk.slackbot.util.SlackSignatureVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SlackEventHandler implements RequestHandler<Object, Object> {
  private static final Logger logger = LoggerFactory.getLogger(SlackEventHandler.class);

  private final ObjectMapper objectMapper;
  private final SlackSignatureVerifier signatureVerifier;
  private final DynamoService dynamoService;
  private final LambdaInvokeService lambdaInvokeService;
  private final AppConfig config;

  public SlackEventHandler() {
    this.config = AppConfig.getInstance();
    this.objectMapper = new ObjectMapper();
    this.objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    this.signatureVerifier = new SlackSignatureVerifier(config.getSlackSigningSecret());
    this.dynamoService = new DynamoService();
    this.lambdaInvokeService = new LambdaInvokeService();
  }

  @Override
  public Object handleRequest(Object input, Context context) {
    // Check if this is an API Gateway request or async invocation
    if (input instanceof Map) {
      Map<String, Object> inputMap = (Map<String, Object>) input;

      // Check if this is an API Gateway event (has httpMethod, headers, body)
      if (inputMap.containsKey("httpMethod")
          && inputMap.containsKey("headers")
          && inputMap.containsKey("body")) {
        // Convert Map to APIGatewayProxyRequestEvent
        APIGatewayProxyRequestEvent request =
            objectMapper.convertValue(inputMap, APIGatewayProxyRequestEvent.class);
        return handleApiGatewayRequest(request, context);
      } else {
        AsyncProcessorHandler asyncHandler = new AsyncProcessorHandler();
        return asyncHandler.handleRequest(inputMap, context);
      }
    }

    // Shouldn't reach here, but handle as API Gateway if it does
    return handleApiGatewayRequest((APIGatewayProxyRequestEvent) input, context);
  }

  private APIGatewayProxyResponseEvent handleApiGatewayRequest(
      APIGatewayProxyRequestEvent request, Context context) {
    logger.info("Received request: {}", request.getPath());

    try {
      // Get headers
      Map<String, String> headers = request.getHeaders();
      if (headers == null) {
        headers = new HashMap<>();
      }

      String signature = headers.get("X-Slack-Signature");
      if (signature == null) signature = headers.get("x-slack-signature");

      String timestamp = headers.get("X-Slack-Request-Timestamp");
      if (timestamp == null) timestamp = headers.get("x-slack-request-timestamp");

      String body = request.getBody();

      // Parse the event first to check if it's a URL verification
      SlackEvent slackEvent = objectMapper.readValue(body, SlackEvent.class);

      // Handle URL verification challenge (these are not signed by Slack)
      if ("url_verification".equals(slackEvent.getType())) {
        logger.info("Handling URL verification challenge");
        Map<String, String> response = new HashMap<>();
        response.put("challenge", slackEvent.getChallenge());
        return createResponse(200, objectMapper.writeValueAsString(response));
      }

      // For all other events, verify Slack signature
      if (!config.getSlackSigningSecret().isEmpty()
          && !signatureVerifier.verifySignature(signature, timestamp, body)) {
        logger.warn("Invalid Slack signature");
        return createResponse(401, "Unauthorized");
      }

      // Handle event callback
      if ("event_callback".equals(slackEvent.getType())) {
        SlackEvent.Event event = slackEvent.getEvent();

        // Only process app_mention events in threads
        if ("app_mention".equals(event.getType()) && event.getThreadTs() != null) {
          String eventId = slackEvent.getEventId();

          // Check for duplicate processing
          if (dynamoService.checkAndSetEventProcessed(eventId)) {
            logger.info("Processing new event: {}", eventId);

            // Prepare async invocation payload
            Map<String, Object> asyncPayload = new HashMap<>();
            asyncPayload.put("action", "process_mention");
            asyncPayload.put("event", slackEvent);

            // Invoke Lambda asynchronously
            lambdaInvokeService.invokeAsync(asyncPayload);
          } else {
            logger.info("Event already processed, skipping: {}", eventId);
          }
        }
      }

      // Always return 200 OK immediately
      return createResponse(200, "OK");

    } catch (Exception e) {
      logger.error("Error processing request", e);
      return createResponse(500, "Internal Server Error");
    }
  }

  private APIGatewayProxyResponseEvent createResponse(int statusCode, String body) {
    APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
    response.setStatusCode(statusCode);
    response.setBody(body);

    Map<String, String> headers = new HashMap<>();
    headers.put("Content-Type", "application/json");
    response.setHeaders(headers);

    return response;
  }
}
