package com.hunt.otziv.u_users.dto;

import com.hunt.otziv.u_users.model.User;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OperatorDTO {

    private Long operatorId;

    private Long userId;
}
