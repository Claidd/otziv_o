package com.hunt.otziv.admin.dto.personal;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hunt.otziv.u_users.api.UserImageLinks;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OperatorsListDTO {
    private Long id;
    private Long userId;
    private String fio;
    private String login;
    private Long imageId;
    private int sum1Month;
    private Long sum1MonthKopecks;
    private int order1Month;
    private int review1Month;
    private Long leadsNew;
    private Long leadsInWork;
    private Long percentInWork;
//    private BigDecimal coefficient;
//    private BigDecimal sum1Day;
//    private BigDecimal sum1Week;
//    private BigDecimal sum1Month;

    /** Short-lived signed link to the photo, relative to the backend base URL. */
    @JsonProperty(value = "imageUrl", access = JsonProperty.Access.READ_ONLY)
    public String getImageUrl() {
        return UserImageLinks.pathForOrDefault(imageId);
    }
}
