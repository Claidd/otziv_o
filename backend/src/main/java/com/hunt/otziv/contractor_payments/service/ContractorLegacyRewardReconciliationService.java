package com.hunt.otziv.contractor_payments.service;

import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.contractor_payments.dto.ContractorLegacyRewardManualGroupResponse;
import com.hunt.otziv.contractor_payments.dto.ContractorLegacyRewardManualResolutionRequest;
import com.hunt.otziv.contractor_payments.dto.ContractorLegacyRewardReconciliationApplyRequest;
import com.hunt.otziv.contractor_payments.dto.ContractorLegacyRewardReconciliationResponse;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAccountingAuthority;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentRolloutState;
import com.hunt.otziv.contractor_payments.model.ContractorRole;
import com.hunt.otziv.contractor_payments.repository.ContractorLegacyRewardReconciliationRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorLegacyRewardReconciliationRepository.AttestationRow;
import com.hunt.otziv.contractor_payments.repository.ContractorLegacyRewardReconciliationRepository.CandidateRow;
import com.hunt.otziv.contractor_payments.repository.ContractorLegacyRewardReconciliationRepository.DbNow;
import com.hunt.otziv.contractor_payments.repository.ContractorLegacyRewardReconciliationRepository.ItemRow;
import com.hunt.otziv.contractor_payments.repository.ContractorLegacyRewardReconciliationRepository.RunRow;
import com.hunt.otziv.contractor_payments.repository.ContractorLegacyRewardReconciliationRepository.SnapshotItem;
import com.hunt.otziv.review_recovery.service.ReviewRecoveryGateService;
import com.hunt.otziv.z_zp.model.Zp;
import com.hunt.otziv.z_zp.repository.ZpRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ContractorLegacyRewardReconciliationService {

    public static final String AUTO_CONFIRMATION = "ПРИМЕНИТЬ АВТОСВЕРКУ";
    public static final String MANUAL_CONFIRMATION = "ПОДТВЕРДИТЬ РУЧНУЮ СВЕРКУ";
    private static final int EXPIRY_MINUTES = 30;

    private final ContractorLegacyRewardReconciliationRepository repository;
    private final ZpRepository zpRepository;
    private final ContractorPaymentRolloutStateService rolloutStateService;
    private final ContractorPaymentAccountingPhaseService accountingPhaseService;
    private final ContractorCompletionCutoverStateService cutoverStateService;
    private final ReviewRecoveryGateService recoveryGateService;
    private final BusinessAuditService businessAuditService;

    /** PREPARE is intentionally independent of deployment master switches. */
    @Transactional
    public ContractorLegacyRewardReconciliationResponse prepare() {
        String actor = requireActor("ROLE_ADMIN", "ROLE_OWNER");
        requireLegacyShadowWithoutCutover();
        DbNow db = repository.dbNow();
        Map<Long, List<CandidateRow>> groups = repository.findCandidates(db.businessDate()).stream()
                .collect(Collectors.groupingBy(
                        CandidateRow::orderId, LinkedHashMap::new, Collectors.toList()
                ));
        List<SnapshotItem> items = new ArrayList<>();
        for (List<CandidateRow> unsorted : groups.values()) {
            List<CandidateRow> rows = unsorted.stream()
                    .sorted(Comparator.comparingLong(CandidateRow::zpId)).toList();
            String category = category(rows);
            String kind = "DATED_COMPLETED_ORDER".equals(category) ? "AUTO" : "MANUAL";
            List<Target> targets = rows.stream().map(this::target).toList();
            if (targets.stream().anyMatch(target -> target.role() == null || target.source() == null)) {
                kind = "MANUAL";
                category = "IDENTITY_OR_SOURCE_AMBIGUOUS";
            }
            String frozenKind = kind;
            String frozenCategory = category;
            String groupHash = sha256(java.util.stream.IntStream.range(0, rows.size())
                    .mapToObj(index -> canonical(
                            rows.get(index), targets.get(index), frozenKind, frozenCategory
                    ))
                    .collect(Collectors.joining(";")));
            for (int index = 0; index < rows.size(); index++) {
                CandidateRow row = rows.get(index);
                Target target = targets.get(index);
                items.add(new SnapshotItem(
                        row, frozenKind, frozenCategory, groupHash,
                        target.source(), target.role(),
                        sha256(Objects.toString(row.attributionSnapshot(), ""))
                ));
            }
        }
        items.sort(Comparator.comparingLong((SnapshotItem item) -> item.row().orderId())
                .thenComparingLong(item -> item.row().zpId()));
        String snapshotHash = sha256(items.stream()
                .map(item -> item.groupHash() + "|" + canonical(
                        item.row(), new Target(item.targetRole(), item.targetSource()),
                        item.kind(), item.evidenceCategory()
                ))
                .collect(Collectors.joining(";")));
        int autoOrders = distinctOrders(items, "AUTO");
        int manualOrders = distinctOrders(items, "MANUAL");
        int autoRows = countRows(items, "AUTO");
        int manualRows = countRows(items, "MANUAL");
        long runId = repository.insertRun(
                db.businessDate(), snapshotHash, autoOrders, autoRows,
                manualOrders, manualRows, db.now().plusMinutes(EXPIRY_MINUTES), actor
        );
        repository.insertItems(runId, items);
        audit("CONTRACTOR_LEGACY_REWARD_RECONCILIATION_PREPARED", runId, null,
                null, "hash=" + snapshotHash + ";autoRows=" + autoRows + ";manualRows=" + manualRows,
                "Dry-run snapshot only; no financial row was changed");
        return response(repository.lockRun(runId));
    }

    @Transactional(readOnly = true)
    public ContractorLegacyRewardReconciliationResponse latest() {
        return response(repository.findLatestRun());
    }

    @Transactional
    public ContractorLegacyRewardReconciliationResponse applyAutomatic(
            long runId,
            ContractorLegacyRewardReconciliationApplyRequest request
    ) {
        String actor = requireActor("ROLE_OWNER");
        requireConfirmation(request.confirmation(), AUTO_CONFIRMATION);
        requireText(request.reason(), "Причина обязательна");
        DbNow db = repository.dbNow();
        RunRow run = requireApplicableRun(runId, request.snapshotHash(), db);
        List<ItemRow> items = repository.lockItems(runId, "AUTO", null);
        if (items.stream().anyMatch(item -> !"PENDING".equals(item.status()))) {
            throw conflict("AUTO-снимок уже применён или изменён");
        }
        if (repository.lockExistingOrders(items.stream().map(ItemRow::orderId).toList())
                != items.stream().map(ItemRow::orderId).distinct().count()) {
            throw conflict("Заказ из AUTO-снимка исчез после PREPARE");
        }
        lockSources(items);
        Map<Long, Long> rowsByOrder = items.stream().collect(Collectors.groupingBy(
                ItemRow::orderId, Collectors.counting()
        ));
        if (rowsByOrder.entrySet().stream().anyMatch(entry ->
                repository.countActiveRows(entry.getKey()) != entry.getValue())) {
            throw conflict("Состав активных начислений AUTO-заказа изменился после PREPARE");
        }
        requireLegacyShadowWithoutCutover();
        requireApplicableRun(runId, request.snapshotHash(), repository.dbNow());
        requireAutomaticSnapshotUnchanged(run, items);
        items.forEach(this::applyOrVerify);
        repository.markAutoItemsApplied(runId, actor, request.reason().trim(), db.now());
        audit("CONTRACTOR_LEGACY_REWARD_RECONCILIATION_AUTO_APPLIED", runId, null,
                "hash=" + run.snapshotHash(), "classifiedRows=" + items.size(), request.reason().trim());
        return response(repository.lockRun(runId));
    }

    @Transactional
    public ContractorLegacyRewardReconciliationResponse resolveManual(
            long runId,
            long orderId,
            ContractorLegacyRewardManualResolutionRequest request
    ) {
        String actor = requireActor("ROLE_OWNER");
        requireConfirmation(request.confirmation(), MANUAL_CONFIRMATION);
        requireText(request.reason(), "Причина обязательна");
        requireText(request.evidenceReference(), "Ссылка на доказательство обязательна");
        DbNow db = repository.dbNow();
        RunRow run = requireApplicableRun(runId, request.snapshotHash(), db);
        if (request.completedOn() == null || !request.completedOn().isBefore(run.startDate())) {
            throw badRequest("Подтверждённая дата должна быть раньше даты запуска");
        }
        if (recoveryGateService.hasActiveRecoveryTasks(orderId)) {
            throw conflict("У заказа остаются активные задачи восстановления");
        }
        List<ItemRow> items = repository.lockItems(runId, "MANUAL", orderId);
        if (items.isEmpty()
                || items.stream().anyMatch(item -> !"PENDING".equals(item.status()))
                || items.stream().anyMatch(item -> !request.groupHash().equals(item.groupHash()))) {
            throw conflict("MANUAL-группа отсутствует, уже применена или её hash изменился");
        }
        repository.lockExistingOrders(List.of(orderId));
        if (items.stream().anyMatch(item -> item.targetRole() == null || item.targetSource() == null)) {
            throw conflict("Получатель не выводится из неизменяемых связей");
        }
        boolean typedAttestation = items.stream().allMatch(this::alreadyTarget);
        if (typedAttestation && items.stream().anyMatch(item -> !repository.exactOriginalSnapshot(item))) {
            throw conflict("Типизированная MANUAL-группа изменилась после PREPARE");
        }
        if (repository.countActiveRows(orderId) != items.size()) {
            throw conflict("Состав активных начислений заказа изменился после PREPARE");
        }
        lockSources(items);
        requireLegacyShadowWithoutCutover();
        requireApplicableRun(runId, request.snapshotHash(), repository.dbNow());
        items.forEach(this::applyOrVerify);
        repository.markManualItemsApplied(
                runId, orderId, request.completedOn(), request.evidenceReference().trim(),
                request.reason().trim(), actor, db.now()
        );
        audit("CONTRACTOR_LEGACY_REWARD_RECONCILIATION_MANUAL_APPLIED", runId, orderId,
                "groupHash=" + request.groupHash(),
                "completedOn=" + request.completedOn() + ";rows=" + items.size(),
                request.reason().trim() + "; evidence=" + request.evidenceReference().trim());
        return response(repository.lockRun(runId));
    }

    /**
     * Signed evidence is authoritative only while every active row still
     * matches the attested identity, amount, classification and group.
     */
    @Transactional(readOnly = true)
    public Optional<LocalDate> authoritativeCompletedOn(Long orderId, LocalDate cutoff) {
        if (orderId == null || orderId <= 0 || cutoff == null) {
            return Optional.empty();
        }
        List<AttestationRow> found = repository.findAppliedManualAttestations(orderId, cutoff);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        long newestRun = found.get(0).runId();
        List<AttestationRow> attested = found.stream()
                .filter(row -> row.runId() == newestRun).toList();
        List<Zp> current = zpRepository.findByOrderIdAndActiveTrue(orderId).stream()
                .sorted(Comparator.comparing(Zp::getId)).toList();
        if (current.isEmpty() || current.size() != attested.size()) {
            return Optional.empty();
        }
        Map<Long, AttestationRow> byId = attested.stream()
                .collect(Collectors.toMap(AttestationRow::zpId, row -> row));
        LocalDate completedOn = attested.get(0).completedOn();
        String groupHash = attested.get(0).groupHash();
        for (Zp row : current) {
            AttestationRow evidence = byId.get(row.getId());
            if (evidence == null
                    || !Objects.equals(completedOn, evidence.completedOn())
                    || !Objects.equals(groupHash, evidence.groupHash())
                    || !matchesAttestation(row, evidence)) {
                return Optional.empty();
            }
        }
        return Optional.of(completedOn);
    }

    private RunRow requireApplicableRun(long runId, String expectedHash, DbNow db) {
        RunRow run = repository.lockRun(runId);
        if (run == null || !Objects.equals(run.snapshotHash(), expectedHash)) {
            throw conflict("Snapshot hash не совпадает");
        }
        if (!run.startDate().equals(db.businessDate()) || !db.now().isBefore(run.expiresAt())) {
            throw conflict("PREPARE-снимок истёк; выполните PREPARE повторно");
        }
        return run;
    }

    private void lockSources(List<ItemRow> items) {
        for (ItemRow item : items.stream().sorted(Comparator.comparingLong(ItemRow::zpId)).toList()) {
            zpRepository.findByIdForContractorLedgerUpdate(item.zpId())
                    .orElseThrow(() -> conflict("Начисление исчезло после PREPARE: " + item.zpId()));
        }
    }

    private void applyOrVerify(ItemRow item) {
        boolean alreadyTarget = alreadyTarget(item);
        if (alreadyTarget) {
            if (!repository.exactOriginalSnapshot(item)) {
                throw conflict("Начисление изменилось после PREPARE: " + item.zpId());
            }
        } else if (repository.casApply(item) != 1) {
            throw conflict("CAS не подтвердил неизменность начисления: " + item.zpId());
        }
    }

    private boolean alreadyTarget(ItemRow item) {
        return Objects.equals(item.originalSource(), item.targetSource())
                && Objects.equals(item.originalRole(), item.targetRole())
                && item.originalFinal() == item.targetFinal();
    }

    /**
     * Row CAS alone cannot detect a newly added reward or changed completion
     * evidence. Rebuild the complete candidate snapshot while sources and the
     * rollout mutex are held and compare its canonical hash.
     */
    private void requireAutomaticSnapshotUnchanged(RunRow run, List<ItemRow> automaticItems) {
        List<CandidateRow> current = repository.findCandidates(run.startDate());
        Map<Long, List<CandidateRow>> groups = current.stream().collect(Collectors.groupingBy(
                CandidateRow::orderId, LinkedHashMap::new, Collectors.toList()
        ));
        List<SnapshotItem> rebuilt = new ArrayList<>();
        for (List<CandidateRow> unsorted : groups.values()) {
            List<CandidateRow> rows = unsorted.stream()
                    .sorted(Comparator.comparingLong(CandidateRow::zpId)).toList();
            String category = category(rows);
            String kind = "DATED_COMPLETED_ORDER".equals(category) ? "AUTO" : "MANUAL";
            List<Target> targets = rows.stream().map(this::target).toList();
            if (targets.stream().anyMatch(target -> target.role() == null || target.source() == null)) {
                kind = "MANUAL";
                category = "IDENTITY_OR_SOURCE_AMBIGUOUS";
            }
            String frozenKind = kind;
            String frozenCategory = category;
            String groupHash = sha256(java.util.stream.IntStream.range(0, rows.size())
                    .mapToObj(index -> canonical(
                            rows.get(index), targets.get(index), frozenKind, frozenCategory
                    ))
                    .collect(Collectors.joining(";")));
            for (int index = 0; index < rows.size(); index++) {
                CandidateRow row = rows.get(index);
                Target target = targets.get(index);
                rebuilt.add(new SnapshotItem(
                        row, frozenKind, frozenCategory, groupHash,
                        target.source(), target.role(),
                        sha256(Objects.toString(row.attributionSnapshot(), ""))
                ));
            }
        }
        rebuilt.sort(Comparator.comparingLong((SnapshotItem item) -> item.row().orderId())
                .thenComparingLong(item -> item.row().zpId()));
        String hash = sha256(rebuilt.stream()
                .map(item -> item.groupHash() + "|" + canonical(
                        item.row(), new Target(item.targetRole(), item.targetSource()),
                        item.kind(), item.evidenceCategory()
                ))
                .collect(Collectors.joining(";")));
        if (!run.snapshotHash().equals(hash)
                || automaticItems.size() != rebuilt.stream()
                        .filter(item -> "AUTO".equals(item.kind())).count()) {
            throw conflict("Полный PREPARE-снимок или evidence заказа изменился");
        }
    }

    private void requireLegacyShadowWithoutCutover() {
        ContractorPaymentRolloutState rollout = rolloutStateService.lockCurrent();
        if (rollout.getAccountingAuthority() != ContractorPaymentAccountingAuthority.LEGACY
                || accountingPhaseService.lockCurrent() != ContractorAllocationMode.SHADOW
                || cutoverStateService.lockedStartDate().isPresent()) {
            throw conflict("Сверка разрешена только в LEGACY + SHADOW, без cutover");
        }
    }

    private String category(List<CandidateRow> rows) {
        if (rows.stream().anyMatch(CandidateRow::activeRecovery)) {
            return "ACTIVE_REVIEW_RECOVERY";
        }
        if (rows.stream().anyMatch(row -> !row.orderExists())) {
            return "DELETED_ORDER";
        }
        return rows.stream().allMatch(CandidateRow::datedPreCutoff)
                ? "DATED_COMPLETED_ORDER"
                : "COMPLETION_DATE_REQUIRES_EVIDENCE";
    }

    private Target target(CandidateRow row) {
        ContractorRole role = row.inferredRole();
        if (role == null || (row.role() != null && !row.role().equals(role.name()))) {
            return new Target(null, null);
        }
        String expected = role == ContractorRole.SPECIALIST
                ? ContractorRewardSourceCodes.LEGACY_ORDER_SPECIALIST
                : ContractorRewardSourceCodes.LEGACY_ORDER_MANAGER;
        if (ContractorRewardSourceCodes.LEGACY_PERFORMER_PRODUCT.equals(row.source())) {
            return new Target(role, ContractorRewardSourceCodes.LEGACY_PERFORMER_PRODUCT);
        }
        if (row.role() != null
                && row.role().equals(role.name())
                && ContractorRewardSourceCodes.isCompletionBased(row.source())
                && ContractorRewardSourceCodes.isLedgerSourceCompatible(row.source(), role)) {
            return new Target(role, row.source());
        }
        if (row.source() != null && !row.source().isBlank()) {
            return expected.equals(row.source())
                    ? new Target(role, expected)
                    : new Target(null, null);
        }
        return new Target(role, expected);
    }

    private ContractorLegacyRewardReconciliationResponse response(RunRow run) {
        if (run == null) {
            return ContractorLegacyRewardReconciliationResponse.empty();
        }
        List<ItemRow> manual = repository.findManualItems(run.id());
        List<ContractorLegacyRewardManualGroupResponse> groups = manual.stream()
                .collect(Collectors.groupingBy(ItemRow::orderId, LinkedHashMap::new, Collectors.toList()))
                .values().stream()
                .map(rows -> new ContractorLegacyRewardManualGroupResponse(
                        rows.get(0).orderId(), rows.get(0).groupHash(),
                        rows.get(0).evidenceCategory(),
                        rows.stream().allMatch(row -> "APPLIED".equals(row.status()))
                                ? "APPLIED" : "PENDING",
                        rows.size(), rows.get(0).completedOn(), rows.get(0).evidenceReference()
                )).toList();
        int remaining = (int) groups.stream()
                .filter(group -> "PENDING".equals(group.status())).count();
        return new ContractorLegacyRewardReconciliationResponse(
                run.id(), run.startDate(), run.status(), run.snapshotHash(),
                run.autoOrders(), run.autoRows(), repository.countPending(run.id(), "AUTO"),
                run.manualOrders(), run.manualRows(), remaining,
                run.createdAt(), run.expiresAt(), groups
        );
    }

    private boolean matchesAttestation(Zp row, AttestationRow evidence) {
        return row.isActive() == evidence.active()
                && Objects.equals(row.getUserId(), evidence.userId())
                && Objects.equals(row.getProfessionId(), evidence.professionId())
                && moneyEquals(row.getSum(), evidence.amount())
                && row.getAmount() == evidence.units()
                && Objects.equals(row.getCreated(), evidence.occurredOn())
                && moneyEquals(row.getRewardBasis(), evidence.rewardBasis())
                && Objects.equals(sha256(Objects.toString(row.getAttributionSnapshot(), "")),
                        evidence.attributionSnapshotHash())
                && Objects.equals(row.getSource(), evidence.targetSource())
                && row.getContractorRole() != null
                && row.getContractorRole().name().equals(evidence.targetRole())
                && row.isAttributionFinal() == evidence.targetFinal();
    }

    private String canonical(CandidateRow row, Target target, String kind, String category) {
        return row.zpId() + "|" + row.orderId() + "|" + row.userId() + "|" + row.professionId()
                + "|" + decimal(row.amount()) + "|" + row.units() + "|" + row.occurredOn()
                + "|" + row.updatedAt() + "|" + row.active() + "|" + nullToken(row.source())
                + "|" + nullToken(row.role()) + "|" + row.attributionFinal()
                + "|" + decimal(row.rewardBasis())
                + "|" + sha256(Objects.toString(row.attributionSnapshot(), ""))
                + "|" + nullToken(target.role()) + "|" + nullToken(target.source())
                + "|" + kind + "|" + category;
    }

    private int distinctOrders(List<SnapshotItem> items, String kind) {
        return (int) items.stream().filter(item -> kind.equals(item.kind()))
                .map(item -> item.row().orderId()).distinct().count();
    }

    private int countRows(List<SnapshotItem> items, String kind) {
        return (int) items.stream().filter(item -> kind.equals(item.kind())).count();
    }

    private boolean moneyEquals(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    private String decimal(BigDecimal value) {
        return value == null ? "<null>" : value.stripTrailingZeros().toPlainString();
    }

    private String nullToken(Object value) {
        return value == null ? "<null>" : String.valueOf(value);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void audit(String action, long runId, Long orderId,
                       Object oldValue, Object newValue, String details) {
        businessAuditService.recordRequiredInCurrentTransaction(
                action, "CONTRACTOR_LEGACY_RECONCILIATION", runId,
                orderId, null, oldValue, newValue, details
        );
    }

    private void requireConfirmation(String actual, String required) {
        if (actual == null || !required.equals(actual.trim())) {
            throw badRequest("Текст подтверждения не совпадает");
        }
    }

    private void requireText(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw badRequest(message);
        }
    }

    private String requireActor(String... allowedRoles) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Аудируемая сверка требует авторизованного оператора"
            );
        }
        boolean allowed = authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .filter(Objects::nonNull)
                .anyMatch(authority -> java.util.Arrays.stream(allowedRoles)
                        .anyMatch(role -> role.equalsIgnoreCase(authority)));
        if (!allowed) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Недостаточно прав для аудируемой сверки"
            );
        }
        String actor = authentication.getName().trim();
        return actor.length() <= 150 ? actor : actor.substring(0, 150);
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private record Target(ContractorRole role, String source) {}
}
