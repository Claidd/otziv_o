package com.hunt.otziv.l_lead.dto.api;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Secret-free projection used by the administrative phone list.
 *
 * <p>Only presence flags are selected for provider credentials, so listing
 * phones never materializes or decrypts every stored password.</p>
 */
public record AdminPhoneListRow(
        Long id,
        String number,
        String fio,
        LocalDate birthday,
        int amountAllowed,
        int amountSent,
        int blockTime,
        LocalDateTime timer,
        boolean googleLoginPresent,
        boolean googlePasswordPresent,
        boolean avitoPasswordPresent,
        boolean mailLoginPresent,
        boolean mailPasswordPresent,
        String fotoInstagram,
        boolean active,
        LocalDate createDate,
        LocalDateTime updateStatus,
        Long operatorId,
        String operatorTitle
) {
}
