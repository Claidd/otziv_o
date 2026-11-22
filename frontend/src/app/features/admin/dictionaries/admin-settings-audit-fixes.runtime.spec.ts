import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Subject, of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AdminWorkSettingsApi } from '../../../core/admin-work-settings.api';
import {
  ContractorLegacyRewardReconciliation,
  ContractorPaymentSystemStatus
} from '../../../core/contractor-payments.api';
import {
  ManagerReportReviewSettings,
  ManagerReportReviewSettingsApi
} from '../../../core/manager-report-review-settings.api';
import { businessDateIso } from '../../../shared/business-date';
import { ToastService } from '../../../shared/toast.service';
import { AdminContractorSystemFacade } from './admin-contractor-system.facade';
import { AdminWorkSettingsFacade } from './admin-work-settings.facade';
import { DEFAULT_MANAGER_EVIDENCE_SETTINGS, ManagerAuditSettingsComponent } from './manager-audit-settings.component';

const nagul = { cooldownMinutes: 15, lookaheadDays: 10, accountWalkedCounterThreshold: 4, accountWalkDelayDays: 1 };
const cooldown = { enabled: false, cooldownSeconds: 30 };
const telegram = {
  morningEnabled: false, morningTime: '09:15', eveningEnabled: true, eveningTime: '21:00', zone: 'Asia/Irkutsk',
  morningLastRunKey: '2026-11-21 morning 09:15 Asia/Irkutsk', eveningLastRunKey: ''
};
const whatsApp = { enabled: false, intervalMinutes: 120, lastRunAt: '2026-11-21T10:00:00', lastLinkedCount: 2 };
const reports = { enabled: false };
const cellular = {
  mode: 'AUDIT', enforcedReasons: ['NON_CELLULAR_NETWORK'], enforceNativeVirtualDevice: false,
  requireMobileDevice: true, ipIntelligenceEnabled: true, allowedCidrs: [], protectedSections: ['nagul']
};

describe('work settings: no placeholder overwrite, kept edits, honest syncs', () => {
  let http: HttpTestingController;
  const toast = { success: vi.fn(), error: vi.fn() };

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    vi.clearAllMocks();
  });
  afterEach(() => {
    vi.restoreAllMocks();
    http.verify({ ignoreCancelled: true });
  });

  function create(options: { maintenance?: boolean; active?: () => boolean; onLateResult?: () => void } = {}) {
    return new AdminWorkSettingsFacade({
      api: TestBed.inject(AdminWorkSettingsApi), toast, isActive: options.active ?? (() => true),
      selectedId: signal(null), search: () => '', canApplyMaintenance: () => options.maintenance ?? true,
      onLateResult: options.onLateResult
    });
  }

  function flushLoad(maintenance = true, overrides: { nagul?: object; telegram?: object } = {}) {
    http.expectOne((r) => r.method === 'GET' && r.url.endsWith('/settings/nagul')).flush(overrides.nagul ?? nagul);
    http.expectOne((r) => r.method === 'GET' && r.url.endsWith('/worker-account-action-settings')).flush(cooldown);
    http.expectOne((r) => r.method === 'GET' && r.url.endsWith('/settings/telegram-reports'))
      .flush(overrides.telegram ?? telegram);
    http.expectOne((r) => r.method === 'GET' && r.url.endsWith('/settings/whatsapp-group-sync')).flush(whatsApp);
    http.expectOne((r) => r.method === 'GET' && r.url.endsWith('/settings/client-publication-progress-reports'))
      .flush(reports);
    if (maintenance) {
      http.expectOne((r) => r.method === 'GET' && r.url.endsWith('/settings/worker-cellular-access')).flush(cellular);
    }
  }

  function loaded(options: Parameters<typeof create>[0] = {}) {
    const facade = create(options);
    facade.load();
    flushLoad(options.maintenance ?? true);
    return facade;
  }

  it('a full read leaves the form clean, so leaving the tab asks no false question', () => {
    const facade = loaded();
    facade.settingsForm.controls.nagulCooldownMinutes.setValue(7);
    facade.settingsForm.markAsDirty();
    facade.load();
    flushLoad();
    expect(facade.settingsForm.dirty).toBe(false);
    facade.destroy();
  });

  it('refuses to save placeholders before a read succeeded, and again after a failed reload', () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
    const facade = create();
    expect(facade.canSaveSettings()).toBe(false);
    facade.saveSettings();
    http.expectNone((r) => r.method === 'PUT');
    expect(toast.error).toHaveBeenCalledWith('Настройки не сохранены', expect.stringContaining('ещё не загрузились'));

    facade.load();
    http.expectOne((r) => r.url.endsWith('/settings/nagul')).flush({ message: 'down' }, { status: 503, statusText: 'Unavailable' });
    // The other reads of the failed load were dropped; they stay listed by the testing backend until matched.
    expect(http.match(() => true).every((request) => request.cancelled)).toBe(true);
    expect(facade.settingsLoaded()).toBe(false);
    facade.saveSettings();
    http.expectNone((r) => r.method === 'PUT');

    facade.load();
    flushLoad();
    expect(facade.settingsLoaded()).toBe(true);
    expect(facade.canSaveSettings()).toBe(true);
    expect(facade.settingsForm.controls.workerCellularAccessMode.value).toBe('AUDIT');

    // A reload clears the flag at once: an interrupted or failed one cannot pass on the old one.
    facade.load();
    expect(facade.settingsLoaded()).toBe(false);
    http.expectOne((r) => r.url.endsWith('/settings/telegram-reports')).flush({ message: 'down' }, { status: 500, statusText: 'Error' });
    facade.saveSettings();
    http.expectNone((r) => r.method === 'PUT');
    expect(confirm).not.toHaveBeenCalled();
  });

  it('a sync waits for a running read instead of cancelling it, and the other actions say they wait', () => {
    const facade = create();
    facade.load();
    const pending = http.match((r) => r.method === 'GET');
    facade.runWhatsAppGroupSync();
    http.expectNone((r) => r.method === 'POST');
    expect(pending.some((request) => request.cancelled)).toBe(false);
    expect(toast.error).toHaveBeenCalledWith('Синхронизация не запущена', expect.stringContaining('Дождитесь'));
    pending.forEach((request) => {
      const url = request.request.url;
      request.flush(url.endsWith('/nagul') ? nagul : url.endsWith('/worker-account-action-settings') ? cooldown
        : url.endsWith('/telegram-reports') ? telegram : url.endsWith('/whatsapp-group-sync') ? whatsApp
          : url.endsWith('/client-publication-progress-reports') ? reports : cellular);
    });
    expect(facade.settingsLoaded()).toBe(true);

    facade.runWhatsAppGroupSync();
    const sync = http.expectOne((r) => r.method === 'POST' && r.url.endsWith('/whatsapp-group-sync/run'));
    expect(facade.canSaveSettings()).toBe(false);
    expect(facade.canRunSync()).toBe(false);
    vi.clearAllMocks();
    facade.saveSettings();
    facade.runSharedChatLinkSync();
    http.expectNone((r) => r.method === 'PUT');
    http.expectNone((r) => r.url.endsWith('/shared-chat-links/sync'));
    expect(toast.error).toHaveBeenCalledWith('Настройки не сохранены', expect.stringContaining('Дождитесь'));
    expect(toast.error).toHaveBeenCalledWith('Синхронизация не запущена', expect.stringContaining('Дождитесь'));
    sync.flush({ ...whatsApp, lastLinkedCount: 3 });
    expect(facade.whatsAppGroupSyncSettings()?.lastLinkedCount).toBe(3);
    expect(facade.canSaveSettings()).toBe(true);
  });

  it('a read cancelled by a running sync («Обновить» pressed meanwhile) is started again when the sync answers', () => {
    const facade = loaded();
    facade.runWhatsAppGroupSync();
    const sync = http.expectOne((r) => r.url.endsWith('/whatsapp-group-sync/run'));
    facade.load();
    expect(facade.settingsLoaded()).toBe(false);
    sync.flush(whatsApp);
    const reads = http.match((r) => r.method === 'GET');
    expect(reads.filter((request) => request.cancelled)).toHaveLength(6);
    const again = reads.filter((request) => !request.cancelled);
    expect(again).toHaveLength(6);
    again.forEach((request) => {
      const url = request.request.url;
      request.flush(url.endsWith('/nagul') ? nagul : url.endsWith('/worker-account-action-settings') ? cooldown
        : url.endsWith('/telegram-reports') ? telegram : url.endsWith('/whatsapp-group-sync') ? whatsApp
          : url.endsWith('/client-publication-progress-reports') ? reports : cellular);
    });
    expect(facade.settingsLoaded()).toBe(true);
    expect(facade.canSaveSettings()).toBe(true);
  });

  it('a sync keeps typed WhatsApp edits and reports its failure at its button, not as a failed tab', () => {
    const facade = loaded();
    facade.settingsForm.controls.whatsAppGroupSyncIntervalMinutes.setValue(45);
    facade.runWhatsAppGroupSync();
    http.expectOne((r) => r.url.endsWith('/whatsapp-group-sync/run')).flush({ ...whatsApp, intervalMinutes: 120 });
    expect(facade.settingsForm.controls.whatsAppGroupSyncIntervalMinutes.value).toBe(45);

    facade.runSharedChatLinkSync();
    http.expectOne((r) => r.url.endsWith('/shared-chat-links/sync'))
      .flush({ message: 'Шлюз недоступен' }, { status: 409, statusText: 'Conflict' });
    expect(facade.sharedChatSyncError()).toContain('Шлюз недоступен');
    expect(facade.error()).toBeNull();
    expect(toast.error).toHaveBeenCalledWith('Общие чаты не синхронизированы', expect.stringContaining('Шлюз недоступен'));
  });

  it('a section refused by the server keeps the edits of every section that was not sent', () => {
    const facade = loaded();
    const form = facade.settingsForm.controls;
    form.workerAccountActionCooldownSeconds.setValue(75);
    form.nagulCooldownMinutes.setValue(20);
    form.telegramReportZone.setValue('Иркутск');
    form.whatsAppGroupSyncIntervalMinutes.setValue(60);
    form.workerCellularAccessMode.setValue('OFF');

    facade.saveSettings();
    http.expectOne((r) => r.method === 'PUT' && r.url.endsWith('/worker-account-action-settings'))
      .flush({ enabled: false, cooldownSeconds: 75 });
    http.expectOne((r) => r.method === 'PUT' && r.url.endsWith('/settings/nagul')).flush({ ...nagul, cooldownMinutes: 20 });
    http.expectOne((r) => r.method === 'PUT' && r.url.endsWith('/settings/telegram-reports'))
      .flush({ message: 'Укажите корректный часовой пояс' }, { status: 400, statusText: 'Bad Request' });
    http.expectNone((r) => r.method === 'PUT');
    expect(toast.error).toHaveBeenCalledWith('Настройки сохранены частично',
      expect.stringMatching(/2 из 6\. Остальные изменения остались в форме.*часовой пояс/));

    flushLoad(true, { nagul: { ...nagul, cooldownMinutes: 20 } });
    expect(form.telegramReportZone.value).toBe('Иркутск');
    expect(form.whatsAppGroupSyncIntervalMinutes.value).toBe(60);
    expect(form.workerCellularAccessMode.value).toBe('OFF');
    expect(form.nagulCooldownMinutes.value).toBe(20);
    expect(facade.telegramReportSettings()?.zone).toBe('Asia/Irkutsk');
    expect(facade.error()).toContain('часовой пояс');
    expect(facade.settingsLoaded()).toBe(true);
  });

  it('a tab switch restores mobile access from the loaded settings, never the «Боевой режим» placeholders', () => {
    const facade = loaded();
    const form = facade.settingsForm.controls;
    form.workerCellularAccessMode.setValue('OFF');
    form.blockVpnProxyOrDatacenter.setValue(true);
    facade.settingsForm.markAsDirty();
    facade.resetSettingsForm();
    expect(form.workerCellularAccessMode.value).toBe('AUDIT');
    expect(form.blockNonCellularNetwork.value).toBe(true);
    expect(form.blockVpnProxyOrDatacenter.value).toBe(false);
    expect(form.enforceNativeVirtualDevice.value).toBe(false);
    expect(facade.settingsForm.dirty).toBe(false);

    const unloaded = create();
    unloaded.settingsForm.controls.workerCellularAccessMode.setValue('OFF');
    unloaded.resetSettingsForm();
    expect(unloaded.settingsForm.controls.workerCellularAccessMode.value).toBe('OFF');
  });

  it('walk settings and the WhatsApp interval take whole numbers; the message names the field', () => {
    const facade = create();
    const form = facade.settingsForm.controls;
    form.nagulCooldownMinutes.setValue(1.5);
    form.whatsAppGroupSyncIntervalMinutes.setValue(7.5);
    expect(form.nagulCooldownMinutes.invalid).toBe(true);
    expect(form.whatsAppGroupSyncIntervalMinutes.invalid).toBe(true);
    expect(facade.settingsFieldError('nagulCooldownMinutes')).toBeNull();
    form.nagulCooldownMinutes.markAsTouched();
    expect(facade.settingsFieldError('nagulCooldownMinutes')).toBe('Введите целое число от 0 до 1440 минут.');
    expect(facade.settingsInvalidSummary()).toBe('Исправьте: время между выгулами, интервал проверки WhatsApp.');
    form.nagulCooldownMinutes.setValue(2);
    form.whatsAppGroupSyncIntervalMinutes.setValue(10);
    expect(facade.settingsInvalidSummary()).toBe('');
  });

  it('the confirmation of «Боевой режим» names only the chosen reasons', () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    const facade = loaded();
    facade.settingsForm.patchValue({
      workerCellularAccessMode: 'ENFORCE', blockNonCellularNetwork: false, blockVpnProxyOrDatacenter: true,
      blockDesktopOrUnknownDevice: false, blockUnknownNetwork: false, enforceNativeVirtualDevice: false
    });
    facade.saveSettings();
    expect(confirm).toHaveBeenCalledOnce();
    expect(confirm.mock.calls[0][0]).toContain('через VPN, прокси, Tor или дата-центр');
    expect(confirm.mock.calls[0][0]).not.toContain('Wi-Fi');
    expect(facade.saving()).toBe(false);
    http.expectNone((r) => r.method === 'PUT');
  });

  it('a save that ends after the view was left still reports its result and asks for a reload', () => {
    let active = true;
    const onLateResult = vi.fn();
    const facade = loaded({ maintenance: false, active: () => active, onLateResult });
    facade.saveSettings();
    active = false;
    facade.deactivate();
    for (const path of ['/worker-account-action-settings', '/settings/nagul', '/settings/telegram-reports',
      '/settings/whatsapp-group-sync', '/settings/client-publication-progress-reports']) {
      http.expectOne((r) => r.method === 'PUT' && r.url.endsWith(path)).flush({});
    }
    expect(toast.success).toHaveBeenCalledWith('Настройки сохранены', 'Все разделы применены.');
    expect(onLateResult).toHaveBeenCalledOnce();
    expect(facade.saving()).toBe(false);
    http.expectNone((r) => r.method === 'GET');

    active = true;
    const second = loaded({ maintenance: false, active: () => active, onLateResult });
    second.saveSettings();
    active = false;
    second.deactivate();
    http.expectOne((r) => r.method === 'PUT' && r.url.endsWith('/worker-account-action-settings')).flush({});
    http.expectOne((r) => r.method === 'PUT' && r.url.endsWith('/settings/nagul'))
      .flush({ message: 'Отклонено' }, { status: 409, statusText: 'Conflict' });
    expect(toast.error).toHaveBeenCalledWith('Настройки сохранены частично', expect.stringContaining('Применено разделов: 1 из 5'));
  });

  it('names schedule runs as dates, flags an unknown zone and calls the watch mode by its meaning', () => {
    const facade = create();
    expect(facade.telegramRunLabel('2026-11-21 morning 11:30 Asia/Irkutsk', 'утро')).toBe('утро: 21.11.2026 в 11:30 (Asia/Irkutsk)');
    expect(facade.telegramRunLabel('', 'вечер')).toBe('вечер: ещё не было');
    expect(facade.telegramRunLabel('что-то другое', 'утро')).toBe('утро: что-то другое');
    facade.settingsForm.controls.telegramReportZone.setValue('Иркутск');
    expect(facade.telegramZoneWarning()).toContain('не распознан');
    facade.settingsForm.controls.telegramReportZone.setValue('Asia/Irkutsk');
    expect(facade.telegramZoneWarning()).toBeNull();
    expect(facade.workerCellularAccessModeLabel('AUDIT')).toBe('наблюдение без блокировки');
  });
});

describe('contractor system: reconciliation forms that explain themselves', () => {
  const toast = { success: vi.fn(), error: vi.fn() };
  const legacyStatus: ContractorPaymentSystemStatus = {
    mode: 'LEGACY', systemEnabled: false, legacyBehavior: true, irreversible: false, routingRequested: false,
    completionAccountingEffective: false, liveRoutingEffective: false, completionBacklogReady: true,
    activationAvailable: false, activationBlockedReasons: ['Финансовая подготовка за текущую дату не завершена'],
    runtimeWarnings: [], attributionStartDate: null, revision: 7, liveRoutingMasterEnabled: true,
    rewardAttributionMasterEnabled: true
  };
  const run: ContractorLegacyRewardReconciliation = {
    runId: 5, startDate: '2026-11-21', status: 'PREPARED', snapshotHash: 'a'.repeat(64),
    autoOrderCount: 1, autoRowCount: 3, autoRemainingRows: 3, manualOrderCount: 1, manualRowCount: 2,
    manualRemainingOrders: 1, createdAt: '2026-11-21T10:00:00', expiresAt: '2026-11-21T10:30:00',
    manualGroups: [{ orderId: 42, groupHash: 'g'.repeat(64), evidenceCategory: 'COMPLETION_DATE_REQUIRES_EVIDENCE',
      status: 'PENDING', rowCount: 2 }]
  };

  beforeEach(() => vi.clearAllMocks());

  function create(api: Record<string, unknown>, options: { active?: () => boolean } = {}) {
    const full = {
      getSystemStatus: vi.fn(() => of(legacyStatus)),
      getLegacyRewardReconciliation: vi.fn(() => of(run)),
      activateSystem: vi.fn(), updateSystemRouting: vi.fn(), prepareLegacyRewardReconciliation: vi.fn(),
      applyLegacyRewardReconciliation: vi.fn(), resolveLegacyRewardManualGroup: vi.fn(),
      ...api
    };
    const facade = new AdminContractorSystemFacade({
      api: full as never, toast, isActive: options.active ?? (() => true), ownerAllowed: () => true
    });
    return { facade, api: full };
  }

  it('after AUTO and manual reconciliation the activation status and its blockers are read again', () => {
    const applied = { ...run, status: 'AUTO_APPLIED', autoRemainingRows: 0 };
    const { facade, api } = create({
      applyLegacyRewardReconciliation: vi.fn(() => of(applied)),
      resolveLegacyRewardManualGroup: vi.fn(() => of({ ...applied, manualRemainingOrders: 0 }))
    });
    facade.loadContractorPaymentSystemStatus();
    expect(api.getSystemStatus).toHaveBeenCalledTimes(1);

    facade.contractorLegacyAutoForm.setValue({ reason: 'Сверено', confirmation: 'ПРИМЕНИТЬ АВТОСВЕРКУ' });
    facade.applyContractorLegacyAutomatic();
    expect(api.applyLegacyRewardReconciliation).toHaveBeenCalledOnce();
    expect(api.getSystemStatus).toHaveBeenCalledTimes(2);
    expect(toast.success).toHaveBeenCalledWith('Автоматическая сверка применена', expect.any(String));

    facade.openContractorLegacyManual(run.manualGroups[0]);
    facade.contractorLegacyManualForm.setValue({
      completedOn: '2026-11-20', evidenceReference: 'Акт 12', reason: 'Подтверждено', confirmation: 'ПОДТВЕРДИТЬ РУЧНУЮ СВЕРКУ'
    });
    facade.resolveContractorLegacyManual();
    expect(api.resolveLegacyRewardManualGroup).toHaveBeenCalledOnce();
    expect(api.getSystemStatus).toHaveBeenCalledTimes(3);
    expect(facade.contractorLegacyManualGroup()).toBeNull();
    expect(toast.success).toHaveBeenCalledWith('Ручная сверка подтверждена', 'Доказательство и снимок записаны в журнал действий.');
  });

  it('refuses blank texts, an inexact phrase and a date that is not before the snapshot day', () => {
    const { facade, api } = create({ applyLegacyRewardReconciliation: vi.fn(() => of(run)) });
    facade.loadContractorPaymentSystemStatus();
    facade.contractorLegacyAutoForm.setValue({ reason: '   ', confirmation: 'ПРИМЕНИТЬ АВТОСВЕРКУ' });
    expect(facade.contractorLegacyAutoReady()).toBe(false);
    facade.applyContractorLegacyAutomatic();
    expect(api.applyLegacyRewardReconciliation).not.toHaveBeenCalled();
    expect(facade.contractorLegacyAutoForm.touched).toBe(true);
    facade.contractorLegacyAutoForm.setValue({ reason: 'Сверено', confirmation: 'применить автосверку' });
    expect(facade.contractorLegacyAutoReady()).toBe(false);
    facade.contractorLegacyAutoForm.setValue({ reason: 'Сверено', confirmation: ' ПРИМЕНИТЬ АВТОСВЕРКУ ' });
    expect(facade.contractorLegacyAutoReady()).toBe(true);

    expect(facade.contractorLegacyManualMaxDate()).toBe('2026-11-20');
    facade.contractorLegacyManualForm.setValue({
      completedOn: '2026-11-21', evidenceReference: 'Акт 12', reason: 'Подтверждено', confirmation: 'ПОДТВЕРДИТЬ РУЧНУЮ СВЕРКУ'
    });
    expect(facade.contractorLegacyManualReady()).toBe(false);
    facade.contractorLegacyManualForm.controls.completedOn.setValue('2026-11-20');
    expect(facade.contractorLegacyManualReady()).toBe(true);
    facade.contractorLegacyManualForm.controls.evidenceReference.setValue('  ');
    expect(facade.contractorLegacyManualReady()).toBe(false);

    facade.contractorLegacyReconciliation.set({ ...run, startDate: '2026-03-01' });
    expect(facade.contractorLegacyManualMaxDate()).toBe('2026-02-28');

    facade.contractorSystemActivationForm.patchValue({ reason: 'x'.repeat(501), confirmation: 'ВКЛЮЧИТЬ НОВУЮ СИСТЕМУ' });
    expect(facade.contractorSystemActivationReady()).toBe(false);
    facade.contractorSystemActivationForm.patchValue({ reason: '   ' });
    expect(facade.contractorSystemActivationReady()).toBe(false);
    facade.contractorSystemRoutingForm.setValue({ reason: '  ', confirmation: 'ВКЛЮЧИТЬ РЕКВИЗИТЫ' });
    expect(facade.contractorSystemRoutingForm.valid).toBe(false);
  });

  it('a refused manual confirmation is shown inside its window, not behind it', () => {
    const { facade } = create({
      resolveLegacyRewardManualGroup: vi.fn(() => throwError(() => ({
        status: 409, error: { message: 'У заказа остаются активные задачи восстановления' }
      })))
    });
    facade.loadContractorPaymentSystemStatus();
    facade.openContractorLegacyManual(run.manualGroups[0]);
    facade.contractorLegacyManualForm.setValue({
      completedOn: '2026-11-20', evidenceReference: 'Акт 12', reason: 'Подтверждено', confirmation: 'ПОДТВЕРДИТЬ РУЧНУЮ СВЕРКУ'
    });
    facade.resolveContractorLegacyManual();
    expect(facade.contractorLegacyManualError()).toBe('У заказа остаются активные задачи восстановления');
    expect(facade.contractorSystemError()).toBeNull();
    expect(facade.contractorLegacyManualGroup()?.orderId).toBe(42);
    expect(facade.contractorLegacyReconciliationSaving()).toBe(false);
    facade.closeContractorLegacyManual();
    expect(facade.contractorLegacyManualError()).toBeNull();
  });

  it('hides reconciliation actions once the system is on, names codes in words and hints a stale snapshot', () => {
    const { facade } = create({ getSystemStatus: vi.fn(() => of({ ...legacyStatus, systemEnabled: true, mode: 'COMPLETION_ACTIVE' })) });
    facade.loadContractorPaymentSystemStatus();
    expect(facade.canApplyContractorLegacy()).toBe(false);
    facade.openContractorLegacyManual(run.manualGroups[0]);
    expect(facade.contractorLegacyManualGroup()).toBeNull();

    expect(facade.contractorLegacyCategoryLabel('ACTIVE_REVIEW_RECOVERY')).toBe('Идёт восстановление отзывов');
    expect(facade.contractorLegacyCategoryLabel('IDENTITY_OR_SOURCE_AMBIGUOUS')).toBe('Неясен получатель');
    expect(facade.contractorLegacyCategoryLabel('NEW_CODE')).toBe('NEW_CODE');
    expect(facade.contractorLegacyGroupStatusLabel('PENDING')).toBe('Ожидает');
    expect(facade.contractorLegacyRunStatusLabel('AUTO_APPLIED')).toBe('автоматическая часть применена');

    facade.contractorLegacyReconciliation.set({ ...run, startDate: '2000-01-01', expiresAt: '2000-01-01T10:30:00' });
    expect(facade.contractorLegacySnapshotStale()).toBe(true);
    facade.contractorLegacyReconciliation.set({ ...run, startDate: businessDateIso(), expiresAt: '2999-01-01T00:00:00' });
    expect(facade.contractorLegacySnapshotStale()).toBe(false);
    // A finished reconciliation does not call for a new snapshot, however old it is.
    facade.contractorLegacyReconciliation.set({ ...run, startDate: '2000-01-01', expiresAt: '2000-01-01T10:30:00',
      autoRemainingRows: 0, manualRemainingOrders: 0 });
    expect(facade.contractorLegacySnapshotStale()).toBe(false);
  });

  it('an activation that ends after the page was left closes its window and reports the result', () => {
    let active = true;
    const answer = new Subject<ContractorPaymentSystemStatus>();
    const { facade } = create({
      getSystemStatus: vi.fn(() => of({ ...legacyStatus, activationAvailable: true, activationBlockedReasons: [] })),
      activateSystem: vi.fn(() => answer)
    }, { active: () => active });
    facade.loadContractorPaymentSystemStatus();
    facade.requestContractorSystemActivation(new Event('click'));
    facade.contractorSystemActivationForm.patchValue({ reason: 'Сверено', confirmation: 'ВКЛЮЧИТЬ НОВУЮ СИСТЕМУ' });
    facade.activateContractorSystem();
    active = false;
    facade.deactivate();
    answer.next({ ...legacyStatus, mode: 'COMPLETION_ACTIVE', systemEnabled: true, legacyBehavior: false, irreversible: true });
    answer.complete();
    expect(facade.contractorSystemActivationOpen()).toBe(false);
    expect(facade.contractorSystemStatus()?.systemEnabled).toBe(true);
    expect(facade.contractorSystemSaving()).toBe(false);
    expect(toast.success).toHaveBeenCalledWith('Новая система расчётов активирована', 'Возврат к старому учёту невозможен.');
  });
});

describe('manager audit settings: explained saving', () => {
  const settings: ManagerReportReviewSettings = {
    enabled: true, managerGroupsEnabled: true, restrictionEnabled: true,
    maxQuestionCount: 8, minimumReadSeconds: 60, testMinimumReadSeconds: 10,
    reminderOneMinutes: 60, reminderThreeMinutes: 180, minimumAnswerScore: 75,
    maxAnswerCharacters: 420, maxPlanCharacters: 600, fastPasteSeconds: 12,
    fastPasteMinCharacters: 140, copyGramSize: 4, copySimilarityPercent: 65, aiTimeoutSeconds: 25,
    questionGenerationMaxTokens: 8000, questionGenerationRetryMaxTokens: 12000,
    evidence: { ...DEFAULT_MANAGER_EVIDENCE_SETTINGS },
    managers: [{ managerId: 2, managerName: 'Менеджер пилота', auditEnabled: true, userActive: true, auditGroupConnected: true }]
  };
  let api: { settings: ReturnType<typeof vi.fn>; update: ReturnType<typeof vi.fn>; updateManager: ReturnType<typeof vi.fn> };
  let toast: { success: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> };

  function render(value: ManagerReportReviewSettings = settings) {
    api = {
      settings: vi.fn(() => of(structuredClone(value))),
      update: vi.fn((request) => of({ ...request, managers: value.managers })),
      updateManager: vi.fn()
    };
    toast = { success: vi.fn(), error: vi.fn() };
    TestBed.configureTestingModule({
      imports: [ManagerAuditSettingsComponent],
      providers: [
        { provide: ManagerReportReviewSettingsApi, useValue: api },
        { provide: ToastService, useValue: toast }
      ]
    });
    const fixture = TestBed.createComponent(ManagerAuditSettingsComponent);
    fixture.detectChanges();
    return fixture;
  }

  afterEach(() => vi.restoreAllMocks());

  it('keeps «Сохранить настройки» available, names a wrong field, opens its folded block and focuses it', () => {
    const fixture = render();
    const page = fixture.componentInstance;
    const element: HTMLElement = fixture.nativeElement;
    page.form.controls.maxQuestionCount.setValue(99);
    fixture.detectChanges();
    const submit = element.querySelector<HTMLButtonElement>('.save-bar button[type="submit"]')!;
    expect(submit.disabled).toBe(false);
    submit.click();
    fixture.detectChanges();
    expect(api.update).not.toHaveBeenCalled();
    expect(page.error()).toContain('«Максимум вопросов»');
    expect(toast.error).toHaveBeenCalledWith('Настройки не сохранены', expect.stringContaining('«Максимум вопросов»'));
    expect(element.querySelector('.save-bar .save-error')?.textContent).toContain('«Максимум вопросов»');
    expect(element.querySelector('.audit-error')).toBeNull();
    expect(element.querySelector<HTMLDetailsElement>('details.advanced-card')!.open).toBe(true);
    expect(document.activeElement).toBe(element.querySelector('[formcontrolname="maxQuestionCount"]'));
    expect(element.querySelector('[formcontrolname="maxQuestionCount"] + small')?.classList).toContain('field-error');
    fixture.destroy();
  });

  it('opens «Генерация вопросов» when a wrong field hides there, even if another wrong field comes first', () => {
    const fixture = render();
    const page = fixture.componentInstance;
    const element: HTMLElement = fixture.nativeElement;
    page.form.patchValue({ reminderOneMinutes: 1, questionGenerationMaxTokens: 1 });
    page.save();
    expect(element.querySelector<HTMLDetailsElement>('details.advanced-card')!.open).toBe(true);
    expect(document.activeElement).toBe(element.querySelector('[formcontrolname="reminderOneMinutes"]'));
    expect(page.error()).toBe(
      'Проверьте поля: «Первое напоминание», «Лимит генерации». Допустимые значения написаны под полями.'
    );
    expect(api.update).not.toHaveBeenCalled();
    fixture.destroy();
  });

  it('shows cross-field refusals next to the button and as a notice', () => {
    const fixture = render();
    const page = fixture.componentInstance;
    page.form.patchValue({ reminderOneMinutes: 60, reminderThreeMinutes: 30 });
    page.save();
    fixture.detectChanges();
    expect(api.update).not.toHaveBeenCalled();
    expect(toast.error).toHaveBeenCalledWith('Настройки не сохранены', 'Срок прохождения должен быть больше времени первого напоминания.');
    expect(fixture.nativeElement.querySelector('.save-bar .save-error')?.textContent).toContain('Срок прохождения');
    fixture.destroy();
  });

  it('asks before switching the audit off only when the saved audit was on', () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    const fixture = render();
    fixture.componentInstance.form.controls.enabled.setValue(false);
    fixture.componentInstance.save();
    expect(confirm).toHaveBeenCalledOnce();
    expect(api.update).not.toHaveBeenCalled();
    fixture.destroy();
    TestBed.resetTestingModule();

    confirm.mockClear();
    const off = render({ ...settings, enabled: false });
    off.componentInstance.form.controls.minimumReadSeconds.setValue(90);
    off.componentInstance.save();
    expect(confirm).not.toHaveBeenCalled();
    expect(api.update).toHaveBeenCalledOnce();
    off.destroy();
  });

  it('names a pilot manager the list cannot show, lets it be removed and does not count it as chosen', () => {
    const fixture = render({
      ...settings,
      evidence: { ...DEFAULT_MANAGER_EVIDENCE_SETTINGS, answerMode: 'PILOT', answerPilotManagerIds: [2, 99] }
    });
    const page = fixture.componentInstance;
    expect(page.hiddenPilotIds('answerPilotManagerIds')).toEqual([99]);
    expect(fixture.nativeElement.textContent).toContain('Менеджер №99 неактивен');

    page.form.controls.evidence.controls.answerPilotManagerIds.setValue([99]);
    page.save();
    expect(api.update).not.toHaveBeenCalled();
    expect(page.error()).toContain('Выберите хотя бы одного менеджера');

    page.removePilotId('answerPilotManagerIds', 99);
    expect(page.form.controls.evidence.controls.answerPilotManagerIds.value).toEqual([]);
    expect(page.form.dirty).toBe(true);
    fixture.destroy();
  });

  it('the summary shows the saved state, and «Обновить» asks before dropping edits', () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    const fixture = render();
    const page = fixture.componentInstance;
    page.form.controls.enabled.setValue(false);
    page.form.markAsDirty();
    fixture.detectChanges();
    const summary: HTMLElement = fixture.nativeElement.querySelector('.audit-summary article');
    expect(summary.textContent).toContain('Аудит работает');
    expect(summary.textContent).toContain('ещё не сохранён');

    page.refresh();
    expect(api.settings).toHaveBeenCalledTimes(1);
    confirm.mockReturnValue(true);
    page.refresh();
    expect(api.settings).toHaveBeenCalledTimes(2);
    expect(page.form.dirty).toBe(false);
    expect(page.form.controls.enabled.value).toBe(true);
    fixture.destroy();
  });

  it('says what the answer check mode does to reminders and the restriction; the search has a name', () => {
    const fixture = render();
    const element: HTMLElement = fixture.nativeElement;
    expect(element.textContent).toContain('В режиме «Сверка качества» ограничение доступа не действует.');
    expect(element.textContent).toContain('напоминания не отправляются и срок не отслеживается');
    expect(element.textContent).toContain('Пока ИИ недоступен, доступ не ограничивается.');
    fixture.componentInstance.form.controls.evidence.controls.answerMode.setValue('PILOT');
    fixture.detectChanges();
    expect(element.textContent).toContain('Ограничение действует только для менеджеров пилота проверки ответов.');
    // The owner switches the restriction off: the note under it says so instead of describing its scope.
    element.querySelector<HTMLInputElement>('input[formcontrolname="restrictionEnabled"]')!.click();
    fixture.detectChanges();
    expect(fixture.componentInstance.form.controls.restrictionEnabled.value).toBe(false);
    expect(element.textContent).toContain('Ограничение доступа выключено.');
    expect(element.textContent).not.toContain('Ограничение действует только для менеджеров пилота');
    expect(element.querySelector('.manager-search input')?.getAttribute('aria-label')).toBe('Найти менеджера');
    fixture.destroy();
  });
});
