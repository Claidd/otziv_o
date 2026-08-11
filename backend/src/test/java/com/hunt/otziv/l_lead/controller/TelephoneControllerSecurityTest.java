package com.hunt.otziv.l_lead.controller;

import com.hunt.otziv.l_lead.dto.TelephoneDTO;
import com.hunt.otziv.l_lead.service.LeadAccessService;
import com.hunt.otziv.l_lead.service.TelephoneService;
import com.hunt.otziv.u_users.model.Operator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.ui.Model;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TelephoneControllerSecurityTest {

    @Mock private TelephoneService telephoneService;
    @Mock private LeadAccessService leadAccessService;
    @Mock private Model model;

    @InjectMocks
    private TelephoneController controller;

    @Test
    void scopedOwnerListFiltersForeignPhonesAndContainsOnlyCredentialMasks() {
        Authentication actor = ownerAuthentication();
        Operator allowed = Operator.builder().id(12L).build();
        Operator foreign = Operator.builder().id(99L).build();
        when(leadAccessService.assignmentOptions(actor)).thenReturn(options(allowed));
        when(telephoneService.getAllTelephones()).thenReturn(List.of(
                phone(7L, allowed),
                phone(8L, foreign)
        ));

        controller.allPhones(model, actor);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TelephoneDTO>> captor = ArgumentCaptor.forClass(List.class);
        verify(model).addAttribute(org.mockito.ArgumentMatchers.eq("all_phones"), captor.capture());
        assertEquals(1, captor.getValue().size());
        TelephoneDTO safe = captor.getValue().getFirst();
        assertEquals(7L, safe.getId());
        assertEquals("••••••", safe.getGoogleLogin());
        assertEquals("Сохранён", safe.getGooglePassword());
        assertEquals("Сохранён", safe.getAvitoPassword());
        assertEquals("••••••", safe.getMailLogin());
        assertEquals("Сохранён", safe.getMailPassword());
    }

    @Test
    void editModelNeverContainsStoredProviderCredentials() {
        Authentication actor = ownerAuthentication();
        Operator operator = Operator.builder().id(12L).build();
        TelephoneDTO current = phone(7L, operator);
        when(telephoneService.getTelephoneDTOById(7L)).thenReturn(current);
        when(leadAccessService.assignmentOptions(actor)).thenReturn(options(operator));

        controller.editPhone(model, 7L, actor);

        ArgumentCaptor<TelephoneDTO> captor = ArgumentCaptor.forClass(TelephoneDTO.class);
        verify(model).addAttribute(org.mockito.ArgumentMatchers.eq("phone"), captor.capture());
        TelephoneDTO safe = captor.getValue();
        assertNull(safe.getGoogleLogin());
        assertNull(safe.getGooglePassword());
        assertNull(safe.getAvitoPassword());
        assertNull(safe.getMailLogin());
        assertNull(safe.getMailPassword());
        verify(model).addAttribute("googleLoginPresent", true);
        verify(model).addAttribute("googlePasswordPresent", true);
        verify(model).addAttribute("avitoPasswordPresent", true);
        verify(model).addAttribute("mailLoginPresent", true);
        verify(model).addAttribute("mailPasswordPresent", true);
    }

    @Test
    void blankEditFieldsPreserveStoredProviderCredentials() {
        Authentication actor = ownerAuthentication();
        Operator operator = Operator.builder().id(12L).build();
        TelephoneDTO current = phone(7L, operator);
        TelephoneDTO requested = TelephoneDTO.builder()
                .number("79990000000")
                .operator(operator)
                .googleLogin(" ")
                .googlePassword(null)
                .avitoPassword("")
                .mailLogin("\t")
                .mailPassword("")
                .build();
        when(leadAccessService.canAccessOperator(12L, actor)).thenReturn(true);
        when(telephoneService.getTelephoneDTOById(7L)).thenReturn(current);

        controller.updatePhone(7L, requested, actor);

        ArgumentCaptor<TelephoneDTO> captor = ArgumentCaptor.forClass(TelephoneDTO.class);
        verify(telephoneService).updatePhone(org.mockito.ArgumentMatchers.eq(7L), captor.capture());
        TelephoneDTO update = captor.getValue();
        assertEquals("google-login-secret", update.getGoogleLogin());
        assertEquals("google-password-secret", update.getGooglePassword());
        assertEquals("avito-password-secret", update.getAvitoPassword());
        assertEquals("mail-login-secret", update.getMailLogin());
        assertEquals("mail-password-secret", update.getMailPassword());
    }

    @Test
    void editRejectsForeignPhoneBeforeLoadingSecrets() {
        Authentication actor = ownerAuthentication();
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Телефон не найден"))
                .when(leadAccessService).requireTelephoneAccess(7L, actor);

        assertThrows(ResponseStatusException.class, () -> controller.editPhone(model, 7L, actor));

        verify(telephoneService, never()).getTelephoneDTOById(any());
    }

    @Test
    void scopedOwnerCannotDetachLegacyPhoneFromOperator() {
        Authentication actor = ownerAuthentication();
        TelephoneDTO requested = TelephoneDTO.builder().number("79990000000").build();

        assertThrows(ResponseStatusException.class, () -> controller.updatePhone(7L, requested, actor));

        verify(leadAccessService).requireTelephoneAccess(7L, actor);
        verify(telephoneService, never()).getTelephoneDTOById(any());
        verify(telephoneService, never()).updatePhone(any(), any());
    }

    @Test
    void deleteRequiresTelephoneScopeBeforeMutation() {
        Authentication actor = ownerAuthentication();
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Телефон не найден"))
                .when(leadAccessService).requireTelephoneAccess(7L, actor);

        assertThrows(ResponseStatusException.class, () -> controller.deletePhone(7L, actor));

        verify(telephoneService, never()).deletePhone(any());
    }

    private TelephoneDTO phone(Long id, Operator operator) {
        return TelephoneDTO.builder()
                .id(id)
                .number("79990000000")
                .operator(operator)
                .googleLogin("google-login-secret")
                .googlePassword("google-password-secret")
                .avitoPassword("avito-password-secret")
                .mailLogin("mail-login-secret")
                .mailPassword("mail-password-secret")
                .build();
    }

    private LeadAccessService.LeadAssignmentOptions options(Operator operator) {
        return new LeadAccessService.LeadAssignmentOptions(List.of(), List.of(operator), List.of());
    }

    private Authentication ownerAuthentication() {
        return new UsernamePasswordAuthenticationToken(
                "owner",
                "password",
                Set.of(new SimpleGrantedAuthority("ROLE_OWNER"))
        );
    }
}
