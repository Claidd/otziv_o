package com.hunt.otziv.u_users.dto;

import com.hunt.otziv.u_users.model.User;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ManagerDTO {

    private Long managerId;

    private User user;

    private String payText;
}
