package com.hunt.otziv.admin.controller;

import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.whatsapp.dto.WhatsAppClientStatusDto;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.Locale;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/cabinet/whatsapp")
public class ApiCabinetWhatsAppController {

    private final UserService userService;
    private final ManagerService managerService;
    private final WhatsAppService whatsAppService;

    @GetMapping
    @PreAuthorize("hasRole('MANAGER')")
    public WhatsAppClientStatusDto status(Principal principal) {
        User user = userService.findByUserName(principal.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));

        Manager manager = managerService.getManagerByUserId(user.getId());
        if (manager == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Manager profile not found");
        }

        String clientId = manager.getClientId();
        if (!hasText(clientId)) {
            clientId = defaultClientId(user.getUsername());
        }

        return whatsAppService.getClientStatus(clientId);
    }

    private String defaultClientId(String username) {
        String normalized = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? "whatsapp_manager" : "whatsapp_" + normalized;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
