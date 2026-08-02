package com.hunt.otziv.u_users.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class RegisterClientRequestValidationTest {

    @Test
    void acceptsExistingSingleLineProfileFormatsWithinDatabaseLengths() {
        RegisterClientRequest request = validRequest();

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(request)).isEmpty();
        }
    }

    @Test
    void rejectsControlCharactersAndValuesThatExceedDatabaseColumns() {
        RegisterClientRequest request = validRequest();
        request.setUsername("client\r\nFORGED");

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertThat(invalidProperties(validator, request)).contains("username");

            request.setUsername("u".repeat(101));
            request.setEmail("e".repeat(250) + "@x.test");
            request.setFio("ф".repeat(256));
            request.setPhoneNumber("7".repeat(51));
            request.setPassword("p".repeat(257));
            request.setMatchingPassword("p".repeat(257));

            assertThat(invalidProperties(validator, request))
                    .contains(
                            "username",
                            "email",
                            "fio",
                            "phoneNumber",
                            "password",
                            "matchingPassword"
                    );
        }
    }

    private static RegisterClientRequest validRequest() {
        RegisterClientRequest request = new RegisterClientRequest();
        request.setUsername("client-name_01");
        request.setEmail("client@example.test");
        request.setFio("Иван Петров-Сидоров");
        request.setPhoneNumber("+7 (900) 000-00-00");
        request.setPassword("ValidPassword7!");
        request.setMatchingPassword("ValidPassword7!");
        return request;
    }

    private static Set<String> invalidProperties(Validator validator, RegisterClientRequest request) {
        return validator.validate(request).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }
}
