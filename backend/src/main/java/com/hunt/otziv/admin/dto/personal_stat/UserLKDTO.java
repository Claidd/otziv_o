package com.hunt.otziv.admin.dto.personal_stat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserLKDTO {

    private String username;
    private String role;
    private Long image;
    private int leadCount;
    private int reviewCount;

}
