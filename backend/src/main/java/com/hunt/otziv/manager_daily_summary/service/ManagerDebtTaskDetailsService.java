package com.hunt.otziv.manager_daily_summary.service;

import com.hunt.otziv.manager_control.model.ManagerDailyControl;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlGroup;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemType;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlItemRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ManagerDebtTaskDetailsService {

    private static final String UNANSWERED = "UNANSWERED_CLIENT_MESSAGES";
    private static final String OVERDUE = "OVERDUE_ORDERS";
    private static final String RISKS = "OPEN_RISKS";
    private static final String AUTOMATION = "AUTOMATION_FAILURES";

    private final ManagerDailyControlRepository controlRepository;
    private final ManagerDailyControlItemRepository itemRepository;
    private final ManagerDailyControlConcreteItemRepository concreteItemRepository;

    @Transactional(readOnly = true)
    public Map<Long, ManagerDebtTasks> tasks(
            LocalDate date,
            List<Long> managerIds
    ) {
        if (date == null || managerIds == null || managerIds.isEmpty()) {
            return Map.of();
        }
        Set<Long> requested = managerIds.stream()
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        List<ManagerDailyControl> controls = controlRepository.findByControlDate(date).stream()
                .filter(control -> control.getManager() != null
                        && requested.contains(control.getManager().getId()))
                .toList();
        if (controls.isEmpty()) {
            return Map.of();
        }
        List<ManagerDailyControlItem> openItems = itemRepository.findByControlIn(controls).stream()
                .filter(this::openActionItem)
                .sorted(Comparator.comparingInt(item -> priority(item.getReasonCode())))
                .toList();
        if (openItems.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<ManagerDailyControlConcreteItem>> concreteByParent =
                concreteItemRepository.findByParentItemIn(openItems).stream()
                        .filter(item -> item.getStatus() == ManagerDailyControlItemStatus.OPEN)
                        .filter(item -> item.getParentItem() != null
                                && item.getParentItem().getId() != null)
                        .collect(Collectors.groupingBy(
                                item -> item.getParentItem().getId(),
                                LinkedHashMap::new,
                                Collectors.toList()
                        ));
        Map<Long, List<DebtCategory>> byManager = new HashMap<>();
        for (ManagerDailyControlItem item : openItems) {
            Long managerId = item.getControl() == null || item.getControl().getManager() == null
                    ? null
                    : item.getControl().getManager().getId();
            if (managerId == null) continue;
            List<DebtItem> concrete = concreteByParent
                    .getOrDefault(item.getId(), List.of())
                    .stream()
                    .sorted(Comparator.comparing(
                            ManagerDailyControlConcreteItem::getTitle,
                            Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)
                    ))
                    .map(this::debtItem)
                    .toList();
            byManager.computeIfAbsent(managerId, ignored -> new ArrayList<>()).add(
                    new DebtCategory(
                            clean(item.getReasonCode()),
                            categoryLabel(item),
                            Math.max(0, item.getCount()),
                            clean(item.getTargetUrl()),
                            concrete
                    )
            );
        }
        Map<Long, ManagerDebtTasks> result = new LinkedHashMap<>();
        byManager.forEach((managerId, categories) ->
                result.put(managerId, new ManagerDebtTasks(managerId, categories)));
        return Map.copyOf(result);
    }

    public String location(String managerName, DebtCategory category) {
        return "«Контроль менеджеров» → " + clean(managerName) + " → «"
                + category.label() + "»";
    }

    public String action(DebtCategory category) {
        return switch (category.reasonCode()) {
            case UNANSWERED -> "открыть каждую указанную переписку, прочитать последнее сообщение "
                    + "и либо отправить содержательный ответ, либо отметить, почему ответ не требуется";
            case OVERDUE -> "открыть каждый просроченный заказ, выполнить следующий необходимый шаг "
                    + "и зафиксировать результат или обоснованный новый срок";
            case RISKS -> "проверить факт по каждому риску, принять решение и сохранить основание";
            case AUTOMATION -> "проверить причину сбоя, восстановить недостающую привязку или интеграцию "
                    + "и повторить действие; если автоматизация не нужна — зафиксировать ручной результат";
            default -> "открыть перечисленные карточки, выполнить требуемое действие и зафиксировать результат";
        };
    }

    public String completionCriterion(DebtCategory category) {
        return switch (category.reasonCode()) {
            case UNANSWERED -> "счётчик «Неотвеченные сообщения» стал 0";
            case AUTOMATION -> "повтор выполнен успешно, а задача больше не имеет статуса «Открыта»";
            default -> "все перечисленные задачи больше не имеют статуса «Открыта»";
        };
    }

    private boolean openActionItem(ManagerDailyControlItem item) {
        return item != null
                && item.getGroup() == ManagerDailyControlGroup.ACTION
                && item.getItemType() != ManagerDailyControlItemType.ORDER_STATUS
                && item.getStatus() == ManagerDailyControlItemStatus.OPEN
                && item.getCount() > 0;
    }

    private DebtItem debtItem(ManagerDailyControlConcreteItem item) {
        return new DebtItem(
                item.getId(),
                clean(item.getTitle()),
                firstDetail(item),
                clean(item.getTargetUrl()),
                clean(item.getChatUrl())
        );
    }

    private String firstDetail(ManagerDailyControlConcreteItem item) {
        if (!clean(item.getStatusLabel()).isBlank()) return clean(item.getStatusLabel());
        if (!clean(item.getSubtitle()).isBlank()) return clean(item.getSubtitle());
        return limit(clean(item.getReason()), 180);
    }

    private String categoryLabel(ManagerDailyControlItem item) {
        String label = clean(item.getLabel());
        if (!label.isBlank()) return label;
        return switch (clean(item.getReasonCode())) {
            case UNANSWERED -> "Неотвеченные сообщения";
            case OVERDUE -> "Просроченные заказы";
            case RISKS -> "Открытые риски";
            case AUTOMATION -> "Ошибки задач и интеграций";
            default -> "Другие открытые задачи";
        };
    }

    private int priority(String reasonCode) {
        return switch (clean(reasonCode)) {
            case UNANSWERED -> 0;
            case OVERDUE -> 1;
            case RISKS -> 2;
            case AUTOMATION -> 3;
            default -> 4;
        };
    }

    private String clean(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private String limit(String value, int max) {
        String text = clean(value);
        return text.length() <= max ? text : text.substring(0, Math.max(0, max - 1)) + "…";
    }

    public record ManagerDebtTasks(Long managerId, List<DebtCategory> categories) {
        public ManagerDebtTasks {
            categories = categories == null ? List.of() : List.copyOf(categories);
        }
    }

    public record DebtCategory(
            String reasonCode,
            String label,
            long count,
            String targetUrl,
            List<DebtItem> items
    ) {
        public DebtCategory {
            reasonCode = reasonCode == null ? "" : reasonCode;
            label = label == null ? "" : label;
            targetUrl = targetUrl == null ? "" : targetUrl;
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record DebtItem(
            Long id,
            String title,
            String detail,
            String targetUrl,
            String chatUrl
    ) {
        public DebtItem {
            title = title == null ? "" : title;
            detail = detail == null ? "" : detail;
            targetUrl = targetUrl == null ? "" : targetUrl;
            chatUrl = chatUrl == null ? "" : chatUrl;
        }
    }
}
