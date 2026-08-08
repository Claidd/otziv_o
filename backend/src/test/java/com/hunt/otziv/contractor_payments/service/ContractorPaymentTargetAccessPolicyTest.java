package com.hunt.otziv.contractor_payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hunt.otziv.contractor_payments.repository.ContractorPaymentAllocationRepository;
import com.hunt.otziv.u_users.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class ContractorPaymentTargetAccessPolicyTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final ContractorPaymentAllocationRepository allocationRepository =
            mock(ContractorPaymentAllocationRepository.class);
    private final ContractorPaymentTargetAccessPolicy policy =
            new ContractorPaymentTargetAccessPolicy(userRepository, allocationRepository);

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void restrictedOwnerCannotTargetAdminOrOwnerUser() {
        authenticate("ROLE_OWNER");
        when(userRepository.countRolesByUserIdAndNames(42L, java.util.Set.of("ROLE_ADMIN", "ROLE_OWNER")))
                .thenReturn(1L);

        assertThatThrownBy(() -> policy.requireCanManageUser(42L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(404));
    }

    @Test
    void adminAlwaysRetainsFullAccess() {
        authenticate("ROLE_ADMIN");

        policy.requireCanManageUser(42L);
        policy.requireCanManageAllocationRecipient(91L);
        policy.requireCanManagePaymentLink(92L);
        policy.requireCanManageAllPaymentLinks();

        assertThat(policy.excludePrivilegedTargets()).isFalse();
        verifyNoInteractions(userRepository, allocationRepository);
    }

    @Test
    void explicitOptInAllowsOwnerToManagePrivilegedTargets() {
        authenticate("ROLE_OWNER");
        ReflectionTestUtils.setField(policy, "ownerManagePrivilegedUsers", true);

        policy.requireCanManageUser(42L);
        policy.requireCanManageAllocationRecipient(91L);
        policy.requireCanManagePaymentLink(92L);
        policy.requireCanManageAllPaymentLinks();

        assertThat(policy.excludePrivilegedTargets()).isFalse();
        verifyNoInteractions(userRepository, allocationRepository);
    }

    @Test
    void allocationMutationAuthorizesDurableRecipientProfileUser() {
        authenticate("ROLE_OWNER");
        when(allocationRepository.findRecipientProfileUserIdById(91L)).thenReturn(Optional.of(42L));
        when(userRepository.countRolesByUserIdAndNames(42L, java.util.Set.of("ROLE_ADMIN", "ROLE_OWNER")))
                .thenReturn(1L);

        assertThatThrownBy(() -> policy.requireCanManageAllocationRecipient(91L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(404));

        verify(allocationRepository).findRecipientProfileUserIdById(91L);
        verify(userRepository).countRolesByUserIdAndNames(
                42L,
                java.util.Set.of("ROLE_ADMIN", "ROLE_OWNER")
        );
    }

    @Test
    void paymentLinkMutationConcealsDurablePrivilegedRecipient() {
        authenticate("ROLE_OWNER");
        when(allocationRepository.findRecipientProfileUserIdByPaymentLinkId(92L))
                .thenReturn(Optional.of(42L));
        when(userRepository.countRolesByUserIdAndNames(42L, java.util.Set.of("ROLE_ADMIN", "ROLE_OWNER")))
                .thenReturn(1L);

        assertThatThrownBy(() -> policy.requireCanManagePaymentLink(92L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(404));

        verify(allocationRepository).findRecipientProfileUserIdByPaymentLinkId(92L);
        verify(userRepository).countRolesByUserIdAndNames(
                42L,
                java.util.Set.of("ROLE_ADMIN", "ROLE_OWNER")
        );
    }

    @Test
    void commonInvoiceMutationConcealsDurablePrivilegedRecipient() {
        authenticate("ROLE_OWNER");
        when(allocationRepository.findRecipientProfileUserIdByCommonInvoiceId(95L))
                .thenReturn(Optional.of(42L));
        when(userRepository.countRolesByUserIdAndNames(42L, java.util.Set.of("ROLE_ADMIN", "ROLE_OWNER")))
                .thenReturn(1L);

        assertThatThrownBy(() -> policy.requireCanManageCommonInvoice(95L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(404));

        verify(allocationRepository).findRecipientProfileUserIdByCommonInvoiceId(95L);
        verify(userRepository).countRolesByUserIdAndNames(
                42L,
                java.util.Set.of("ROLE_ADMIN", "ROLE_OWNER")
        );
    }

    @Test
    void paymentLinkAuthorizationAllowsOrdinaryAndOwnerFallbackRecipients() {
        authenticate("ROLE_OWNER");
        when(allocationRepository.findRecipientProfileUserIdByPaymentLinkId(93L))
                .thenReturn(Optional.of(43L));
        when(userRepository.countRolesByUserIdAndNames(43L, java.util.Set.of("ROLE_ADMIN", "ROLE_OWNER")))
                .thenReturn(0L);
        when(allocationRepository.findRecipientProfileUserIdByPaymentLinkId(94L))
                .thenReturn(Optional.empty());

        policy.requireCanManagePaymentLink(93L);
        policy.requireCanManagePaymentLink(94L);

        assertThat(policy.excludePrivilegedTargets()).isTrue();
        verify(allocationRepository).findRecipientProfileUserIdByPaymentLinkId(93L);
        verify(allocationRepository).findRecipientProfileUserIdByPaymentLinkId(94L);
    }

    @Test
    void restrictedOwnerCannotRunGlobalPaymentLinkArchive() {
        authenticate("ROLE_OWNER");

        assertThatThrownBy(policy::requireCanManageAllPaymentLinks)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(403));
    }

    private void authenticate(String... roles) {
        var authorities = java.util.Arrays.stream(roles)
                .map(SimpleGrantedAuthority::new)
                .toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("actor", "", authorities)
        );
    }
}
