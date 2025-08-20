package org.bvnk.slackbot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SlackEvent {
  private String type;
  private String token;
  private String challenge;

  @JsonProperty("team_id")
  private String teamId;

  @JsonProperty("api_app_id")
  private String apiAppId;

  private Event event;

  @JsonProperty("event_id")
  private String eventId;

  @JsonProperty("event_time")
  private Long eventTime;

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Event {
    private String type;
    private String subtype;
    private String user;
    private String text;
    private String ts;
    private String channel;

    @JsonProperty("thread_ts")
    private String threadTs;

    @JsonProperty("parent_user_id")
    private String parentUserId;

    @JsonProperty("event_ts")
    private String eventTs;

    @JsonProperty("channel_type")
    private String channelType;

    private List<Block> blocks;
    private Map<String, Object> metadata;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Block {
    private String type;
    private Map<String, Object> text;
    private List<Map<String, Object>> elements;
  }
}
