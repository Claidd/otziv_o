package com.hunt.otziv.common_billing.service;

import static com.hunt.otziv.common_billing.service.CommonBillingCompanyReconciliationWorkflow.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceMembershipWorkflow.*;
import static com.hunt.otziv.common_billing.service.CommonInvoicePaymentRouteWorkflow.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceManualPaymentWorkflow.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceTochkaReconciliationService.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceDeliveryService.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceDetailsAssembler.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceInitializationService.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceCancellationService.*;
import static com.hunt.otziv.common_billing.service.CommonInvoicePresenter.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceSettlementService.*;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.common_billing.dto.CommonBillingAccountRequest;
import com.hunt.otziv.common_billing.dto.CommonBillingAccountResponse;
import com.hunt.otziv.common_billing.dto.CommonBillingCompanyResponse;
import com.hunt.otziv.common_billing.dto.CommonInvoiceSummaryResponse;
import com.hunt.otziv.common_billing.model.CommonBillingAccount;
import com.hunt.otziv.common_billing.model.CommonBillingAccountCompany;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonBillingAccountCompanyRepository;
import com.hunt.otziv.common_billing.repository.CommonBillingAccountRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.payments.model.InvoicePaymentMode;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.CaughtFailureStage.CLOSE_ORDER;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.CaughtFailureStage.OPEN_NEXT_ORDER;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.TransactionFlow.COMMON_INVOICE_CLOSE;

/** Owns common-payer configuration, company membership and the existing normalized account views. */
@Service
@Slf4j
@RequiredArgsConstructor
public class CommonBillingAccountWorkflow {

    private final CommonBillingCompanyReconciliationWorkflow companyReconciliation;

    private final CommonInvoiceMembershipWorkflow invoiceMembershipWorkflow;

    private final CommonInvoiceDeliveryService invoiceDelivery;

    private final CommonInvoiceInitializationService invoiceInitialization;

    private final CommonInvoicePresenter invoicePresenter;

    private final CommonInvoiceSettlementService settlementService;

    static final int BULK_QUERY_CHUNK_SIZE = 500;

    static final Set<CommonInvoiceStatus> CURRENT_INVOICE_STATUSES = Set.of(CommonInvoiceStatus.COLLECTING, CommonInvoiceStatus.READY, CommonInvoiceStatus.INVOICED, CommonInvoiceStatus.REMINDER, CommonInvoiceStatus.PARTIALLY_PAID, CommonInvoiceStatus.NEEDS_ATTENTION);

    static final Set<CommonInvoiceStatus> MUTABLE_INVOICE_STATUSES = Set.of(CommonInvoiceStatus.COLLECTING, CommonInvoiceStatus.READY, CommonInvoiceStatus.INVOICED, CommonInvoiceStatus.REMINDER, CommonInvoiceStatus.PARTIALLY_PAID);

    private final CommonBillingAccountRepository accountRepository;

    private final CommonBillingAccountCompanyRepository accountCompanyRepository;

    private final CommonInvoiceRepository invoiceRepository;

    private final CommonInvoiceOrderRepository invoiceOrderRepository;

    private final CompanyRepository companyRepository;

    private final ManagerRepository managerRepository;

    @Transactional
    public List<CommonBillingAccountResponse> accounts() {
        Set<Long> visibleManagerIds = visibleManagerIdsForCurrentUser();
        List<CommonBillingAccount> accounts = accountRepository.findAllForAdmin();
        List<Long> ids = accounts.stream().map(CommonBillingAccount::getId).toList();
        Map<Long, List<CommonBillingAccountCompany>> companies = accountCompanyRepository.findByAccountIds(ids).stream().collect(Collectors.groupingBy(link -> link.getAccount().getId()));
        List<CommonBillingAccount> visibleAccounts = accounts.stream().filter(account -> accountVisibleToManager(account, companies.getOrDefault(account.getId(), List.of()), visibleManagerIds)).toList();
        Map<Long, CommonInvoiceSummaryResponse> currentInvoices = currentInvoiceSummaries(visibleAccounts);
        return visibleAccounts.stream().map(account -> toAccountResponse(account, companies.getOrDefault(account.getId(), List.of()), currentInvoices.get(account.getId()))).toList();
    }

    @Transactional
    public List<CommonBillingAccountResponse> accountsForCompany(Long companyId) {
        Company company = companyRepository.findById(companyId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Компания не найдена"));
        ensureCompanyVisibleForCurrentUser(company);
        Set<Long> visibleManagerIds = visibleManagerIdsForCurrentUser();
        List<CommonBillingAccount> accounts = accountCompanyRepository.findLinksForCompany(companyId).stream().map(CommonBillingAccountCompany::getAccount).filter(Objects::nonNull).distinct().toList();
        List<Long> ids = accounts.stream().map(CommonBillingAccount::getId).toList();
        Map<Long, List<CommonBillingAccountCompany>> companies = ids.isEmpty() ? Map.of() : accountCompanyRepository.findByAccountIds(ids).stream().collect(Collectors.groupingBy(link -> link.getAccount().getId()));
        List<CommonBillingAccount> visibleAccounts = accounts.stream().filter(account -> accountVisibleToManager(account, companies.getOrDefault(account.getId(), List.of()), visibleManagerIds)).toList();
        Map<Long, CommonInvoiceSummaryResponse> currentInvoices = currentInvoiceSummaries(visibleAccounts);
        return visibleAccounts.stream().map(account -> toAccountResponse(account, companies.getOrDefault(account.getId(), List.of()), currentInvoices.get(account.getId()))).toList();
    }

    @Transactional
    public CommonBillingAccountResponse createAccount(CommonBillingAccountRequest request) {
        CommonBillingAccount account = new CommonBillingAccount();
        applyAccountRequest(account, request);
        ensureAccountRequestVisibleForCurrentUser(account, request == null ? List.of() : request.companyIds(), true);
        account = accountRepository.save(account);
        replaceCompanies(account, request == null ? List.of() : request.companyIds());
        return account(account.getId());
    }

    @Transactional(readOnly = true)
    public CommonBillingAccountResponse account(Long accountId) {
        CommonBillingAccount account = accountRepository.findByIdWithRelations(accountId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий плательщик не найден"));
        ensureAccountVisibleForCurrentUser(account);
        return toAccountResponse(account, accountCompanyRepository.findByAccount_IdOrderByCompany_TitleAsc(accountId));
    }

    @Transactional
    public CommonBillingAccountResponse updateAccount(Long accountId, CommonBillingAccountRequest request) {
        CommonBillingAccount accountSnapshot = accountRepository.findByIdWithRelations(accountId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий плательщик не найден"));
        ensureAccountVisibleForCurrentUser(accountSnapshot);
        boolean snapshotEnabled = accountSnapshot.isEnabled();
        boolean snapshotTargetDisabled = request != null && (Boolean.FALSE.equals(request.enabled()) || (request.enabled() == null && !snapshotEnabled));
        if (snapshotTargetDisabled) {
            // No Account/link field has been changed yet. The detach path can
            // therefore establish Order -> Account -> Invoice first.
            detachCurrentAccountOrders(accountId);
        }
        CommonBillingAccount account = lockFreshAccountAfterOrderPrelude(accountId);
        ensureAccountVisibleForCurrentUser(account);
        if (request != null && request.enabled() == null && snapshotEnabled != account.isEnabled()) {
            throw invoiceMembershipChanged("состояние общего плательщика изменилось; повторите сохранение");
        }
        applyAccountRequest(account, request);
        ensureAccountRequestVisibleForCurrentUser(account, request == null ? null : request.companyIds(), false);
        accountRepository.save(account);
        if (!account.isEnabled()) {
            disableAccountCompanies(account);
            return account(accountId);
        }
        if (request != null && request.companyIds() != null) {
            replaceCompanies(account, request.companyIds());
        }
        return account(accountId);
    }

    @Transactional
    public CommonBillingAccountResponse addCompany(Long accountId, Long companyId) {
        CommonBillingAccount accountSnapshot = accountRepository.findByIdWithRelations(accountId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий плательщик не найден"));
        ensureAccountVisibleForCurrentUser(accountSnapshot);
        CommonBillingAccount account = lockFreshAccountAfterOrderPrelude(accountId);
        ensureAccountVisibleForCurrentUser(account);
        Company company = companyRepository.findById(companyId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Компания не найдена"));
        addCompanyToAccount(account, company);
        return account(accountId);
    }

    @Transactional
    public CommonBillingAccountResponse removeCompany(Long accountId, Long companyId, boolean detachCurrent) {
        CommonBillingAccount accountSnapshot = accountRepository.findByIdWithRelations(accountId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий плательщик не найден"));
        ensureAccountVisibleForCurrentUser(accountSnapshot);
        if (detachCurrent) {
            detachCurrentCompanyOrders(accountId, companyId);
        }
        CommonBillingAccount account = lockFreshAccountAfterOrderPrelude(accountId);
        ensureAccountVisibleForCurrentUser(account);
        Long currentLinkId = accountCompanyRepository.findByAccount_IdAndCompany_Id(accountId, companyId).map(CommonBillingAccountCompany::getId).orElse(null);
        if (currentLinkId != null) {
            CommonBillingAccountCompany link = accountCompanyRepository.findByIdForUpdate(currentLinkId).orElseThrow(() -> invoiceMembershipChanged("связь компании с общим плательщиком исчезла"));
            if (link.getAccount() == null || link.getCompany() == null || !Objects.equals(accountId, link.getAccount().getId()) || !Objects.equals(companyId, link.getCompany().getId())) {
                throw invoiceMembershipChanged("связь компании сменила плательщика");
            }
            link.setEnabled(false);
            clearCompanyReconcileState(link);
            saveAccountCompany(link);
        }
        return account(accountId);
    }

    void applyAccountRequest(CommonBillingAccount account, CommonBillingAccountRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Заполните параметры общего плательщика");
        }
        String name = normalize(request.name());
        if (name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Название общего плательщика обязательно");
        }
        account.setName(limit(name, 160));
        if (request.enabled() != null) {
            account.setEnabled(request.enabled());
        }
        if (request.autoRepeatOrders() != null) {
            account.setAutoRepeatOrders(request.autoRepeatOrders());
        }
        account.setManager(request.managerId() == null ? null : eligibleCommonBillingManager(request.managerId()));
        account.setInvoiceCompany(request.invoiceCompanyId() == null ? null : companyRepository.findById(request.invoiceCompanyId()).orElse(null));
    }

    Manager eligibleCommonBillingManager(Long managerId) {
        Manager manager = managerRepository.findById(managerId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Менеджер не найден"));
        if (!isEligibleCommonBillingManager(manager)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Менеджер не найден");
        }
        return manager;
    }

    boolean isEligibleCommonBillingManager(Manager manager) {
        return invoiceInitialization.isEligibleCommonBillingManager(manager);
    }

    void replaceCompanies(CommonBillingAccount account, List<Long> companyIds) {
        if (companyIds == null) {
            return;
        }
        if (account != null && !account.isEnabled()) {
            disableAccountCompanies(account);
            return;
        }
        Map<Long, CommonBillingAccountCompany> existing = accountCompanyRepository.findByAccount_IdOrderByCompany_TitleAsc(account.getId()).stream().collect(Collectors.toMap(link -> link.getCompany().getId(), Function.identity()));
        Set<Long> requested = companyIds.stream().filter(id -> id != null && id > 0).collect(Collectors.toSet());
        requested.forEach(companyId -> ensureCompanyNotEnabledInAnotherAccount(account.getId(), companyId));
        for (Map.Entry<Long, CommonBillingAccountCompany> entry : existing.entrySet()) {
            boolean wasEnabled = entry.getValue().isEnabled();
            boolean shouldEnable = requested.contains(entry.getKey());
            if (wasEnabled && !shouldEnable && account.getId() != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Исключайте компанию из общего счета отдельной кнопкой, чтобы явно выбрать судьбу текущих позиций");
            }
            entry.getValue().setEnabled(shouldEnable);
            boolean reconciliationStarted = !wasEnabled && shouldEnable;
            if (reconciliationStarted) {
                markCompanyReconcilePending(entry.getValue());
            } else if (!shouldEnable) {
                clearCompanyReconcileState(entry.getValue());
            }
            saveAccountCompany(entry.getValue());
            if (reconciliationStarted) {
                scheduleCompanyReconcileAfterCommit(entry.getValue().getId());
            }
        }
        for (Long companyId : requested) {
            if (existing.containsKey(companyId)) {
                continue;
            }
            Company company = companyRepository.findById(companyId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Компания не найдена"));
            addCompanyToAccount(account, company);
        }
    }

    void addCompanyToAccount(CommonBillingAccount account, Company company) {
        if (account == null || account.getId() == null || company == null || company.getId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Не выбраны общий плательщик или компания");
        }
        if (!account.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Нельзя включить компанию в отключенный общий счет");
        }
        ensureCompanyVisibleForCurrentUser(company);
        ensureCompanyNotEnabledInAnotherAccount(account.getId(), company.getId());
        CommonBillingAccountCompany link = accountCompanyRepository.findByAccount_IdAndCompany_Id(account.getId(), company.getId()).orElseGet(CommonBillingAccountCompany::new);
        boolean reconciliationRequired = link.getId() == null || !link.isEnabled() || link.isReconcilePending();
        link.setAccount(account);
        link.setCompany(company);
        link.setEnabled(true);
        if (reconciliationRequired) {
            markCompanyReconcilePending(link);
        }
        saveAccountCompany(link);
        if (account.getInvoiceCompany() == null) {
            account.setInvoiceCompany(company);
            accountRepository.save(account);
        }
        if (reconciliationRequired) {
            scheduleCompanyReconcileAfterCommit(link.getId());
        }
    }

    void saveAccountCompany(CommonBillingAccountCompany link) {
        try {
            accountCompanyRepository.saveAndFlush(link);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Компания уже включена в другой активный общий счет", e);
        }
    }

    void disableAccountCompanies(CommonBillingAccount account) {
        if (account == null || account.getId() == null) {
            return;
        }
        for (CommonBillingAccountCompany link : accountCompanyRepository.findByAccount_IdOrderByCompany_TitleAsc(account.getId())) {
            if (link.isEnabled()) {
                link.setEnabled(false);
                clearCompanyReconcileState(link);
                saveAccountCompany(link);
            }
        }
    }

    void detachCurrentCompanyOrders(Long accountId, Long companyId) {
        Optional<CommonInvoice> optionalInvoice = activeInvoiceSnapshot(accountId).flatMap(snapshot -> lockedInvoice(snapshot.getId()));
        if (optionalInvoice.isEmpty()) {
            return;
        }
        CommonInvoice invoice = optionalInvoice.get();
        ensureCommonPaymentRouteAllowsCompositionChange(invoice);
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId());
        List<CommonInvoiceOrder> detachItems = items.stream().filter(item -> item.getOrder() != null && item.getOrder().getCompany() != null && companyId.equals(item.getOrder().getCompany().getId()) && !item.isPaid()).toList();
        if (detachItems.isEmpty()) {
            return;
        }
        detachItems.forEach(item -> restoreDetachedOrderStatus(item.getOrder(), item.getOriginalOrderStatusTitle()));
        invoiceOrderRepository.deleteAll(detachItems);
        List<CommonInvoiceOrder> remainingItems = invoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId());
        recalculateInvoice(invoice, remainingItems);
        if (remainingItems.isEmpty()) {
            invoice.setStatus(CommonInvoiceStatus.DISABLED);
            invoice.setNextReminderAt(null);
            invoice.setLastError("empty: в общем счете нет заказов");
            invoiceRepository.save(invoice);
        } else if (isInvoiceReady(invoice.getId()) && invoice.getStatus() == CommonInvoiceStatus.COLLECTING) {
            invoice.setStatus(CommonInvoiceStatus.READY);
            invoiceRepository.save(invoice);
            markInvoiceOrdersPublished(remainingItems);
        }
    }

    void detachCurrentAccountOrders(Long accountId) {
        Optional<CommonInvoice> optionalInvoice = activeInvoiceSnapshot(accountId).flatMap(snapshot -> lockedInvoice(snapshot.getId()));
        if (optionalInvoice.isEmpty()) {
            return;
        }
        CommonInvoice invoice = optionalInvoice.get();
        ensureCommonPaymentRouteAllowsCompositionChange(invoice);
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId());
        List<CommonInvoiceOrder> detachItems = items.stream().filter(item -> item.getOrder() != null && !item.isPaid()).toList();
        detachItems.forEach(item -> restoreDetachedOrderStatus(item.getOrder(), item.getOriginalOrderStatusTitle()));
        if (!detachItems.isEmpty()) {
            invoiceOrderRepository.deleteAll(detachItems);
            recalculateInvoice(invoice, invoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId()));
        }
        invoice.setStatus(CommonInvoiceStatus.DISABLED);
        invoice.setNextReminderAt(null);
        invoice.setLastError("disabled: общий счет выключен, неоплаченные заказы отключены");
        invoiceRepository.save(invoice);
    }

    Optional<CommonInvoice> lockedInvoice(Long invoiceId) {
        return settlementService.lockedInvoice(invoiceId);
    }

    CommonBillingAccount lockFreshAccountAfterOrderPrelude(Long accountId) {
        return settlementService.lockFreshAccountAfterOrderPrelude(accountId);
    }

    void ensureCompanyNotEnabledInAnotherAccount(Long accountId, Long companyId) {
        invoiceMembershipWorkflow.ensureCompanyNotEnabledInAnotherAccount(accountId, companyId);
    }

    void scheduleCompanyReconcileAfterCommit(Long linkId) {
        companyReconciliation.scheduleCompanyReconcileAfterCommit(linkId);
    }

    void markCompanyReconcilePending(CommonBillingAccountCompany link) {
        companyReconciliation.markCompanyReconcilePending(link);
    }

    void clearCompanyReconcileState(CommonBillingAccountCompany link) {
        companyReconciliation.clearCompanyReconcileState(link);
    }

    ResponseStatusException invoiceMembershipChanged(String detail) {
        return settlementService.invoiceMembershipChanged(detail);
    }

    Optional<CommonInvoice> activeInvoiceSnapshot(Long accountId) {
        if (accountId == null) {
            return Optional.empty();
        }
        return invoiceRepository.findCurrentForAccount(accountId, MUTABLE_INVOICE_STATUSES, PageRequest.of(0, 1)).stream().findFirst();
    }

    boolean isInvoiceReady(Long invoiceId) {
        return invoiceMembershipWorkflow.isInvoiceReady(invoiceId);
    }

    void recalculateInvoice(CommonInvoice invoice) {
        settlementService.recalculateInvoice(invoice);
    }

    void recalculateInvoice(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        settlementService.recalculateInvoice(invoice, items);
    }

    void refreshInvoiceAmounts(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        settlementService.refreshInvoiceAmounts(invoice, items);
    }

    CommonBillingAccountResponse toAccountResponse(CommonBillingAccount account, List<CommonBillingAccountCompany> companyLinks) {
        CommonInvoiceSummaryResponse current = invoiceRepository.findCurrentPresentationForAccount(account.getId(), CURRENT_INVOICE_STATUSES, PageRequest.of(0, 1)).stream().findFirst().map(invoice -> {
            List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId());
            refreshInvoiceAmounts(invoice, items);
            return toInvoiceSummary(invoice, items);
        }).orElse(null);
        return toAccountResponse(account, companyLinks, current);
    }

    CommonBillingAccountResponse toAccountResponse(CommonBillingAccount account, List<CommonBillingAccountCompany> companyLinks, CommonInvoiceSummaryResponse current) {
        return new CommonBillingAccountResponse(account.getId(), account.getName(), account.isEnabled(), account.isAutoRepeatOrders(), account.getManager() == null ? null : account.getManager().getId(), managerName(account.getManager()), account.getInvoiceCompany() == null ? null : account.getInvoiceCompany().getId(), account.getInvoiceCompany() == null ? null : account.getInvoiceCompany().getTitle(), companyLinks.stream().map(this::toCompanyResponse).toList(), current, (account.getInvoicePaymentMode() == null ? InvoicePaymentMode.AUTO_ROUTING : account.getInvoicePaymentMode()).name());
    }

    Map<Long, CommonInvoiceSummaryResponse> currentInvoiceSummaries(List<CommonBillingAccount> accounts) {
        if (accounts == null || accounts.isEmpty()) {
            return Map.of();
        }
        List<Long> accountIds = accounts.stream().map(CommonBillingAccount::getId).filter(Objects::nonNull).distinct().toList();
        if (accountIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, CommonInvoice> invoiceByAccountId = new HashMap<>();
        for (int start = 0; start < accountIds.size(); start += BULK_QUERY_CHUNK_SIZE) {
            List<Long> chunk = accountIds.subList(start, Math.min(start + BULK_QUERY_CHUNK_SIZE, accountIds.size()));
            for (CommonInvoice invoice : invoiceRepository.findLatestCurrentForAccounts(chunk, CURRENT_INVOICE_STATUSES)) {
                if (invoice == null || invoice.getId() == null || invoice.getAccount() == null || invoice.getAccount().getId() == null) {
                    continue;
                }
                invoiceByAccountId.merge(invoice.getAccount().getId(), invoice, (left, right) -> left.getId() >= right.getId() ? left : right);
            }
        }
        if (invoiceByAccountId.isEmpty()) {
            return Map.of();
        }
        List<Long> invoiceIds = invoiceByAccountId.values().stream().map(CommonInvoice::getId).distinct().toList();
        Map<Long, List<CommonInvoiceOrder>> itemsByInvoiceId = new HashMap<>();
        for (int start = 0; start < invoiceIds.size(); start += BULK_QUERY_CHUNK_SIZE) {
            List<Long> chunk = invoiceIds.subList(start, Math.min(start + BULK_QUERY_CHUNK_SIZE, invoiceIds.size()));
            for (CommonInvoiceOrder item : invoiceOrderRepository.findByInvoiceIdsWithOrders(chunk)) {
                if (item == null || item.getInvoice() == null || item.getInvoice().getId() == null) {
                    continue;
                }
                itemsByInvoiceId.computeIfAbsent(item.getInvoice().getId(), ignored -> new ArrayList<>()).add(item);
            }
        }
        Map<Long, CommonInvoiceSummaryResponse> summaries = new HashMap<>();
        invoiceByAccountId.forEach((accountId, invoice) -> {
            List<CommonInvoiceOrder> items = itemsByInvoiceId.getOrDefault(invoice.getId(), List.of());
            refreshInvoiceAmounts(invoice, items);
            summaries.put(accountId, toInvoiceSummary(invoice, items));
        });
        return summaries;
    }

    CommonInvoiceSummaryResponse toInvoiceSummary(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        return invoicePresenter.toInvoiceSummary(invoice, items);
    }

    CommonInvoiceSummaryResponse toInvoiceSummary(CommonInvoice invoice, List<CommonInvoiceOrder> items, String tbankTerminalLabel) {
        return invoicePresenter.toInvoiceSummary(invoice, items, tbankTerminalLabel);
    }

    boolean accountVisibleToManager(CommonBillingAccount account, List<CommonBillingAccountCompany> companyLinks, Set<Long> visibleManagerIds) {
        if (visibleManagerIds == null) {
            return true;
        }
        if (visibleManagerIds.isEmpty() || account == null) {
            return false;
        }
        Manager accountManager = account.getManager();
        if (accountManager != null && accountManager.getId() != null && visibleManagerIds.contains(accountManager.getId())) {
            return true;
        }
        List<CommonBillingAccountCompany> enabledLinks = companyLinks == null ? List.of() : companyLinks.stream().filter(CommonBillingAccountCompany::isEnabled).toList();
        return !enabledLinks.isEmpty() && enabledLinks.stream().map(CommonBillingAccountCompany::getCompany).map(company -> company == null ? null : company.getManager()).allMatch(manager -> manager != null && manager.getId() != null && visibleManagerIds.contains(manager.getId()));
    }

    void ensureAccountVisibleForCurrentUser(CommonBillingAccount account) {
        Set<Long> visibleManagerIds = visibleManagerIdsForCurrentUser();
        if (visibleManagerIds == null) {
            return;
        }
        List<CommonBillingAccountCompany> companyLinks = account == null || account.getId() == null ? List.of() : accountCompanyRepository.findByAccount_IdOrderByCompany_TitleAsc(account.getId());
        if (!accountVisibleToManager(account, companyLinks, visibleManagerIds)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Общий плательщик недоступен текущему пользователю");
        }
    }

    void ensureAccountRequestVisibleForCurrentUser(CommonBillingAccount account, List<Long> companyIds, boolean requireVisibleAnchor) {
        Set<Long> visibleManagerIds = visibleManagerIdsForCurrentUser();
        if (visibleManagerIds == null) {
            return;
        }
        if (visibleManagerIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Общий плательщик недоступен текущему пользователю");
        }
        boolean visibleAnchor = false;
        Manager accountManager = account == null ? null : account.getManager();
        if (accountManager != null) {
            ensureManagerVisible(accountManager, visibleManagerIds);
            visibleAnchor = true;
        }
        Company invoiceCompany = account == null ? null : account.getInvoiceCompany();
        if (invoiceCompany != null) {
            ensureCompanyVisible(invoiceCompany, visibleManagerIds);
        }
        for (Long companyId : safeCompanyIds(companyIds)) {
            Company company = companyRepository.findById(companyId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Компания не найдена"));
            ensureCompanyVisible(company, visibleManagerIds);
            visibleAnchor = true;
        }
        if (requireVisibleAnchor && !visibleAnchor) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Укажите доступного менеджера или доступную компанию для общего плательщика");
        }
    }

    Set<Long> safeCompanyIds(List<Long> companyIds) {
        if (companyIds == null) {
            return Set.of();
        }
        return companyIds.stream().filter(id -> id != null && id > 0).collect(Collectors.toSet());
    }

    void ensureCompanyVisibleForCurrentUser(Company company) {
        Set<Long> visibleManagerIds = visibleManagerIdsForCurrentUser();
        if (visibleManagerIds == null) {
            return;
        }
        ensureCompanyVisible(company, visibleManagerIds);
    }

    void ensureManagerVisible(Manager manager, Set<Long> visibleManagerIds) {
        if (manager == null || manager.getId() == null || !visibleManagerIds.contains(manager.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Менеджер недоступен текущему пользователю");
        }
    }

    void ensureCompanyVisible(Company company, Set<Long> visibleManagerIds) {
        Manager manager = company == null ? null : company.getManager();
        if (manager == null || manager.getId() == null || !visibleManagerIds.contains(manager.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Компания недоступна текущему пользователю");
        }
    }

    Set<Long> visibleManagerIdsForCurrentUser() {
        return invoiceDelivery.visibleManagerIdsForCurrentUser();
    }

    CommonBillingCompanyResponse toCompanyResponse(CommonBillingAccountCompany link) {
        return invoicePresenter.toCompanyResponse(link);
    }

    void ensureCommonPaymentRouteAllowsCompositionChange(CommonInvoice invoice) {
        invoiceMembershipWorkflow.ensureCommonPaymentRouteAllowsCompositionChange(invoice);
    }

    void restoreDetachedOrderStatus(Order order, String originalStatus) {
        invoiceMembershipWorkflow.restoreDetachedOrderStatus(order, originalStatus);
    }

    void markInvoiceOrdersPublished(Long invoiceId) {
        settlementService.markInvoiceOrdersPublished(invoiceId);
    }

    void markInvoiceOrdersPublished(List<CommonInvoiceOrder> items) {
        settlementService.markInvoiceOrdersPublished(items);
    }

    String managerName(Manager manager) {
        if (manager == null || manager.getUser() == null) {
            return "";
        }
        return normalize(manager.getUser().getFio());
    }

    String normalize(String value) {
        return settlementService.normalize(value);
    }

    String limit(String value, int max) {
        return settlementService.limit(value, max);
    }
}
