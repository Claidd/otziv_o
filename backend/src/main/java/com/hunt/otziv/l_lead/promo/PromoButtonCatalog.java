package com.hunt.otziv.l_lead.promo;

import java.util.List;
import java.util.Optional;

public final class PromoButtonCatalog {
    public static final String SECTION_LEADS = "leads";
    public static final String SECTION_MANAGER_COMPANIES = "manager_companies";
    public static final String SECTION_MANAGER_ORDERS = "manager_orders";

    private static final List<Slot> SLOTS = List.of(
            new Slot(SECTION_LEADS, "Лиды", "offer", "предложение", 0, 1),
            new Slot(SECTION_LEADS, "Лиды", "reminder", "напоминание", 1, 2),
            new Slot(SECTION_LEADS, "Лиды", "data", "данные", 2, 3),
            new Slot(SECTION_LEADS, "Лиды", "answers", "ответы", 3, 4),
            new Slot(SECTION_MANAGER_COMPANIES, "Менеджер: компании", "offer", "предложение", 0, 1),
            new Slot(SECTION_MANAGER_COMPANIES, "Менеджер: компании", "explanation", "пояснение", 10, 11),
            new Slot(SECTION_MANAGER_COMPANIES, "Менеджер: компании", "broadcast", "рассылка", 9, 10),
            new Slot(SECTION_MANAGER_ORDERS, "Менеджер: заказы", "explanation", "пояснение", 10, 11),
            new Slot(SECTION_MANAGER_ORDERS, "Менеджер: заказы", "reminder", "напоминание", 5, 6),
            new Slot(SECTION_MANAGER_ORDERS, "Менеджер: заказы", "threat", "угроза", 6, 7)
    );

    private PromoButtonCatalog() {
    }

    public static List<Slot> slots() {
        return SLOTS;
    }

    public static List<Slot> slotsForSection(String sectionCode) {
        return SLOTS.stream()
                .filter(slot -> slot.sectionCode().equals(sectionCode))
                .toList();
    }

    public static Optional<Slot> find(String sectionCode, String buttonKey) {
        return SLOTS.stream()
                .filter(slot -> slot.sectionCode().equals(sectionCode) && slot.buttonKey().equals(buttonKey))
                .findFirst();
    }

    public record Slot(
            String sectionCode,
            String sectionTitle,
            String buttonKey,
            String buttonLabel,
            int outputIndex,
            int defaultPosition
    ) {
    }
}
