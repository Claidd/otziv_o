package com.hunt.otziv.payments.service;

import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.contractor_payments.model.ContractorActualPaymentAttribution;
import com.hunt.otziv.contractor_payments.model.ContractorActualPaymentSourceKind;
import com.hunt.otziv.contractor_payments.model.ContractorCashDestinationKind;
import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.contractor_payments.repository.ContractorActualPaymentAttributionRepository;
import com.hunt.otziv.payments.dto.ManualPaymentRecipientMonthlySummaryItem;
import com.hunt.otziv.payments.dto.ManualPaymentRecipientMonthlySummaryResponse;
import com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind;
import com.hunt.otziv.payments.repository.ManualPaymentLegacyMonthlySourceProjection;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ManualPaymentRecipientMonthlySummaryService {

    static final String LEGACY_UNKNOWN_KEY = "LEGACY:UNATTRIBUTED";
    static final String LEGACY_UNKNOWN_LABEL = "Получатель не указан (старые оплаты без атрибуции)";

    private final ContractorActualPaymentAttributionRepository attributionRepository;
    private final PaymentLinkRepository paymentLinkRepository;
    private final CommonInvoiceRepository commonInvoiceRepository;

    @Transactional(readOnly = true)
    public ManualPaymentRecipientMonthlySummaryResponse summary(String month) {
        YearMonth selectedMonth = parseMonth(month);
        LocalDateTime from = selectedMonth.atDay(1).atStartOfDay();
        LocalDateTime to = selectedMonth.plusMonths(1).atDay(1).atStartOfDay();
        Map<String, RecipientAccumulator> recipients = new LinkedHashMap<>();
        Set<SourceKey> allSources = new HashSet<>();

        for (ContractorActualPaymentAttribution row : safeList(
                attributionRepository
                        .findAllByEffectiveAtGreaterThanEqualAndEffectiveAtLessThanOrderByEffectiveAtAscIdAsc(
                                from, to))) {
            if (row == null || row.getSourceKind() == null || row.getSourceId() == null) {
                continue;
            }
            RecipientDescriptor descriptor = descriptor(row);
            SourceKey source = new SourceKey(row.getSourceKind(), row.getSourceId());
            recipients.computeIfAbsent(descriptor.key(), ignored -> new RecipientAccumulator(descriptor))
                    .add(source, row.getAmountKopecks(), row.getEffectiveAt());
            allSources.add(source);
        }

        RecipientAccumulator legacy = null;
        legacy = addLegacy(
                legacy,
                safeList(paymentLinkRepository.findLegacyManualConfirmedForMonthlyRecipientSummary(
                        from,
                        to
                )),
                ContractorActualPaymentSourceKind.PAYMENT_LINK,
                allSources
        );
        legacy = addLegacy(
                legacy,
                safeList(commonInvoiceRepository.findLegacyManualConfirmedForMonthlyRecipientSummary(
                        from,
                        to
                )),
                ContractorActualPaymentSourceKind.COMMON_INVOICE,
                allSources
        );
        if (legacy != null) {
            recipients.put(LEGACY_UNKNOWN_KEY, legacy);
        }

        List<ManualPaymentRecipientMonthlySummaryItem> items = new ArrayList<>(recipients.size());
        for (RecipientAccumulator recipient : recipients.values()) {
            items.add(recipient.toItem());
        }
        items.sort(Comparator
                .comparingLong(ManualPaymentRecipientMonthlySummaryItem::amountKopecks)
                .reversed()
                .thenComparing(
                        item -> normalize(item.accountingRecipientLabel()),
                        String.CASE_INSENSITIVE_ORDER
                ));
        long totalAmountKopecks = items.stream()
                .mapToLong(ManualPaymentRecipientMonthlySummaryItem::amountKopecks)
                .sum();

        return new ManualPaymentRecipientMonthlySummaryResponse(
                selectedMonth.toString(),
                selectedMonth.atDay(1),
                selectedMonth.plusMonths(1).atDay(1),
                items.size(),
                allSources.size(),
                totalAmountKopecks,
                BigDecimal.valueOf(totalAmountKopecks, 2),
                List.copyOf(items)
        );
    }

    private RecipientAccumulator addLegacy(
            RecipientAccumulator accumulator,
            Collection<ManualPaymentLegacyMonthlySourceProjection> rows,
            ContractorActualPaymentSourceKind sourceKind,
            Set<SourceKey> allSources
    ) {
        RecipientAccumulator result = accumulator;
        for (ManualPaymentLegacyMonthlySourceProjection row : rows) {
            if (row == null || row.getSourceId() == null) {
                continue;
            }
            long amountKopecks = row.getAmountKopecks() == null ? 0L : row.getAmountKopecks();
            if (amountKopecks <= 0) {
                continue;
            }
            if (result == null) {
                result = new RecipientAccumulator(RecipientDescriptor.legacy());
            }
            SourceKey source = new SourceKey(sourceKind, row.getSourceId());
            result.add(source, amountKopecks, row.getEffectiveAt());
            allSources.add(source);
        }
        return result;
    }

    private RecipientDescriptor descriptor(ContractorActualPaymentAttribution row) {
        ContractorCashDestinationKind kind = row.getActualCashDestinationKind();
        if (kind == ContractorCashDestinationKind.OWNER) {
            return new RecipientDescriptor(
                    "OWNER", "Владелец", kind, ContractorRecipientType.OWNER,
                    null, null, null, null, true
            );
        }
        if (kind == ContractorCashDestinationKind.CONTRACTOR_PROFILE
                && row.getActualRecipientProfileId() != null) {
            Long profileId = row.getActualRecipientProfileId();
            ContractorRecipientType type = row.getActualRecipientType();
            String label = normalize(row.getActualRecipientNameSnapshot());
            if (label.isBlank()) {
                label = recipientTypeLabel(type) + " · профиль №" + profileId;
            }
            return new RecipientDescriptor(
                    "PROFILE:" + profileId,
                    label,
                    kind,
                    type,
                    profileId,
                    null,
                    null,
                    null,
                    true
            );
        }
        if (kind == ContractorCashDestinationKind.MANUAL_PAYMENT_TASK
                && row.getActualManualPaymentTaskId() != null) {
            Long taskId = row.getActualManualPaymentTaskId();
            Long generation = row.getActualManualPaymentTaskGeneration();
            ManualPaymentTaskAccountingTargetKind targetKind = row.getActualManualPaymentTaskTargetKind();
            if (targetKind == ManualPaymentTaskAccountingTargetKind.OWNER) {
                return new RecipientDescriptor(
                        "OWNER", "Владелец", ContractorCashDestinationKind.OWNER,
                        ContractorRecipientType.OWNER, null, null, null, null, true
                );
            }
            if ((targetKind == ManualPaymentTaskAccountingTargetKind.SPECIALIST
                    || targetKind == ManualPaymentTaskAccountingTargetKind.MANAGER)
                    && row.getActualRecipientProfileId() != null) {
                Long profileId = row.getActualRecipientProfileId();
                ContractorRecipientType type = targetKind == ManualPaymentTaskAccountingTargetKind.SPECIALIST
                        ? ContractorRecipientType.SPECIALIST : ContractorRecipientType.MANAGER;
                String label = normalize(row.getActualRecipientNameSnapshot());
                if (label.isBlank()) {
                    label = recipientTypeLabel(type) + " · профиль №" + profileId;
                }
                return new RecipientDescriptor(
                        "PROFILE:" + profileId,
                        label,
                        ContractorCashDestinationKind.CONTRACTOR_PROFILE,
                        type,
                        profileId,
                        null,
                        null,
                        null,
                        true
                );
            }
            if (targetKind != ManualPaymentTaskAccountingTargetKind.EXTERNAL_TASK) {
                return unknownDescriptor(row, kind);
            }
            String label = normalize(row.getActualRecipientNameSnapshot());
            if (label.isBlank()) {
                label = "Внешний получатель";
            }
            return new RecipientDescriptor(
                    "TASK:" + taskId + ":" + (generation == null ? "?" : generation),
                    label + " · платёжное задание №" + taskId,
                    kind,
                    row.getActualRecipientType(),
                    row.getActualRecipientProfileId(),
                    taskId,
                    generation,
                    targetKind,
                    true
            );
        }
        return unknownDescriptor(row, kind);
    }

    private RecipientDescriptor unknownDescriptor(
            ContractorActualPaymentAttribution row,
            ContractorCashDestinationKind kind
    ) {
        return new RecipientDescriptor(
                "ATTRIBUTION:UNKNOWN:" + (kind == null ? "NULL" : kind.name()),
                "Получатель атрибуции не определён",
                kind,
                row.getActualRecipientType(),
                row.getActualRecipientProfileId(),
                row.getActualManualPaymentTaskId(),
                row.getActualManualPaymentTaskGeneration(),
                row.getActualManualPaymentTaskTargetKind(),
                false
        );
    }

    private String recipientTypeLabel(ContractorRecipientType type) {
        if (type == ContractorRecipientType.SPECIALIST) {
            return "Специалист";
        }
        if (type == ContractorRecipientType.MANAGER) {
            return "Менеджер";
        }
        if (type == ContractorRecipientType.OWNER) {
            return "Владелец";
        }
        return "Получатель";
    }

    private YearMonth parseMonth(String value) {
        String clean = normalize(value);
        if (clean.isBlank()) {
            return YearMonth.now();
        }
        try {
            return YearMonth.parse(clean);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Укажите месяц в формате YYYY-MM",
                    e
            );
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private record SourceKey(ContractorActualPaymentSourceKind kind, Long id) {
    }

    private record RecipientDescriptor(
            String key,
            String label,
            ContractorCashDestinationKind destinationKind,
            ContractorRecipientType recipientType,
            Long recipientProfileId,
            Long taskId,
            Long taskGeneration,
            ManualPaymentTaskAccountingTargetKind taskTargetKind,
            boolean attributionKnown
    ) {
        private static RecipientDescriptor legacy() {
            return new RecipientDescriptor(
                    LEGACY_UNKNOWN_KEY,
                    LEGACY_UNKNOWN_LABEL,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    false
            );
        }
    }

    private static final class RecipientAccumulator {
        private final RecipientDescriptor descriptor;
        private final Set<SourceKey> sources = new HashSet<>();
        private long amountKopecks;
        private LocalDateTime firstConfirmedAt;
        private LocalDateTime lastConfirmedAt;

        private RecipientAccumulator(RecipientDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        private void add(SourceKey source, long amount, LocalDateTime effectiveAt) {
            sources.add(source);
            amountKopecks = Math.addExact(amountKopecks, amount);
            if (effectiveAt != null
                    && (firstConfirmedAt == null || effectiveAt.isBefore(firstConfirmedAt))) {
                firstConfirmedAt = effectiveAt;
            }
            if (effectiveAt != null
                    && (lastConfirmedAt == null || effectiveAt.isAfter(lastConfirmedAt))) {
                lastConfirmedAt = effectiveAt;
            }
        }

        private ManualPaymentRecipientMonthlySummaryItem toItem() {
            return new ManualPaymentRecipientMonthlySummaryItem(
                    descriptor.label(),
                    "",
                    "",
                    "",
                    "",
                    null,
                    null,
                    descriptor.key(),
                    descriptor.label(),
                    descriptor.destinationKind(),
                    descriptor.recipientType(),
                    descriptor.recipientProfileId(),
                    descriptor.taskId(),
                    descriptor.taskGeneration(),
                    descriptor.taskTargetKind(),
                    descriptor.attributionKnown(),
                    sources.size(),
                    amountKopecks,
                    firstConfirmedAt,
                    lastConfirmedAt
            );
        }
    }
}
