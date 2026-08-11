package com.hunt.otziv.b_bots.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.b_bots.dto.BotDTO;
import com.hunt.otziv.b_bots.service.BotBrowserAccessService;
import com.hunt.otziv.b_bots.service.BotCrudAccessService;
import com.hunt.otziv.b_bots.service.BotService;
import com.hunt.otziv.b_bots.service.StatusBotService;
import com.hunt.otziv.b_bots.utils.BotValidation;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.service.UserService;
import java.security.Principal;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

class BotsControllerCrudAccessTest {

    @Test
    void createChecksFreshAccessBeforeValidationAndMutation() {
        Fixture fixture = fixture();
        BotDTO dto = new BotDTO();
        dto.setPassword("secret");
        BindingResult bindingResult = mock(BindingResult.class);
        when(bindingResult.hasErrors()).thenReturn(false);
        when(fixture.botService.createBot(dto, fixture.authentication)).thenReturn(true);

        String result = fixture.controller.saveBot(
                new ExtendedModelMap(),
                dto,
                bindingResult,
                fixture.principal,
                new RedirectAttributesModelMap(),
                fixture.authentication
        );

        assertThat(result).isEqualTo("redirect:/bots/bot_add");
        InOrder order = inOrder(fixture.crudAccess, fixture.botValidation, fixture.botService);
        order.verify(fixture.crudAccess).requireCreateAccess(fixture.authentication);
        order.verify(fixture.botValidation).validate(dto, bindingResult);
        order.verify(fixture.botService).createBot(dto, fixture.authentication);
    }

    @Test
    void createRejectsBlankPasswordButEditCanLeaveItBlank() {
        Fixture fixture = fixture();
        BotDTO dto = new BotDTO();
        dto.setPassword("  ");
        BindingResult bindingResult = new BeanPropertyBindingResult(dto, "bot");

        String result = fixture.controller.saveBot(
                new ExtendedModelMap(),
                dto,
                bindingResult,
                fixture.principal,
                new RedirectAttributesModelMap(),
                fixture.authentication
        );

        assertThat(result).isEqualTo("bots/bot_add");
        assertThat(bindingResult.getFieldError("password")).isNotNull();
        verify(fixture.botService, never()).createBot(dto, fixture.authentication);
    }

    @Test
    void editChecksFreshObjectAccessBeforeLoadingSecretBotData() {
        Fixture fixture = fixture();
        BotDTO dto = new BotDTO();
        User user = new User();
        user.setWorkers(Set.of());
        when(fixture.botService.findById(42L, fixture.authentication)).thenReturn(dto);
        when(fixture.userService.findByUserNameWithAssignments("worker"))
                .thenReturn(Optional.of(user));

        String view = fixture.controller.editBot(
                42L,
                new ExtendedModelMap(),
                new BotDTO(),
                fixture.principal,
                fixture.authentication
        );

        assertThat(view).isEqualTo("bots/bot_edit");
        verify(fixture.botService).findById(42L, fixture.authentication);
    }

    @Test
    void deniedWorkerNeverLoadsOrMutatesAnotherWorkersBot() {
        Fixture fixture = fixture();
        ResponseStatusException notFound = new ResponseStatusException(HttpStatus.NOT_FOUND, "Ресурс не найден");
        when(fixture.botService.findById(42L, fixture.authentication)).thenThrow(notFound);

        assertThatThrownBy(() -> fixture.controller.editBot(
                42L,
                new ExtendedModelMap(),
                new BotDTO(),
                fixture.principal,
                fixture.authentication
        )).isSameAs(notFound);

        verify(fixture.crudAccess, never()).requireAccess(42L, fixture.authentication);
        verify(fixture.userService, never()).findByUserNameWithAssignments("worker");
    }

    @Test
    void updateAndDeleteCarryWorkerConstraintIntoLockedMutation() {
        Fixture fixture = fixture();
        BotCrudAccessService.AuthorizedCrudBot authorized =
                new BotCrudAccessService.AuthorizedCrudBot(42L, 7L, true);
        BotDTO dto = new BotDTO();
        BindingResult bindingResult = mock(BindingResult.class);
        when(fixture.crudAccess.requireAccess(42L, fixture.authentication)).thenReturn(authorized);
        when(bindingResult.hasErrors()).thenReturn(false);
        when(fixture.botService.updateBot(dto, 42L, fixture.authentication)).thenReturn(true);

        String updateResult = fixture.controller.updateBot(
                42L,
                new ExtendedModelMap(),
                dto,
                bindingResult,
                fixture.principal,
                new RedirectAttributesModelMap(),
                fixture.authentication
        );
        String deleteResult = fixture.controller.deleteBot(
                42L,
                new ExtendedModelMap(),
                dto,
                fixture.authentication
        );

        assertThat(updateResult).isEqualTo("redirect:/worker/bot");
        assertThat(deleteResult).isEqualTo("redirect:/worker/bot_list");
        verify(fixture.crudAccess).requireUpdateOwnership(authorized, dto);
        verify(fixture.botService).updateBot(dto, 42L, fixture.authentication);
        verify(fixture.botService).deleteBot(42L, fixture.authentication);
    }

    private Fixture fixture() {
        BotService botService = mock(BotService.class);
        UserService userService = mock(UserService.class);
        StatusBotService statusBotService = mock(StatusBotService.class);
        BotCrudAccessService crudAccess = mock(BotCrudAccessService.class);
        BotValidation botValidation = mock(BotValidation.class);
        Principal principal = () -> "worker";
        Authentication authentication = mock(Authentication.class);
        BotsController controller = new BotsController(
                botService,
                userService,
                statusBotService,
                botValidation,
                mock(BotBrowserAccessService.class),
                crudAccess
        );
        return new Fixture(
                controller,
                botService,
                userService,
                crudAccess,
                botValidation,
                principal,
                authentication
        );
    }

    private record Fixture(
            BotsController controller,
            BotService botService,
            UserService userService,
            BotCrudAccessService crudAccess,
            BotValidation botValidation,
            Principal principal,
            Authentication authentication
    ) {
    }
}
