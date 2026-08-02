package com.hunt.otziv.u_users.utils;

import com.hunt.otziv.u_users.dto.RegistrationUserDTO;
import com.hunt.otziv.u_users.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.validation.BeanPropertyBindingResult;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class UserValidationTest {

    @Test
    void malformedLegacyRegistrationDoesNotThrowOrQueryNullKeys() {
        UserRepository repository = mock(UserRepository.class);
        UserValidation validation = new UserValidation(repository);
        RegistrationUserDTO request = new RegistrationUserDTO();
        BeanPropertyBindingResult errors = new BeanPropertyBindingResult(request, "newUser");

        assertThatCode(() -> validation.validate(request, errors)).doesNotThrowAnyException();
        verifyNoInteractions(repository);
    }
}
