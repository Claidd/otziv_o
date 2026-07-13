package com.hunt.otziv.review_recovery.model;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class ReviewRecoveryBotExclusionId implements Serializable {
    private Long taskId;
    private Long botId;
}
