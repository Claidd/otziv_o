package com.hunt.otziv.u_users.dto;

import lombok.Data;

import java.util.LinkedHashSet;
import java.util.Set;

@Data
public class UpdateUserAssignmentsRequest {

    private Set<Long> managerIds = new LinkedHashSet<>();

    private Set<Long> workerIds = new LinkedHashSet<>();

    private Set<Long> operatorIds = new LinkedHashSet<>();

    private Set<Long> marketologIds = new LinkedHashSet<>();
}
