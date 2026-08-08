package com.hunt.otziv.contractor_payments.service;

import com.hunt.otziv.contractor_payments.repository.ContractorPaymentAllocationRepository;
import com.hunt.otziv.u_users.repository.UserRepository;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * One target-authorization policy for every contractor-payment administration
 * entry point. ADMIN always has full access. OWNER may manage users carrying
 * ADMIN/OWNER only when the same explicit opt-in used by user administration
 * is enabled.
 */
@Service
@RequiredArgsConstructor
public class ContractorPaymentTargetAccessPolicy {

    private static final Set<String> PRIVILEGED_TARGET_ROLES = Set.of("ROLE_ADMIN", "ROLE_OWNER");

    private final UserRepository userRepository;
    private final ContractorPaymentAllocationRepository allocationRepository;

    @Value("${otziv.security.owner-manage-privileged-users:false}")
    private boolean ownerManagePrivilegedUsers;

    public void requireCanManageUser(Long userId) {
        if (!restrictedOwner() || userId == null) {
            return;
        }
        if (isPrivilegedTarget(userId)) {
            // User-scoped contractor-payment endpoints are identifier based.
            // Conceal privileged targets exactly like allocation/link targets
            // so the response cannot be used as a user-existence oracle.
            throw concealed("Пользователь не найден");
        }
    }

    /**
     * Allocation-id mutations must authorize the durable recipient profile,
     * never the mutable worker/manager currently attached to the order.
     */
    public void requireCanManageAllocationRecipient(Long allocationId) {
        if (!restrictedOwner() || allocationId == null) {
            return;
        }
        allocationRepository.findRecipientProfileUserIdById(allocationId)
                .filter(this::isPrivilegedTarget)
                .ifPresent(ignored -> {
                    throw concealed("Назначение платежа не найдено");
                });
    }

    /**
     * Payment-link administration must authorize the immutable allocation
     * recipient before reading decrypted snapshots or changing provider state.
     * A restricted target is deliberately indistinguishable from a missing
     * link to prevent an identifier-existence oracle.
     */
    public void requireCanManagePaymentLink(Long paymentLinkId) {
        if (!restrictedOwner() || paymentLinkId == null) {
            return;
        }
        allocationRepository.findRecipientProfileUserIdByPaymentLinkId(paymentLinkId)
                .filter(this::isPrivilegedTarget)
                .ifPresent(ignored -> {
                    throw concealed("Платежная ссылка не найдена");
                });
    }

    /**
     * Common-invoice administration must authorize the same immutable
     * allocation recipient as standalone payment-link administration. The
     * policy query runs before the controller loads or mutates the invoice.
     */
    public void requireCanManageCommonInvoice(Long commonInvoiceId) {
        if (!restrictedOwner() || commonInvoiceId == null) {
            return;
        }
        allocationRepository.findRecipientProfileUserIdByCommonInvoiceId(commonInvoiceId)
                .filter(this::isPrivilegedTarget)
                .ifPresent(ignored -> {
                    throw concealed("Общий счет не найден");
                });
    }

    /** Global archive mutations cannot be safely narrowed to one target. */
    public void requireCanManageAllPaymentLinks() {
        if (restrictedOwner()) {
            throw forbidden();
        }
    }

    public boolean excludePrivilegedTargets() {
        return restrictedOwner();
    }

    public boolean excludePrivilegedTargetsFromJournal() {
        return excludePrivilegedTargets();
    }

    private boolean isPrivilegedTarget(Long userId) {
        return userId != null
                && userRepository.countRolesByUserIdAndNames(userId, PRIVILEGED_TARGET_ROLES) > 0L;
    }

    private boolean restrictedOwner() {
        return !ownerManagePrivilegedUsers
                && hasAuthority("ROLE_OWNER")
                && !hasAuthority("ROLE_ADMIN");
    }

    private boolean hasAuthority(String expected) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(Objects::nonNull)
                .map(String::trim)
                .anyMatch(expected::equalsIgnoreCase);
    }

    private ResponseStatusException forbidden() {
        return new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Владельцу запрещено управлять платёжными данными администратора или владельца"
        );
    }

    private ResponseStatusException concealed(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}
