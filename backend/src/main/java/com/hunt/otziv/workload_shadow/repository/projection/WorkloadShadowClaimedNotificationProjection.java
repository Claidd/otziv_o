package com.hunt.otziv.workload_shadow.repository.projection;

public interface WorkloadShadowClaimedNotificationProjection {

    Long getId();

    String getSeverity();

    String getEventType();

    Long getManagerId();

    String getTitle();

    String getMessage();

    String getTargetGroupType();

    Long getTargetGroupChatId();

    Integer getDeliveryAttempts();
}
