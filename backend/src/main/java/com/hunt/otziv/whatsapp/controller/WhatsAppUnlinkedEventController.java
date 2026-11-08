package com.hunt.otziv.whatsapp.controller;

import com.hunt.otziv.whatsapp.service.WhatsAppUnlinkedEventStore;
import com.hunt.otziv.whatsapp.service.WhatsAppUnlinkedGroupNotifier;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController @RequiredArgsConstructor
public class WhatsAppUnlinkedEventController {
    private final WhatsAppUnlinkedEventStore store;
    private final WhatsAppUnlinkedGroupNotifier notices;
    @GetMapping("/api/whatsapp/unlinked-events")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public List<Map<String,Object>> unresolvedGroups() {return store.unresolvedGroups();}
    /** The admin notice each group with open messages gets (or got), as Telegram HTML; sends nothing. */
    @GetMapping("/api/whatsapp/unlinked-events/notices")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public List<WhatsAppUnlinkedGroupNotifier.Preview> notices() {return notices.previews();}
}
