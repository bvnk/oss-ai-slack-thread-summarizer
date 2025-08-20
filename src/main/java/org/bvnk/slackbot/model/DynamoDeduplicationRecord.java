package org.bvnk.slackbot.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DynamoDeduplicationRecord {
  private String eventId;
  private Long processedAt;
  private Long ttl;
  private String status;
}
