package com.hunt.otziv.l_lead.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeadDtoTransfer {
    @NotBlank
    @Size(max = 20)
    @Pattern(regexp = "[+0-9()\\-\\s]{5,20}")
    private String telephoneLead;
    @Size(max = 500)
    private String companyName;
    @Size(max = 100_000)
    private String phones;
    @Size(max = 100_000)
    private String mobilePhones;
    @Size(max = 100_000)
    private String whatsappPhones;
    @Size(max = 100_000)
    private String emails;
    @Size(max = 100_000)
    private String websites;
    @Size(max = 10_000)
    private String vkUrl;
    @Size(max = 10_000)
    private String telegramUrl;
    @Size(max = 100_000)
    private String industries;
    @Size(max = 10_000)
    private String companyType;
    @Size(max = 255)
    private String region;
    @Size(max = 100_000)
    private String address;
    @NotBlank
    @Size(max = 50)
    private String cityLead;
    @Size(max = 2000)
    private String commentsLead;
    @Size(max = 30)
    private String lidStatus;
    @NotNull
    private LocalDate createDate;
    private LocalDateTime updateStatus;
    private LocalDate dateNewTry;

    @Positive
    private Long operatorId;
    @Positive
    private Long managerId;
    @Positive
    private Long marketologId;
    @Positive
    private Long telephoneId;

    private boolean offer;
    private LocalDateTime lastSeen;
}
