package com.hunt.otziv.l_lead.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.l_lead.dto.TelephoneDTO;
import com.hunt.otziv.l_lead.dto.api.AdminPhoneListRow;
import com.hunt.otziv.l_lead.dto.api.PhoneListResponse;
import com.hunt.otziv.l_lead.dto.api.PhoneResponse;
import com.hunt.otziv.l_lead.dto.api.PhoneUpsertRequest;
import com.hunt.otziv.l_lead.model.Telephone;
import com.hunt.otziv.l_lead.repository.DeviceTokenRepository;
import com.hunt.otziv.l_lead.repository.TelephoneRepository;
import com.hunt.otziv.l_lead.service.LeadAccessService;
import com.hunt.otziv.l_lead.service.TelephoneService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiAdminPhoneControllerSecurityTest {

    @Mock private TelephoneService telephoneService;
    @Mock private TelephoneRepository telephoneRepository;
    @Mock private DeviceTokenRepository deviceTokenRepository;
    @Mock private LeadAccessService leadAccessService;

    @InjectMocks
    private ApiAdminPhoneController controller;

    @Test
    void responseMasksProviderLoginsAndNeverSerializesProviderPasswords() throws Exception {
        TelephoneDTO phone = phoneWithSecrets();
        Authentication actor = adminAuthentication();
        when(leadAccessService.assignmentOptions(actor)).thenReturn(assignmentOptions());
        when(telephoneRepository.findAdminPhoneRows(true, Set.of(Long.MIN_VALUE), ""))
                .thenReturn(List.of(phoneRow(true)));
        when(deviceTokenRepository.findAdminRowsByTelephoneIds(List.of(7L))).thenReturn(List.of());

        PhoneResponse response = controller.getPhones("", actor).phones().getFirst();

        assertEquals("••••••", response.googleLoginMasked());
        assertTrue(response.googleLoginPresent());
        assertTrue(response.googlePasswordPresent());
        assertTrue(response.avitoPasswordPresent());
        assertEquals("••••••", response.mailLoginMasked());
        assertTrue(response.mailLoginPresent());
        assertTrue(response.mailPasswordPresent());

        JsonNode json = new ObjectMapper().findAndRegisterModules().valueToTree(response);
        assertFalse(json.has("googleLogin"));
        assertFalse(json.has("googlePassword"));
        assertFalse(json.has("avitoPassword"));
        assertFalse(json.has("mailLogin"));
        assertFalse(json.has("mailPassword"));
        String serialized = json.toString();
        assertFalse(serialized.contains("google-login-secret"));
        assertFalse(serialized.contains("google-password-secret"));
        assertFalse(serialized.contains("avito-password-secret"));
        assertFalse(serialized.contains("mail-login-secret"));
        assertFalse(serialized.contains("mail-password-secret"));

        JsonNode dtoJson = new ObjectMapper().findAndRegisterModules().valueToTree(phone);
        assertFalse(dtoJson.has("googleLogin"));
        assertFalse(dtoJson.has("googlePassword"));
        assertFalse(dtoJson.has("avitoPassword"));
        assertFalse(dtoJson.has("mailLogin"));
        assertFalse(dtoJson.has("mailPassword"));
    }

    @Test
    void telephoneEntityCannotLeakProviderCredentialsThroughGenericJson() throws Exception {
        Telephone phone = new Telephone();
        phone.setId(7L);
        phone.setGoogleLogin("google-login-secret");
        phone.setGooglePassword("google-password-secret");
        phone.setAvitoPassword("avito-password-secret");
        phone.setMailLogin("mail-login-secret");
        phone.setMailPassword("mail-password-secret");

        JsonNode json = new ObjectMapper().findAndRegisterModules().valueToTree(phone);

        assertFalse(json.has("googleLogin"));
        assertFalse(json.has("googlePassword"));
        assertFalse(json.has("avitoPassword"));
        assertFalse(json.has("mailLogin"));
        assertFalse(json.has("mailPassword"));
        assertFalse(json.toString().contains("secret"));
    }

    @Test
    void blankWriteOnlyFieldsPreserveStoredProviderCredentials() {
        TelephoneDTO current = phoneWithSecrets();
        Authentication actor = adminAuthentication();
        PhoneUpsertRequest request = request("   ", null, "", "\t", "");
        when(telephoneService.getTelephoneDTOById(7L)).thenReturn(current);
        when(deviceTokenRepository.findByTelephone_IdOrderByCreatedAtDesc(7L)).thenReturn(List.of());

        controller.updatePhone(7L, request, actor);

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
    void nonBlankWriteOnlyFieldsReplaceStoredProviderCredentials() {
        TelephoneDTO current = phoneWithSecrets();
        Authentication actor = adminAuthentication();
        PhoneUpsertRequest request = request(
                "new-google-login",
                "new-google-password",
                "new-avito-password",
                "new-mail-login",
                "new-mail-password"
        );
        when(telephoneService.getTelephoneDTOById(7L)).thenReturn(current);
        when(deviceTokenRepository.findByTelephone_IdOrderByCreatedAtDesc(7L)).thenReturn(List.of());

        controller.updatePhone(7L, request, actor);

        ArgumentCaptor<TelephoneDTO> captor = ArgumentCaptor.forClass(TelephoneDTO.class);
        verify(telephoneService).updatePhone(org.mockito.ArgumentMatchers.eq(7L), captor.capture());
        TelephoneDTO update = captor.getValue();
        assertEquals("new-google-login", update.getGoogleLogin());
        assertEquals("new-google-password", update.getGooglePassword());
        assertEquals("new-avito-password", update.getAvitoPassword());
        assertEquals("new-mail-login", update.getMailLogin());
        assertEquals("new-mail-password", update.getMailPassword());
    }

    @Test
    void providerCredentialCannotBeUsedAsReadSearchOracle() {
        Authentication actor = adminAuthentication();
        when(leadAccessService.assignmentOptions(actor)).thenReturn(assignmentOptions());
        when(telephoneRepository.findAdminPhoneRows(true, Set.of(Long.MIN_VALUE), "google-login-secret"))
                .thenReturn(List.of());

        PhoneListResponse response = controller.getPhones("google-login-secret", actor);

        assertTrue(response.phones().isEmpty());
    }

    @Test
    void absentProviderCredentialsHaveNoMaskAndFalsePresenceFlags() {
        Authentication actor = adminAuthentication();
        when(leadAccessService.assignmentOptions(actor)).thenReturn(assignmentOptions());
        when(telephoneRepository.findAdminPhoneRows(true, Set.of(Long.MIN_VALUE), ""))
                .thenReturn(List.of(phoneRow(false)));
        when(deviceTokenRepository.findAdminRowsByTelephoneIds(List.of(7L))).thenReturn(List.of());

        PhoneResponse response = controller.getPhones("", actor).phones().getFirst();

        assertNull(response.googleLoginMasked());
        assertFalse(response.googleLoginPresent());
        assertFalse(response.googlePasswordPresent());
        assertFalse(response.avitoPasswordPresent());
        assertNull(response.mailLoginMasked());
        assertFalse(response.mailLoginPresent());
        assertFalse(response.mailPasswordPresent());
    }

    @Test
    void scopedOwnerCannotDetachPhoneFromOperator() {
        Authentication actor = ownerAuthentication();

        assertThrows(
                ResponseStatusException.class,
                () -> controller.updatePhone(
                        7L,
                        request(null, null, null, null, null),
                        actor
                )
        );

        verify(leadAccessService).requireTelephoneAccess(7L, actor);
        verify(telephoneService, never()).getTelephoneDTOById(7L);
        verify(telephoneService, never()).updatePhone(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private TelephoneDTO phoneWithSecrets() {
        return TelephoneDTO.builder()
                .id(7L)
                .number("79990000000")
                .googleLogin("google-login-secret")
                .googlePassword("google-password-secret")
                .avitoPassword("avito-password-secret")
                .mailLogin("mail-login-secret")
                .mailPassword("mail-password-secret")
                .build();
    }

    private AdminPhoneListRow phoneRow(boolean credentialsPresent) {
        return new AdminPhoneListRow(
                7L,
                "79990000000",
                null,
                null,
                0,
                0,
                0,
                null,
                credentialsPresent,
                credentialsPresent,
                credentialsPresent,
                credentialsPresent,
                credentialsPresent,
                null,
                false,
                null,
                null,
                null,
                null
        );
    }

    private PhoneUpsertRequest request(
            String googleLogin,
            String googlePassword,
            String avitoPassword,
            String mailLogin,
            String mailPassword
    ) {
        return new PhoneUpsertRequest(
                "79990000000",
                null,
                null,
                null,
                null,
                null,
                null,
                googleLogin,
                googlePassword,
                avitoPassword,
                mailLogin,
                mailPassword,
                null,
                null,
                null,
                null
        );
    }

    private LeadAccessService.LeadAssignmentOptions assignmentOptions() {
        return new LeadAccessService.LeadAssignmentOptions(List.of(), List.of(), List.of());
    }

    private Authentication adminAuthentication() {
        return new UsernamePasswordAuthenticationToken(
                "admin",
                "password",
                Set.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
    }

    private Authentication ownerAuthentication() {
        return new UsernamePasswordAuthenticationToken(
                "owner",
                "password",
                Set.of(new SimpleGrantedAuthority("ROLE_OWNER"))
        );
    }
}
