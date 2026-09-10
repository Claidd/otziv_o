package com.hunt.otziv.p_products.status.service;

import com.hunt.otziv.config.settings.api.PublicationProgressSettings;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Prepares optional progress text; publication and durable enqueue keep their existing transaction. */
@Service
@RequiredArgsConstructor
public class PublicationProgressMessage {
    private final PublicationProgressSettings settings;

    public record Context(boolean companyReportsEnabled, String companyTitle, String filialTitle) {}

    /** Context is loaded once and only when the global switches and progress permit a message. */
    public String prepare(int total, int published, Supplier<Context> context) {
        if (!settings.immediatePublicationMessagesEnabled()) return null;
        if (total > 0 && published >= total) return null;
        if (!settings.publicationProgressReportsEnabled()) return null;
        Context details = context.get();
        if (!details.companyReportsEnabled()) return null;
        String company = clean(details.companyTitle()), filial = clean(details.filialTitle());
        String subject = company.isEmpty() ? filial : filial.isEmpty() ? company : company + " - " + filial;
        if (subject.isEmpty()) subject = "Компания";
        int expected = total > 0 ? total : published;
        String template = settings.publicationProgressTemplate();
        if (template == null || template.isBlank()) template = PublicationProgressSettings.DEFAULT_TEMPLATE;
        return template.replace("{company}", company).replace("{filial}", filial)
                .replace("{companyAndFilial}", subject).replace("{published}", String.valueOf(published))
                .replace("{total}", String.valueOf(expected)).replace("{progress}", published + " / " + expected).trim();
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
