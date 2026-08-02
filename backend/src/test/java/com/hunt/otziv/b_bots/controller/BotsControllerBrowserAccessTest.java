package com.hunt.otziv.b_bots.controller;

import com.hunt.otziv.b_bots.dto.BrowserBotMetadataResponse;
import com.hunt.otziv.b_bots.services.BotBrowserAccessService;
import com.hunt.otziv.b_bots.services.BotCrudAccessService;
import com.hunt.otziv.b_bots.services.BotService;
import com.hunt.otziv.b_bots.services.StatusBotService;
import com.hunt.otziv.b_bots.utils.BotValidation;
import com.hunt.otziv.u_users.services.service.UserService;
import java.lang.reflect.RecordComponent;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BotsControllerBrowserAccessTest {

    @Test
    void legacyBrowserPageUsesFreshObjectGuardAndPasswordFreeMetadata() {
        BotService botService = mock(BotService.class);
        BotBrowserAccessService accessService = mock(BotBrowserAccessService.class);
        Authentication authentication = mock(Authentication.class);
        when(accessService.requireAccess(42L, authentication))
                .thenReturn(new BotBrowserAccessService.AuthorizedBot(42L, "+79990001122", "Test Bot"));

        BotsController controller = controller(botService, accessService);
        ExtendedModelMap model = new ExtendedModelMap();

        assertThat(controller.botBrowserPage(42L, model, authentication))
                .isEqualTo("bots/bot_browser");
        assertThat(model.get("botId")).isEqualTo(42L);
        assertThat(model.get("bot")).isEqualTo(new BrowserBotMetadataResponse(
                42L,
                "+79990001122",
                "Test Bot"
        ));
        assertThat(BrowserBotMetadataResponse.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .doesNotContain("password");
        verify(accessService).requireAccess(42L, authentication);
        verify(botService, never()).findById(42L, authentication);
    }

    @Test
    void legacyBrowserPageDoesNotFallBackToAdminBotLookupWhenAccessIsDenied() {
        BotService botService = mock(BotService.class);
        BotBrowserAccessService accessService = mock(BotBrowserAccessService.class);
        Authentication authentication = mock(Authentication.class);
        ResponseStatusException notFound = new ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND,
                "Ресурс не найден"
        );
        when(accessService.requireAccess(42L, authentication)).thenThrow(notFound);

        BotsController controller = controller(botService, accessService);

        assertThatThrownBy(() -> controller.botBrowserPage(42L, new ExtendedModelMap(), authentication))
                .isSameAs(notFound);
        verify(botService, never()).findById(42L, authentication);
    }

    private BotsController controller(BotService botService, BotBrowserAccessService accessService) {
        return new BotsController(
                botService,
                mock(UserService.class),
                mock(StatusBotService.class),
                mock(BotValidation.class),
                accessService,
                mock(BotCrudAccessService.class)
        );
    }
}
