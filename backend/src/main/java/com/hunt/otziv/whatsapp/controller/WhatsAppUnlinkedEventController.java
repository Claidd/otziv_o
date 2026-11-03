package com.hunt.otziv.whatsapp.controller;

import com.hunt.otziv.whatsapp.service.WhatsAppUnlinkedEventStore;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController @RequiredArgsConstructor
public class WhatsAppUnlinkedEventController {
    private final WhatsAppUnlinkedEventStore store;
    @GetMapping("/api/whatsapp/unlinked-events")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public List<Map<String,Object>> unresolvedGroups() {return store.unresolvedGroups();}
}
