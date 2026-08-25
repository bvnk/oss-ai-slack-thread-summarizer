package org.bvnk.slackbot.config;

import lombok.Getter;

@Getter
public class AppConfig {
  private final String slackSigningSecret;
  private final String slackBotToken;
  private final String dynamoTableName;
  private final String lambdaFunctionName;
  private final String bedrockModelId;
  private final String awsRegion;

  private static final AppConfig INSTANCE = new AppConfig();

  private AppConfig() {
    this.slackSigningSecret = getEnvOrDefault("SLACK_SIGNING_SECRET", "");
    this.slackBotToken = getEnvOrDefault("SLACK_BOT_TOKEN", "");
    this.dynamoTableName = getEnvOrDefault("DYNAMO_TABLE", "slack-event-deduplication");
    this.lambdaFunctionName = getEnvOrDefault("AWS_LAMBDA_FUNCTION_NAME", "");
    this.bedrockModelId = getEnvOrDefault("BEDROCK_MODEL_ID", "eu.anthropic.claude-sonnet-5");
    this.awsRegion = getEnvOrDefault("AWS_REGION", "eu-west-1");
  }

  public static AppConfig getInstance() {
    return INSTANCE;
  }

  private String getEnvOrDefault(String key, String defaultValue) {
    String value = System.getenv(key);
    return value != null && !value.isEmpty() ? value : defaultValue;
  }
}
