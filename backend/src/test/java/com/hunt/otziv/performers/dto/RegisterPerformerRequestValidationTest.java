package com.hunt.otziv.performers.dto;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RegisterPerformerRequestValidationTest {

    @Test
    void boundsAndValidatesPublicPerformerRegistrationFields() {
        RegisterPerformerRequest valid = new RegisterPerformerRequest();
        valid.setPhoneNumber("+7 (900) 123-45-67");
        valid.setCityId(1L);
        valid.setFio("Иван Петров");
        valid.setTelegramUsername("@performer_1");
        valid.setRegisteredSource("landing");

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(valid)).isEmpty();

            valid.setPhoneNumber("123");
            valid.setCityId(0L);
            valid.setFio("x\r\nforged");
            valid.setTelegramUsername("https://example.test/not-a-username");
            assertThat(factory.getValidator().validate(valid))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("phoneNumber", "cityId", "fio", "telegramUsername");

            valid.setPhoneNumber("1234567890123456");
            assertThat(factory.getValidator().validate(valid))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("phoneNumber");
        }
    }
}
