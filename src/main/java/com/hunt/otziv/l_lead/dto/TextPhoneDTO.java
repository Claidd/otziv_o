package com.hunt.otziv.l_lead.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TextPhoneDTO {
    // Начальный текст
    private String beginText;

    // Текст предложения
    private String offerText;

    // Текст предложения 2
    private String offer2Text;

    // Текст о создании группы
    private String startText;
}
