package org.bvnk.slackbot.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

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