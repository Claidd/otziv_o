package com.hunt.otziv.whatsapp.service;

import com.hunt.otziv.c_companies.api.MissingWhatsAppRoute;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Repairs a missing route before dispatch; never modifies a frozen delivery or guesses by group name. */
@Service
@RequiredArgsConstructor
@Slf4j
public class WhatsAppDeliveryRouteRecovery {
    private final MissingWhatsAppRoute companies;
    private final WhatsAppService whatsapp;

    public String recoverMissingGroup(Long companyId, String invite, String clientId) {
        if (companyId == null || clientId == null || clientId.isBlank()
                || TransactionSynchronizationManager.isActualTransactionActive()
                || WhatsAppGroupCompanyLinker.whatsAppInviteCode(invite).isEmpty()) return null;
        try {
            var current = companies.find(companyId).orElse(null);
            if (current == null || !current.bindingRequired() || !Objects.equals(current.invite(), invite)) return null;
            // An input snapshot may predate a successful sync. Prefer the persisted route.
            if (current.groupId() != null && !current.groupId().isBlank()) return current.groupId();
            var resolved = whatsapp.resolveGroupByInvite(clientId, current.invite()).orElse(null);
            if (resolved == null || resolved.groupId() == null || !resolved.groupId().matches("[0-9]+@g\\.us")) return null;
            // The conditional write cannot overwrite a manager edit or another successful repair.
            if (!companies.restore(current, resolved.groupId())) return null;
            log.info("Missing WhatsApp delivery route restored automatically: companyId={}, clientId={}", companyId, clientId);
            return resolved.groupId();
        } catch (RuntimeException unavailable) {
            log.warn("Automatic WhatsApp route repair deferred: companyId={}, errorType={}", companyId, unavailable.getClass().getSimpleName());
            return null;
        }
    }
}
