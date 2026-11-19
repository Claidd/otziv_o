package com.hunt.otziv.client_messages.controller;

import com.hunt.otziv.client_messages.api.ClientContactHealth;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/client-messages/contacts")
@PreAuthorize("hasAnyRole('ADMIN','OWNER')")
public class ClientContactHealthController {
    private final ClientContactHealth contacts;
    @GetMapping public List<ClientContactHealth.Contact> list() { return contacts.excludedCompanies(); }
    @PostMapping("/{id}/recheck") public ClientContactHealth.Contact recheck(@PathVariable Long id) { return contacts.recheck(id); }
}
