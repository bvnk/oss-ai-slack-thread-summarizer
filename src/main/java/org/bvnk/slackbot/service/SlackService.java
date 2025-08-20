package org.bvnk.slackbot.service;

import com.slack.api.Slack;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.request.chat.ChatPostMessageRequest;
import com.slack.api.methods.request.conversations.ConversationsRepliesRequest;
import com.slack.api.methods.request.reactions.ReactionsAddRequest;
import com.slack.api.methods.request.reactions.ReactionsRemoveRequest;
import com.slack.api.methods.request.users.UsersInfoRequest;
import com.slack.api.methods.response.chat.ChatPostMessageResponse;
import com.slack.api.methods.response.conversations.ConversationsRepliesResponse;
import com.slack.api.methods.response.reactions.ReactionsAddResponse;
import com.slack.api.methods.response.reactions.ReactionsRemoveResponse;
import com.slack.api.methods.response.users.UsersInfoResponse;
import com.slack.api.model.Message;
import com.slack.api.model.User;
import com.slack.api.model.block.SectionBlock;
import com.slack.api.model.block.composition.MarkdownTextObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.bvnk.slackbot.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SlackService {
  private static final Logger logger = LoggerFactory.getLogger(SlackService.class);

  private final MethodsClient slackClient;
  private final String botToken;
  private final Map<String, String> userCache = new ConcurrentHashMap<>();

  public SlackService() {
    AppConfig config = AppConfig.getInstance();
    this.botToken = config.getSlackBotToken();
    this.slackClient = Slack.getInstance().methods(botToken);
  }

  public void addReaction(String channel, String timestamp, String emoji) {
    try {
      ReactionsAddRequest request =
          ReactionsAddRequest.builder().channel(channel).timestamp(timestamp).name(emoji).build();

      ReactionsAddResponse response = slackClient.reactionsAdd(request);

      if (response.isOk()) {
        logger.info("Added reaction {} to message", emoji);
      } else {
        logger.warn("Failed to add reaction: {}", response.getError());
      }
    } catch (Exception e) {
      logger.error("Error adding reaction", e);
    }
  }

  public void removeReaction(String channel, String timestamp, String emoji) {
    try {
      ReactionsRemoveRequest request =
          ReactionsRemoveRequest.builder()
              .channel(channel)
              .timestamp(timestamp)
              .name(emoji)
              .build();

      ReactionsRemoveResponse response = slackClient.reactionsRemove(request);

      if (response.isOk()) {
        logger.info("Removed reaction {} from message", emoji);
      } else {
        logger.warn("Failed to remove reaction: {}", response.getError());
      }
    } catch (Exception e) {
      logger.error("Error removing reaction", e);
    }
  }

  public List<Map<String, Object>> getThreadMessages(String channel, String threadTs) {
    List<Map<String, Object>> messages = new ArrayList<>();

    try {
      ConversationsRepliesRequest request =
          ConversationsRepliesRequest.builder()
              .channel(channel)
              .ts(threadTs)
              .inclusive(true)
              .limit(1000)
              .build();

      ConversationsRepliesResponse response = slackClient.conversationsReplies(request);

      if (response.isOk() && response.getMessages() != null) {
        for (Message message : response.getMessages()) {
          Map<String, Object> msgMap = new HashMap<>();
          msgMap.put("user", message.getUser());
          msgMap.put("text", message.getText());
          msgMap.put("ts", message.getTs());
          msgMap.put("thread_ts", message.getThreadTs());
          messages.add(msgMap);
        }
        logger.info("Retrieved {} thread messages", messages.size());
      } else {
        logger.warn("Failed to get thread messages: {}", response.getError());
      }
    } catch (Exception e) {
      logger.error("Error getting thread messages", e);
    }

    return messages;
  }

  public void postMessage(String channel, String threadTs, String text) {
    try {
      // Create a markdown block for better formatting
      SectionBlock textBlock =
          SectionBlock.builder().text(MarkdownTextObject.builder().text(text).build()).build();

      ChatPostMessageRequest request =
          ChatPostMessageRequest.builder()
              .channel(channel)
              .threadTs(threadTs)
              .blocks(List.of(textBlock))
              .text(text) // Fallback for notifications
              .build();

      ChatPostMessageResponse response = slackClient.chatPostMessage(request);

      if (response.isOk()) {
        logger.info("Posted message to thread");
      } else {
        logger.error("Failed to post message: {}", response.getError());
        throw new RuntimeException("Failed to post message: " + response.getError());
      }
    } catch (Exception e) {
      logger.error("Error posting message", e);
      throw new RuntimeException("Error posting message", e);
    }
  }

  /**
   * Convert thread messages to formatted markdown string for AI context Excludes bot's own messages
   * and the triggering message, formats for readability with actual user names
   */
  public String formatThreadMessagesForAI(
      List<Map<String, Object>> messages, String botUserId, String triggerMessageTs) {
    if (messages == null || messages.isEmpty()) {
      return "";
    }

    logger.info(
        "Formatting {} thread messages for AI context, excluding bot messages and trigger message",
        messages.size());

    return messages.stream()
        .filter(
            msg -> {
              // Exclude bot's own messages
              String userId = (String) msg.get("user");
              String msgTs = (String) msg.get("ts");

              // Exclude the triggering message (the one that mentioned the bot)
              boolean isTriggerMessage = msgTs != null && msgTs.equals(triggerMessageTs);

              if (isTriggerMessage) {
                logger.debug("Excluding trigger message with timestamp: {}", msgTs);
              }
              if (userId != null && userId.equals(botUserId)) {
                logger.debug("Excluding bot's own message");
              }

              return userId != null && !userId.equals(botUserId) && !isTriggerMessage;
            })
        .map(
            msg -> {
              String userId = (String) msg.get("user");
              String text = (String) msg.get("text");
              String ts = (String) msg.get("ts");

              // Get the actual user name
              String userName = getUserDisplayName(userId);

              // Clean up text - replace user mentions with names
              if (text != null) {
                // Replace user mentions with actual names
                text = replaceUserMentionsWithNames(text);
              }

              return String.format("%s: %s", userName, text);
            })
        .collect(Collectors.joining("\n\n"));
  }

  /**
   * Convert thread messages to formatted markdown string for AI context Overloaded method for
   * backward compatibility
   */
  public String formatThreadMessagesForAI(List<Map<String, Object>> messages, String botUserId) {
    return formatThreadMessagesForAI(messages, botUserId, null);
  }

  /**
   * Replace user ID mentions in text with actual user names Converts <@U123456> to the actual
   * user's name
   */
  private String replaceUserMentionsWithNames(String text) {
    if (text == null) {
      return "";
    }

    // Pattern to match user mentions like <@U123456>
    String pattern = "<@([A-Z0-9]+)>";

    // Use a StringBuffer for efficient replacement
    java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
    java.util.regex.Matcher m = p.matcher(text);
    StringBuffer sb = new StringBuffer();

    while (m.find()) {
      String userId = m.group(1);
      String userName = getUserDisplayName(userId);
      // Replace with @username format
      m.appendReplacement(sb, "@" + userName);
    }
    m.appendTail(sb);

    return sb.toString().trim();
  }

  /** Extract the actual question/command from the mention text */
  public String extractQuestionFromMention(String text) {
    if (text == null) {
      return "";
    }

    // First replace user mentions with names (except for the bot mention which we'll remove)
    String withNames = replaceUserMentionsWithNames(text);

    // Remove any remaining @mentions (likely the bot mention)
    String cleaned = withNames.replaceAll("@[\\w]+", "").trim();

    // Remove extra whitespace
    cleaned = cleaned.replaceAll("\\s+", " ");

    return cleaned;
  }

  /** Get bot user ID from the bot token This is needed to filter out bot's own messages */
  public String getBotUserId() {
    try {
      var authTestResponse = slackClient.authTest(r -> r);
      if (authTestResponse.isOk()) {
        return authTestResponse.getUserId();
      }
    } catch (Exception e) {
      logger.error("Failed to get bot user ID", e);
    }
    return null;
  }

  /** Get user's display name from their user ID Uses cache to minimize API calls */
  private String getUserDisplayName(String userId) {
    if (userId == null) {
      return "Unknown User";
    }

    // Check cache first
    if (userCache.containsKey(userId)) {
      return userCache.get(userId);
    }

    try {
      UsersInfoRequest request = UsersInfoRequest.builder().user(userId).build();

      UsersInfoResponse response = slackClient.usersInfo(request);

      if (response.isOk() && response.getUser() != null) {
        User user = response.getUser();
        String displayName = null;

        // Try to get the best display name available
        if (user.getProfile() != null) {
          // Prefer display name
          if (user.getProfile().getDisplayName() != null
              && !user.getProfile().getDisplayName().isEmpty()) {
            displayName = user.getProfile().getDisplayName();
          }
          // Fall back to real name
          else if (user.getProfile().getRealName() != null
              && !user.getProfile().getRealName().isEmpty()) {
            displayName = user.getProfile().getRealName();
          }
        }

        // Fall back to username
        if (displayName == null || displayName.isEmpty()) {
          displayName = user.getName();
        }

        // Cache the result
        userCache.put(userId, displayName);
        return displayName;
      } else {
        logger.warn("Failed to get user info for {}: {}", userId, response.getError());
      }
    } catch (Exception e) {
      logger.error("Error getting user info for " + userId, e);
    }

    // If all else fails, return the user ID
    return userId;
  }
}
