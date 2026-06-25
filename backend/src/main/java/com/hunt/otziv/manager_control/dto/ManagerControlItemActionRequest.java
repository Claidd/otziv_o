package com.hunt.otziv.manager_control.dto;

public record ManagerControlItemActionRequest(
        String actionType,
        String comment,
        Boolean manualWorkerNotification
) {
}
