package org.bvnk.slackbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.bvnk.slackbot.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

public class BedrockService {
  private static final Logger logger = LoggerFactory.getLogger(BedrockService.class);
  private static final int MAX_TOKENS = 1000;
  private static final double TEMPERATURE = 0.7;
  private static final int SLACK_MESSAGE_CHAR_LIMIT = 3000; // Slack's message character limit

  private final BedrockRuntimeClient bedrockClient;
  private final String modelId;
  private final ObjectMapper objectMapper;

  public BedrockService() {
    AppConfig config = AppConfig.getInstance();
    this.modelId = config.getBedrockModelId();
    this.objectMapper = new ObjectMapper();
    this.bedrockClient =
        BedrockRuntimeClient.builder().region(Region.of(config.getAwsRegion())).build();
  }

  public String getResponse(String threadContext, String userQuestion) {
    try {
      // Handle special command prompts
      String prompt = buildPrompt(threadContext, userQuestion);

      // Create the request body for Claude
      ObjectNode requestBody = objectMapper.createObjectNode();
      requestBody.put("anthropic_version", "bedrock-2023-05-31");
      requestBody.put("max_tokens", MAX_TOKENS);
      requestBody.put("temperature", TEMPERATURE);

      // Build messages array
      requestBody.putArray("messages").addObject().put("role", "user").put("content", prompt);

      // Add system prompt
      requestBody.put(
          "system",
          "You are a helpful AI assistant analyzing a Slack conversation thread. "
              + "Provide concise, relevant answers based on the thread context. "
              + "Format your responses using Slack markdown where appropriate.");

      String jsonRequest = objectMapper.writeValueAsString(requestBody);

      // Invoke the model
      InvokeModelRequest invokeRequest =
          InvokeModelRequest.builder()
              .modelId(modelId)
              .contentType("application/json")
              .accept("application/json")
              .body(SdkBytes.fromString(jsonRequest, StandardCharsets.UTF_8))
              .build();

      InvokeModelResponse response = bedrockClient.invokeModel(invokeRequest);

      // Parse the response
      String responseBody = response.body().asUtf8String();
      Map<String, Object> responseMap = objectMapper.readValue(responseBody, Map.class);

      // Extract the content from Claude's response
      Object content = responseMap.get("content");
      if (content instanceof List && !((List<?>) content).isEmpty()) {
        Map<String, Object> firstContent = (Map<String, Object>) ((List<?>) content).get(0);
        String rawResponse = (String) firstContent.get("text");
        return formatResponseForSlack(rawResponse);
      }

      return "I couldn't generate a response. Please try again.";

    } catch (Exception e) {
      logger.error("Error getting response from Bedrock", e);
      return "I encountered an error while processing your request. Please try again later.";
    }
  }

  private String buildPrompt(String threadContext, String userQuestion) {
    String lowerQuestion = userQuestion.toLowerCase().trim();

    // Handle special commands with context-aware prompts
    if (lowerQuestion.equals("summarize")) {
      return String.format(
          "Please provide a concise summary of the following Slack thread conversation:\n\n"
              + "Thread Context:\n%s\n\n"
              + "Provide a clear, bullet-point summary of the key topics discussed.",
          threadContext);
    }

    if (lowerQuestion.equals("action-items") || lowerQuestion.contains("action items")) {
      return String.format(
          "Please extract all action items from the following Slack thread:\n\n"
              + "Thread Context:\n%s\n\n"
              + "List all action items, tasks, or commitments mentioned in the conversation. "
              + "Format as a numbered list with the person responsible (if mentioned).",
          threadContext);
    }

    if (lowerQuestion.equals("key-points") || lowerQuestion.contains("key points")) {
      return String.format(
          "Please identify the key discussion points from the following Slack thread:\n\n"
              + "Thread Context:\n%s\n\n"
              + "List the main topics, decisions, and important points discussed. "
              + "Format as bullet points.",
          threadContext);
    }

    // Default question handling
    return String.format(
        "Based on the following Slack thread conversation, please answer the user's question.\n\n"
            + "Thread Context:\n%s\n\n"
            + "User Question: %s\n\n"
            + "Please provide a helpful and relevant response based on the thread context.",
        threadContext, userQuestion);
  }

  /**
   * Format the AI response for Slack display Handles code blocks, truncation, and Slack-specific
   * formatting
   */
  private String formatResponseForSlack(String response) {
    if (response == null) {
      return "I couldn't generate a response.";
    }

    // Convert markdown code blocks to Slack format
    String formatted =
        response
            // Convert triple backtick code blocks
            .replaceAll("```([^`]+)```", "```$1```")
            // Convert inline code
            .replaceAll("`([^`]+)`", "`$1`")
            // Ensure bullet points work in Slack (handle multiline)
            .replaceAll("(?m)^- ", "• ")
            // Convert bold text
            .replaceAll("\\*\\*([^*]+)\\*\\*", "*$1*");

    // Truncate if necessary, preserving complete sentences
    if (formatted.length() > SLACK_MESSAGE_CHAR_LIMIT) {
      formatted = truncateResponse(formatted);
    }

    return formatted;
  }

  /** Truncate response intelligently at sentence boundaries */
  private String truncateResponse(String response) {
    if (response.length() <= SLACK_MESSAGE_CHAR_LIMIT) {
      return response;
    }

    // Try to find a good breaking point
    int cutoff = SLACK_MESSAGE_CHAR_LIMIT - 100; // Leave room for truncation message

    // Look for sentence endings
    int lastPeriod = response.lastIndexOf(".", cutoff);
    int lastNewline = response.lastIndexOf("\n", cutoff);

    int breakPoint = Math.max(lastPeriod, lastNewline);
    if (breakPoint <= 0) {
      breakPoint = cutoff;
    }

    return response.substring(0, breakPoint) + "\n\n_[Response truncated due to length]_";
  }
}
