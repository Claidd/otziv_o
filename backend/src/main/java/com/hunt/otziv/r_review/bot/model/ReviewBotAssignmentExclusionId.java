package com.hunt.otziv.r_review.bot.model;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class ReviewBotAssignmentExclusionId implements Serializable {
    private Long reviewId;
    private Long botId;
}
