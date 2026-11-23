package com.hunt.otziv.archive.controller;

import com.hunt.otziv.archive.dto.ArchiveRecoveryWorkerOption;
import com.hunt.otziv.archive.dto.ArchiveRestoreSpecialistRequiredResponse;
import com.hunt.otziv.archive.exception.ArchiveRestoreConflictException;
import com.hunt.otziv.archive.exception.ArchiveRestoreSpecialistRequiredException;
import com.hunt.otziv.archive.service.ManagerArchiveService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiManagerArchiveControllerTest {
    private final ManagerArchiveService service = mock(ManagerArchiveService.class);
    private final ApiManagerArchiveController controller = new ApiManagerArchiveController(service);
    private final TestingAuthenticationToken admin = new TestingAuthenticationToken("admin", "unused", "ROLE_ADMIN");

    @Test
    void restorePassesTheChosenSpecialistToTheService() {
        controller.restoreOrder(10L, "Новый", true, 72L, true, admin, admin);

        verify(service).restoreOrder(10L, "Новый", 72L, true, true, admin, admin);
    }

    @Test
    void aClientThatCannotChooseASpecialistRestoresAsBefore() {
        // Android 1.0.93 and earlier send neither specialistChoice nor targetWorkerId; the service refuses only the
        // orders whose archived specialist no longer works (OrderArchiveRestoreService.needsSpecialistChoice).
        controller.restoreOrder(10L, "Новый", true, null, false, admin, admin);

        verify(service).restoreOrder(10L, "Новый", null, false, true, admin, admin);
    }

    @Test
    void specialistRequiredConflictCarriesCodeAndChoicesForTheRestoreWindow() {
        List<ArchiveRecoveryWorkerOption> options = List.of(
                new ArchiveRecoveryWorkerOption(72L, "Новый специалист", true));
        ArchiveRestoreSpecialistRequiredException conflict = new ArchiveRestoreSpecialistRequiredException(
                "Специалист заказа деактивирован — выберите действующего специалиста", options, 72L);
        when(service.restoreOrder(10L, "Новый", null, true, true, admin, admin)).thenThrow(conflict);

        assertThatThrownBy(() -> controller.restoreOrder(10L, "Новый", true, null, true, admin, admin))
                .isSameAs(conflict);

        var response = controller.specialistRequired(conflict);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isEqualTo(new ArchiveRestoreSpecialistRequiredResponse(
                "Специалист заказа деактивирован — выберите действующего специалиста",
                "SPECIALIST_REQUIRED",
                options,
                72L
        ));
    }

    @Test
    void otherRestoreConflictsStayPlainConflicts() {
        when(service.restoreOrder(10L, "Новый", 72L, true, true, admin, admin)).thenThrow(
                new ArchiveRestoreConflictException("Специалиста нельзя изменить: для заказа уже зафиксирован получатель платежа"));

        assertThatThrownBy(() -> controller.restoreOrder(10L, "Новый", true, 72L, true, admin, admin))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getReason()).contains("получатель платежа");
                });
    }
}
