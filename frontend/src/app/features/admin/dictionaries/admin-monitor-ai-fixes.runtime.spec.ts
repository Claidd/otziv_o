import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { of, Subject, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type {
  AdminClientMessageMaintenancePreview,
  AdminClientMessageMonitor,
  AdminClientMessageMonitorQueueItem,
  AdminClientMessageMonitorScenario,
  AdminClientMessageMonitorSettings,
  AdminMaintenanceApplyResponse
} from '../../../core/admin-dictionaries.api';
import type { ReputationAiProvider, ReputationAiStatus } from '../../../core/reputation-ai.api';
import { AdminAiProviderFacade } from './admin-ai-provider.facade';
import { AdminMessageMonitorFacade } from './admin-message-monitor.facade';
import { ReviewArchiveChecksComponent } from './review-archive-checks.component';

const monitor = (patch: Partial<AdminClientMessageMonitor> = {}) => ({
  enabled: true, activeCandidates: 3, updatedAt: '2026-11-21T10:00:00', nowIrkutsk: '2026-11-21T10:00:00',
  queue: [], attempts: [], scenarios: [], ...patch
}) as unknown as AdminClientMessageMonitor;

const task = (patch: Partial<AdminClientMessageMonitorQueueItem> = {}) => ({
  id: 17, scenario: 'PAYMENT_REMINDER', scenarioLabel: 'Оплата', targetType: 'ORDER', targetKey: 'order:5:1',
  companyId: 9, companyTitle: 'Ромашка', orderId: 5, orderTitle: 'Ромашка - Центр', sentCount: 0, consecutiveFailures: 0,
  ...patch
}) as AdminClientMessageMonitorQueueItem;

const preview = () => ({
  updatedAt: '2026-11-21T10:00:00', paymentOverdueDays: 60, publicationStaleDays: 30,
  companyStatuses: { shouldMoveToWork: 4, workWithoutActiveOrders: 7 },
  paymentStatuses: { invoiceOrReminderOlderThanThreshold: 12 },
  unpaidRecovery: { canCreateBadTasks: 3 },
  publication: { farFuturePublishDate: 1, longPublishSpan: 2, undatedUnpublished: 3, oldAllReviewsPublished: 5 },
  archiveOffers: { blockedByActiveOrders: 2, blockedByOpenNextRequest: 1 },
  suggestedActions: []
}) as unknown as AdminClientMessageMaintenancePreview;

const applied = (changed: number, message: string) =>
  ({ action: 'payment-overdue', changed, message, appliedAt: '2026-11-21T10:00:00', preview: preview() }) as AdminMaintenanceApplyResponse;

/**
 * Wired like the page: the message settings are loaded only by the autoresponder tabs (null here unless given); without
 * them the flag falls back to the last snapshot, and patching does nothing while they are absent.
 */
function monitorSetup(settingsEnabled: boolean | null = null) {
  const view = { active: true, visible: true, settings: settingsEnabled };
  const api = {
    getClientMessageMonitor: vi.fn(() => of(monitor())),
    getClientMessageMaintenancePreview: vi.fn(() => of(preview())),
    updateClientMessageMonitorSettings: vi.fn(),
    applyClientMessageMaintenance: vi.fn(),
    retryClientMessageNow: vi.fn(() => of(monitor())),
    disableClientMessageCandidate: vi.fn(() => of(monitor())),
    markClientMessageCandidateDone: vi.fn(() => of(monitor()))
  };
  const toast = { success: vi.fn(), error: vi.fn(), warning: vi.fn() };
  const facade: AdminMessageMonitorFacade = new AdminMessageMonitorFacade({
    api, toast,
    isActive: () => view.active,
    isVisible: () => view.visible,
    enabled: () => view.settings ?? facade.clientMessageMonitor()?.enabled ?? false,
    setEnabled: (enabled) => { if (view.settings !== null) view.settings = enabled; }
  });
  return { facade, api, view, toast };
}

describe('monitor tab: one source of the monitoring state', () => {
  afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); });

  it('shows «Загружаю», not «выключено», until the server answers when the settings tab was not opened', () => {
    const { facade, api } = monitorSetup();
    const answer = new Subject<AdminClientMessageMonitor>();
    api.getClientMessageMonitor.mockReturnValueOnce(answer);
    expect(facade.monitorEnabled()).toBeNull();
    expect(facade.monitorSwitchLabel()).toBe('Загружаю');
    expect(facade.monitorSwitchChecked()).toBe(false);
    facade.loadClientMessageMonitor(false, true);
    expect(facade.monitorSwitchLabel()).toBe('Загружаю');
    answer.next(monitor({ activeCandidates: 4 }));
    expect(facade.monitorEnabled()).toBe(true);
    expect(facade.monitorSwitchChecked()).toBe(true);
    expect(facade.monitorSwitchLabel()).toBe('Наблюдение включено');
    expect(facade.monitorTotal()).toBe(4);
    facade.destroy();
  });

  it('says the state is unknown, not «Загружаю», when the first read failed', () => {
    const { facade, api } = monitorSetup();
    api.getClientMessageMonitor.mockReturnValueOnce(throwError(() => new Error('offline')));
    facade.loadClientMessageMonitor(false, true);
    expect(facade.monitorEnabled()).toBeNull();
    expect(facade.clientMessageMonitorLoading()).toBe(false);
    expect(facade.monitorSwitchLabel()).toBe('Состояние неизвестно');
    facade.destroy();
  });

  it('takes «включено» from the switch answer itself, before the next read of the monitor', () => {
    const { facade, api, toast } = monitorSetup();
    api.getClientMessageMonitor.mockReturnValueOnce(of(monitor({ enabled: false })));
    facade.loadClientMessageMonitor(false, true);
    expect(facade.monitorSwitchLabel()).toBe('Наблюдение выключено');
    const write = new Subject<AdminClientMessageMonitorSettings>();
    const reread = new Subject<AdminClientMessageMonitor>();
    api.updateClientMessageMonitorSettings.mockReturnValue(write);
    api.getClientMessageMonitor.mockReturnValueOnce(reread);
    facade.setClientMessageMonitorEnabled(true);
    expect(facade.monitorSwitchChecked()).toBe(true);
    expect(facade.monitorSwitchLabel()).toBe('Переключаю');
    write.next({ enabled: true }); write.complete();
    expect(reread.observed).toBe(true);
    expect(facade.monitorEnabled()).toBe(true);
    expect(facade.monitorSwitchLabel()).toBe('Наблюдение включено');
    expect(toast.success).toHaveBeenCalledWith('Наблюдение включено', expect.stringContaining('Для всех администраторов'));
    facade.destroy();
  });

  it('puts the switch back after a refused switch and keeps the refusal out of the load error', () => {
    const { facade, api, toast } = monitorSetup();
    api.getClientMessageMonitor.mockReturnValueOnce(of(monitor({ enabled: false })));
    facade.loadClientMessageMonitor(false, true);
    api.updateClientMessageMonitorSettings.mockReturnValue(throwError(() => new Error('нет доступа')));
    facade.setClientMessageMonitorEnabled(true);
    expect(facade.monitorSwitchChecked()).toBe(false);
    expect(facade.monitorEnabled()).toBe(false);
    expect(facade.clientMessageMonitorError()).toBeNull();
    expect(toast.error).toHaveBeenCalledWith('Наблюдение не переключено', expect.any(String));
    facade.destroy();
  });

  it('does not carry a stale state into the next visit after the setting was changed on another tab', () => {
    const { facade, view } = monitorSetup(true);
    facade.loadClientMessageMonitor(false, true);
    expect(facade.monitorEnabled()).toBe(true);
    view.active = false;
    facade.syncClientMessageMonitorPolling();
    view.settings = false;
    view.active = true;
    expect(facade.monitorEnabled()).not.toBe(true);
    facade.destroy();
  });
});

describe('monitor tab: row actions ask first', () => {
  afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); });

  it('asks before «Повторить сейчас», naming the scenario, the order and what happens', () => {
    const { facade, api } = monitorSetup();
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    facade.retryClientMessageCandidate(task({ id: 21, scenario: 'BAD_REVIEW_AUTO_BAN', scenarioLabel: 'Автобан после плохих' }));
    const question = String(confirm.mock.calls[0][0]);
    expect(question).toContain('Повторить сейчас?');
    expect(question).toContain('Сценарий: «Автобан после плохих»');
    expect(question).toContain('Цель: заказ №5 («Ромашка - Центр»)');
    expect(question).toContain('сразу уйдёт в «Бан»');
    expect(api.retryClientMessageNow).not.toHaveBeenCalled();
    expect(facade.clientMessageManualAction()).toBeNull();
    confirm.mockReturnValue(true);
    facade.retryClientMessageCandidate(task({ scenario: 'REVIEW_CHECK_REMINDER', scenarioLabel: 'Проверка отзывов' }));
    expect(String(confirm.mock.calls[1][0])).toContain('засчитается в семь напоминаний');
    expect(api.retryClientMessageNow).toHaveBeenCalledWith(17);
    facade.destroy();
  });

  it('names the scenario and the target in «Пометить выполненной» and «Отключить» and sends only after a yes', () => {
    const { facade, api, toast } = monitorSetup();
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
    facade.markClientMessageCandidateDone(task({ id: 31, scenario: 'PAYMENT_OVERDUE_ESCALATION', scenarioLabel: 'Просрочка оплаты' }));
    expect(String(confirm.mock.calls[0][0])).toContain('Сценарий: «Просрочка оплаты»');
    expect(String(confirm.mock.calls[0][0])).toContain('без отправки и без смены статуса');
    expect(api.markClientMessageCandidateDone).toHaveBeenCalledWith(31);
    expect(toast.success).toHaveBeenCalledWith('Задача помечена выполненной', 'Просрочка оплаты · заказ №5 («Ромашка - Центр»)');
    facade.disableClientMessageCandidate(task({ id: 32, scenario: 'ARCHIVE_REORDER_OFFER', scenarioLabel: 'Архивные компании',
      targetType: 'ARCHIVE_COMPANY', orderId: null, orderTitle: null }));
    expect(String(confirm.mock.calls[1][0])).toContain('Цель: компания «Ромашка»');
    expect(String(confirm.mock.calls[1][0])).toContain('больше не вернётся в очередь');
    expect(api.disableClientMessageCandidate).toHaveBeenCalledWith(32);
    facade.destroy();
  });

  it('does not start a second row action while the question is open', () => {
    const { facade, api } = monitorSetup();
    const item = task();
    const confirm = vi.spyOn(window, 'confirm').mockImplementation(() => {
      facade.retryClientMessageCandidate(item);
      facade.disableClientMessageCandidate(item);
      return true;
    });
    facade.retryClientMessageCandidate(item);
    expect(confirm).toHaveBeenCalledOnce();
    expect(api.retryClientMessageNow).toHaveBeenCalledOnce();
    expect(api.disableClientMessageCandidate).not.toHaveBeenCalled();
    facade.destroy();
  });

  it('after a yes keeps the write lifecycle of row actions: one write, stale answer dropped, reads cancelled', () => {
    vi.useFakeTimers();
    const { facade, api, view, toast } = monitorSetup(true);
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const item = { id: 17, targetKey: 'company:91', scenarioLabel: 'Оплата' } as AdminClientMessageMonitorQueueItem;
    const late = new Subject<AdminClientMessageMonitor>();
    api.retryClientMessageNow.mockReturnValue(late);
    facade.retryClientMessageCandidate(item);
    view.active = false;
    facade.syncClientMessageMonitorPolling();
    expect(late.observed).toBe(true);
    view.active = true;
    facade.retryClientMessageCandidate(item);
    expect(api.retryClientMessageNow).toHaveBeenCalledTimes(1);
    late.next(monitor({ activeCandidates: 99 })); late.complete();
    expect(facade.clientMessageManualAction()).toBeNull();
    expect(facade.clientMessageMonitor()).toBeNull();
    expect(toast.success).not.toHaveBeenCalled();

    const read = new Subject<AdminClientMessageMonitor>();
    const write = new Subject<AdminClientMessageMonitor>();
    api.getClientMessageMonitor.mockReturnValue(read);
    api.retryClientMessageNow.mockReturnValue(write);
    facade.loadClientMessageMonitor();
    facade.retryClientMessageCandidate(item);
    expect(read.observed).toBe(false);
    facade.loadClientMessageMonitor(true);
    expect(api.getClientMessageMonitor).toHaveBeenCalledTimes(1);
    write.next(monitor({ activeCandidates: 7 })); write.complete();
    read.next(monitor({ activeCandidates: 1 }));
    expect(facade.clientMessageMonitor()?.activeCandidates).toBe(7);
    expect(api.retryClientMessageNow).toHaveBeenLastCalledWith(17);
    facade.destroy();
  });

  it('reports a refused row action in its own toast, not as a failed load of the monitor', () => {
    const { facade, api, toast } = monitorSetup();
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    api.retryClientMessageNow.mockReturnValue(throwError(() => new Error('Задача уже обрабатывается')));
    facade.retryClientMessageCandidate(task());
    expect(facade.clientMessageMonitorError()).toBeNull();
    expect(facade.clientMessageManualAction()).toBeNull();
    expect(toast.error).toHaveBeenCalledWith('Действие не выполнено', expect.any(String));
    facade.destroy();
  });
});

describe('monitor tab: check of the base and its updates', () => {
  afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); });

  it('checks the base once per entry while monitoring is on, never from the minute poll or while it is off', () => {
    vi.useFakeTimers();
    const on = monitorSetup();
    on.facade.loadClientMessageMonitor(false, true);
    expect(on.api.getClientMessageMaintenancePreview).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(180_000);
    expect(on.api.getClientMessageMonitor).toHaveBeenCalledTimes(4);
    expect(on.api.getClientMessageMaintenancePreview).toHaveBeenCalledTimes(1);
    on.facade.destroy();

    const off = monitorSetup();
    off.api.getClientMessageMonitor.mockReturnValue(of(monitor({ enabled: false })));
    off.facade.loadClientMessageMonitor(false, true);
    expect(off.facade.monitorEnabled()).toBe(false);
    expect(off.api.getClientMessageMaintenancePreview).not.toHaveBeenCalled();
    off.facade.loadClientMessageMaintenancePreview();
    expect(off.api.getClientMessageMaintenancePreview).not.toHaveBeenCalled();
    expect(off.facade.clientMessageMaintenancePreviewLoading()).toBe(false);
    expect(off.toast.warning).toHaveBeenCalledWith('Проверка базы не запущена', expect.stringContaining('Включите наблюдение'));
    off.facade.destroy();
  });

  it('asks with the numbers of the last check and the limits, and sends nothing on «Отмена»', () => {
    const { facade, api } = monitorSetup();
    facade.loadClientMessageMonitor(false, true);
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    facade.applyClientMessageMaintenance('company-statuses', 'актуализировать статусы компаний');
    expect(String(confirm.mock.calls[0][0])).toContain('Вернуть в «В работе»: 4. Поставить «На стопе»: 7');
    expect(String(confirm.mock.calls[0][0])).not.toContain('не больше');
    facade.applyClientMessageMaintenance('payment-overdue', 'перевести старые счета в Не оплачено');
    expect(String(confirm.mock.calls[1][0])).toContain('60 дн. и дольше: 12');
    expect(String(confirm.mock.calls[1][0])).toContain('не больше 200 заказов');
    expect(api.applyClientMessageMaintenance).not.toHaveBeenCalled();
    expect(facade.maintenanceAction()).toBeNull();
    facade.destroy();
  });

  it('reports nothing changed or a partial failure as a warning and keeps the outcome for the card', () => {
    const { facade, api, toast } = monitorSetup();
    facade.loadClientMessageMonitor(false, true);
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    api.applyClientMessageMaintenance
      .mockReturnValueOnce(of(applied(5, 'Просроченные счета переведены в Не оплачено через бизнес-логику: 5, не удалось: 2')))
      .mockReturnValueOnce(of(applied(0, 'Заблокированы лишние архивные предложения: 0')))
      .mockReturnValueOnce(of(applied(3, 'Созданы недостающие плохие задачи для старых неоплат: 3')));
    facade.applyClientMessageMaintenance('payment-overdue', 'перевести старые счета в Не оплачено');
    expect(toast.warning).toHaveBeenLastCalledWith('Выполнено не полностью', expect.stringContaining('не удалось: 2'));
    facade.applyClientMessageMaintenance('archive-offers', 'заблокировать лишние архивные предложения');
    expect(toast.warning).toHaveBeenLastCalledWith('Ничего не изменено', expect.any(String));
    facade.applyClientMessageMaintenance('missing-bad-tasks', 'создать недостающие плохие задачи');
    expect(toast.success).toHaveBeenLastCalledWith('Актуализация выполнена', expect.stringContaining(': 3'));
    expect(facade.maintenanceResult()).toMatchObject({ tone: 'success', title: 'Актуализация выполнена' });
    expect(api.getClientMessageMaintenancePreview).toHaveBeenCalledTimes(1);
    facade.destroy();
  });

  it('keeps the outcome of an update that ends while the browser tab is hidden, and reports one that ends after the return', () => {
    const { facade, api, view, toast } = monitorSetup();
    facade.loadClientMessageMonitor(false, true);
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const hidden = new Subject<AdminMaintenanceApplyResponse>();
    api.applyClientMessageMaintenance.mockReturnValueOnce(hidden);
    facade.applyClientMessageMaintenance('payment-overdue', 'перевести старые счета в Не оплачено');
    view.visible = false;
    facade.syncClientMessageMonitorPolling();
    hidden.next(applied(4, 'Просроченные счета переведены в Не оплачено через бизнес-логику: 4, не удалось: 1')); hidden.complete();
    expect(toast.warning).not.toHaveBeenCalled();
    expect(facade.maintenanceResult()).toMatchObject({ tone: 'warning', title: 'Выполнено не полностью' });
    expect(facade.maintenanceAction()).toBeNull();

    view.visible = true;
    facade.syncClientMessageMonitorPolling();
    const back = new Subject<AdminMaintenanceApplyResponse>();
    api.applyClientMessageMaintenance.mockReturnValueOnce(back);
    facade.applyClientMessageMaintenance('publication-completed', 'перевести завершенные публикации в Опубликовано');
    view.visible = false;
    facade.syncClientMessageMonitorPolling();
    view.visible = true;
    facade.syncClientMessageMonitorPolling();
    const reads = api.getClientMessageMonitor.mock.calls.length;
    back.next(applied(2, 'Завершенные публикации переведены в Опубликовано через бизнес-логику: 2')); back.complete();
    expect(toast.success).toHaveBeenCalledWith('Актуализация выполнена', expect.stringContaining(': 2'));
    expect(api.getClientMessageMonitor).toHaveBeenCalledTimes(reads + 1);
    facade.destroy();
  });
});

describe('monitor tab: queue and scenario cards', () => {
  it('counts only real failures under «С ошибкой», not planned waits', () => {
    const { facade } = monitorSetup();
    facade.clientMessageMonitor.set(monitor({ queue: [
      task({ id: 1, consecutiveFailures: 2, lastErrorCode: 'send_failed', lastErrorMessage: 'Telegram не ответил' }),
      task({ id: 2, consecutiveFailures: 0, lastErrorCode: 'daily_limit', lastErrorMessage: 'Дневной лимит исчерпан' }),
      task({ id: 3, consecutiveFailures: 1, lastErrorCode: 'archive_waiting', lastErrorMessage: 'История переписки недоступна' }),
      // Failed once, now only waits for the daily limit: the postponement keeps the failure counter.
      task({ id: 4, consecutiveFailures: 1, lastErrorCode: 'daily_limit', lastErrorMessage: 'Дневной лимит авторассылки исчерпан' })
    ] }));
    facade.setMonitorQueueStatusFilter('ERROR');
    expect(facade.filteredMonitorQueue().map((item) => item.id)).toEqual([1]);
    facade.destroy();
  });

  it('opens the preview of one task when two tasks of one order share a target', () => {
    const { facade } = monitorSetup();
    const payment = task({ id: 1, targetKey: 'order:5:7' });
    const overdue = task({ id: 2, targetKey: 'order:5:7', scenario: 'PAYMENT_OVERDUE_ESCALATION' });
    facade.toggleMonitorQueueDetails(payment);
    expect(facade.isMonitorQueueExpanded(payment)).toBe(true);
    expect(facade.isMonitorQueueExpanded(overdue)).toBe(false);
    facade.toggleMonitorQueueDetails(payment);
    expect(facade.isMonitorQueueExpanded(payment)).toBe(false);
    facade.toggleMonitorQueueDetails(overdue);
    facade.setMonitorSearch('ромашка');
    expect(facade.isMonitorQueueExpanded(overdue)).toBe(false);
    facade.destroy();
  });

  it('names targets, channels and scenario results in words and colours only a fresh last error', () => {
    const { facade } = monitorSetup();
    facade.clientMessageMonitor.set(monitor({ nowIrkutsk: '2026-11-21T10:00:00' }));
    const card = (patch: Partial<AdminClientMessageMonitorScenario>) => ({
      scenario: 'PAYMENT_REMINDER', label: 'Оплата', activeCandidates: 0, dueNow: 0, readyToSendNow: 0, waitingForWindow: 0,
      missingChannelBindings: 0, sentToday: 0, sentSevenDays: 0, failedToday: 0, skippedToday: 0, ...patch
    }) as AdminClientMessageMonitorScenario;
    expect(facade.monitorScenarioTone(card({ lastError: 'Telegram не ответил', lastErrorAt: '2026-11-12T14:05:00' }))).toBe('teal');
    expect(facade.monitorScenarioTone(card({ lastError: 'Telegram не ответил', lastErrorAt: '2026-11-21T09:05:00' }))).toBe('pink');
    expect(facade.monitorScenarioSentWord(card({ scenario: 'BAD_REVIEW_AUTO_BAN' }))).toBe('выполнено');
    expect(facade.monitorScenarioSentWord(card({}))).toBe('отправлено');
    for (const scenario of ['REVIEW_CHECK_DELIVERY_RETRY', 'PAYMENT_INVOICE_RETRY', 'REVIEW_CHECK_AUTO_ARCHIVE']) {
      expect(facade.monitorScenarioIcon(card({ scenario }))).not.toBe('mark_chat_unread');
      expect(facade.monitorScenarioTooltip(card({ scenario }))).not.toContain('Сценарий автоответчика');
    }
    expect(facade.monitorTargetTypeLabel('ORDER')).toBe('заказ');
    expect(facade.monitorTargetTypeLabel('ARCHIVE_COMPANY')).toBe('компания из архива');
    expect(facade.monitorChannelLabel('system')).toBe('система: смена статуса');
    expect(facade.monitorChannelLabel(null)).toBe('канал не выбран');
    expect(facade.monitorScenarioHasPayment(task())).toBe(true);
    expect(facade.monitorScenarioHasPayment(task({ scenario: 'REVIEW_CHECK_REMINDER' }))).toBe(false);
    facade.destroy();
  });
});

describe('AI provider choice', () => {
  afterEach(() => { vi.restoreAllMocks(); });
  const aiStatus = (aiProvider: ReputationAiProvider) => ({ aiProvider, warnings: [] }) as unknown as ReputationAiStatus;

  it('asks before leaving DeepSeek and names the manager-control checks that stop working', () => {
    const api = {
      status: vi.fn(() => of(aiStatus('deepseek'))),
      selectProvider: vi.fn((provider: ReputationAiProvider) => of(aiStatus(provider)))
    };
    const toast = { success: vi.fn(), error: vi.fn() };
    const facade = new AdminAiProviderFacade({ api, toast, isActive: () => true });
    facade.loadAiProviderStatus();
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    facade.selectAiProvider('yandexgpt');
    expect(api.selectProvider).not.toHaveBeenCalled();
    expect(facade.switchingAiProvider()).toBe(false);
    const question = String(confirm.mock.calls[0][0]);
    expect(question).toContain('Переключить AI на YandexGPT?');
    expect(question).toContain('работают только с DeepSeek');
    expect(question).toContain('«Проверить: ответ не нужен»');
    expect(question).toContain('отчётам менеджеров');
    expect(question).toContain('пояснений исполнителей');
    expect(question).toContain('ежедневных сводках');

    confirm.mockReturnValue(true);
    facade.selectAiProvider('openai');
    expect(api.selectProvider).toHaveBeenCalledWith('openai');
    expect(toast.success).toHaveBeenCalledWith('AI-провайдер переключён', expect.stringContaining('Проверки контроля менеджеров'));
    facade.selectAiProvider('yandexgpt');
    expect(confirm).toHaveBeenCalledTimes(2);
    expect(api.selectProvider).toHaveBeenLastCalledWith('yandexgpt');
    expect(facade.aiSearchProviderLabel('yandex')).toBe('Яндекс Поиск');
    expect(facade.aiSearchProviderLabel('local')).toBe('не подключён');
    facade.destroy();
  });
});

describe('autoarchive check of the monitor tab', () => {
  const url = '/api/admin/client-messages/archive-checks';
  const row = { stateId: 41, orderId: 512, company: 'Ромашка', channel: 'TELEGRAM', outcome: null, reason: null, checkedAt: null, nextCheckAt: null };

  function render() {
    TestBed.configureTestingModule({ imports: [ReviewArchiveChecksComponent], providers: [provideHttpClient(), provideHttpClientTesting()] });
    const fixture = TestBed.createComponent(ReviewArchiveChecksComponent);
    fixture.detectChanges();
    return { fixture, http: TestBed.inject(HttpTestingController), page: fixture.componentInstance, element: fixture.nativeElement as HTMLElement };
  }

  it('warns in the confirmation that the client and the staff get notifications', () => {
    const { fixture, http, element } = render();
    http.expectOne(url).flush([row]);
    fixture.detectChanges();
    (element.querySelector('.ac-button--check') as HTMLButtonElement).click();
    fixture.detectChanges();
    const text = element.querySelector('#archive-check-text')?.textContent ?? '';
    expect(text).toContain('клиенту и сотрудникам будут отправлены уведомления');
    expect(text).toContain('предложение нового заказа');
    fixture.destroy();
  });

  it('names the order in the result, keeps it through the reload after the check and clears it on «Обновить проверки»', () => {
    const { fixture, http, page } = render();
    http.expectOne(url).flush([row]);
    page.check(row);
    http.expectOne(`${url}/41/check`).flush({ archived: false, reason: 'История переписки сейчас недоступна. Система повторит проверку.' });
    http.expectOne(url).flush([row]);
    expect(page.message()).toBe('Заказ №512: История переписки сейчас недоступна. Система повторит проверку.');
    page.refresh();
    expect(page.message()).toBeNull();
    http.expectOne(url).flush([row]);
    page.check(row);
    http.expectOne(`${url}/41/check`).flush({ archived: false, reason: null });
    expect(page.message()).toBe('Заказ №512: заказ не перенесён в архив.');
    http.expectOne(url).flush([]);
    http.verify();
    fixture.destroy();
  });
});
