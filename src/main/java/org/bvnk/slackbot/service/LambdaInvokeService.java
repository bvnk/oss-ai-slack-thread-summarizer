package org.bvnk.slackbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.bvnk.slackbot.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

public class LambdaInvokeService {
  private static final Logger logger = LoggerFactory.getLogger(LambdaInvokeService.class);

  private final LambdaClient lambdaClient;
  private final String functionName;
  private final ObjectMapper objectMapper;

  public LambdaInvokeService() {
    AppConfig config = AppConfig.getInstance();
    this.functionName = config.getLambdaFunctionName();
    this.objectMapper = new ObjectMapper();
    this.lambdaClient = LambdaClient.builder().region(Region.of(config.getAwsRegion())).build();
  }

  public void invokeAsync(Object payload) {
    try {
      String jsonPayload = objectMapper.writeValueAsString(payload);
      logger.info("Invoking Lambda function async: {}", functionName);

      InvokeRequest invokeRequest =
          InvokeRequest.builder()
              .functionName(functionName)
              .invocationType(InvocationType.EVENT)
              .payload(SdkBytes.fromString(jsonPayload, StandardCharsets.UTF_8))
              .build();

      InvokeResponse response = lambdaClient.invoke(invokeRequest);

      if (response.statusCode() == 202) {
        logger.info("Successfully invoked Lambda function async");
      } else {
        logger.error("Failed to invoke Lambda function. Status code: {}", response.statusCode());
      }

    } catch (Exception e) {
      logger.error("Error invoking Lambda function", e);
      throw new RuntimeException("Failed to invoke Lambda function", e);
    }
  }
}
