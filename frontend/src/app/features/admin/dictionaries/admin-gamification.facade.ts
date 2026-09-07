type GamificationProgressDays = 1 | 7 | 30;
import { computed, signal, WritableSignal } from '@angular/core';
import { FormBuilder, Validators } from '@angular/forms';
import { forkJoin } from 'rxjs';
import {
  AdminGamificationBalance,
  AdminGamificationBalances,
  AdminGamificationBackfill,
  AdminGamificationEvent,
  AdminGamificationProgress,
  AdminGamificationRule,
  AdminGamificationRulesRequest,
  AdminGamificationRulesResponse,
  AdminGamificationScoreLedger,
  AdminGamificationScoreLedgerRebuild,
  AdminGamificationScorePreview,
  AdminGamificationSettings,
  AdminGamificationSettingsRequest
} from '../../../core/admin-dictionaries.api';
import {
  AdminGamificationRewardsApi,
  GamificationRewardSettings
} from '../../../core/admin-gamification-rewards.api';
import { apiErrorMessage } from '../../../shared/api-error-message';
import { ToastService } from '../../../shared/toast.service';
import { DictionaryViewScope } from './dictionary-view-scope';
import { AdminGamificationApi } from '../../../core/admin-gamification.api';
type GamificationDictionaryResponse = {
  settings: AdminGamificationSettings;
  rules: AdminGamificationRulesResponse;
  progress: AdminGamificationProgress;
  scorePreview: AdminGamificationScorePreview;
  scoreLedger: AdminGamificationScoreLedger;
  balances: AdminGamificationBalances;
  events: AdminGamificationEvent[];
};
type Deps = {
  api: AdminGamificationApi;
  toast: Pick<ToastService, 'success' | 'error'>;
  isActive: () => boolean;
  selectedId: WritableSignal<number | null>;
  search: () => string;
  rewardsApi: AdminGamificationRewardsApi;
};

export class AdminGamificationFacade {
  private readonly fb = new FormBuilder();
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly deleting = signal(false);
  readonly error = signal<string | null>(null);
  readonly scope: DictionaryViewScope;
  constructor(private readonly deps: Deps) {
    this.scope = new DictionaryViewScope(deps.isActive);
  }
  private get dictionariesApi() {
    return this.deps.api;
  }
  private get toastService() {
    return this.deps.toast;
  }
  private get selectedId() {
    return this.deps.selectedId;
  }
  deactivate(): void {
    this.scope.deactivate();
  }
  destroy(): void {
    this.scope.destroy();
  }
  private errorMessage(error: unknown, fallback: string): string {
    return apiErrorMessage(error, fallback);
  }
  private failLoad(error: unknown): void {
    const message = this.errorMessage(error, 'Не удалось загрузить справочник');
    this.error.set(message);
    this.toastService.error('Справочник не загрузился', message);
  }
  private get rewardsApi() {
    return this.deps.rewardsApi;
  }
  load(): void {
    if (!this.scope.active()) return;
    this.error.set(null);
    const days = this.gamificationProgressDays();
    this.scope
      .read(
        'progress',
        forkJoin({
          settings: this.dictionariesApi.getGamificationSettings(),
          rules: this.dictionariesApi.getGamificationRules(),
          progress: this.dictionariesApi.getGamificationProgress(days),
          scorePreview: this.dictionariesApi.getGamificationScorePreview(days),
          scoreLedger: this.dictionariesApi.getGamificationScoreLedger(days),
          balances: this.dictionariesApi.getGamificationBalances(days),
          events: this.dictionariesApi.getGamificationEvents(),
          rewards: this.rewardsApi.settings()
        }),
        this.loading
      )
      .subscribe({
        next: (response) => {
          this.applyGamificationResponse(response);
          this.applyRewardSettings(response.rewards);
        },
        error: (error) => this.failLoad(error)
      });
  }

  readonly gamificationSettings = signal<AdminGamificationSettings | null>(null);

  readonly rewardSettings = signal<GamificationRewardSettings | null>(null);

  readonly gamificationRules = signal<AdminGamificationRule[]>([]);

  readonly gamificationProgress = signal<AdminGamificationProgress | null>(null);

  readonly gamificationProgressDays = signal<GamificationProgressDays>(1);

  readonly gamificationScorePreview = signal<AdminGamificationScorePreview | null>(null);

  readonly gamificationScoreLedger = signal<AdminGamificationScoreLedger | null>(null);

  readonly gamificationLedgerRebuild = signal<AdminGamificationScoreLedgerRebuild | null>(null);

  readonly gamificationBackfill = signal<AdminGamificationBackfill | null>(null);

  readonly gamificationBalances = signal<AdminGamificationBalances | null>(null);

  readonly gamificationEvents = signal<AdminGamificationEvent[]>([]);

  readonly topWorkerGamificationScoreActors = computed(() =>
    this.byRole(this.gamificationScorePreview()?.topActors ?? [], 'WORKER').slice(0, 8)
  );

  readonly topManagerGamificationScoreActors = computed(() =>
    this.byRole(this.gamificationScorePreview()?.topActors ?? [], 'MANAGER').slice(0, 8)
  );

  readonly topOtherGamificationScoreActors = computed(() =>
    this.withoutRoles(this.gamificationScorePreview()?.topActors ?? [], [
      'WORKER',
      'MANAGER'
    ]).slice(0, 8)
  );

  readonly workerGamificationScoreActors = computed(() =>
    this.byRole(this.gamificationScorePreview()?.topActors ?? [], 'WORKER')
  );

  readonly managerGamificationScoreActors = computed(() =>
    this.byRole(this.gamificationScorePreview()?.topActors ?? [], 'MANAGER')
  );

  readonly otherGamificationScoreActors = computed(() =>
    this.withoutRoles(this.gamificationScorePreview()?.topActors ?? [], ['WORKER', 'MANAGER'])
  );

  readonly workerGamificationLedgerActors = computed(() =>
    this.byRole(this.gamificationScoreLedger()?.topActors ?? [], 'WORKER')
  );

  readonly managerGamificationLedgerActors = computed(() =>
    this.byRole(this.gamificationScoreLedger()?.topActors ?? [], 'MANAGER')
  );

  readonly otherGamificationLedgerActors = computed(() =>
    this.withoutRoles(this.gamificationScoreLedger()?.topActors ?? [], ['WORKER', 'MANAGER'])
  );

  readonly workerGamificationBalances = computed(() =>
    this.byRole(this.gamificationBalances()?.balances ?? [], 'WORKER')
  );

  readonly managerGamificationBalances = computed(() =>
    this.byRole(this.gamificationBalances()?.balances ?? [], 'MANAGER')
  );

  readonly otherGamificationBalances = computed(() =>
    this.withoutRoles(this.gamificationBalances()?.balances ?? [], ['WORKER', 'MANAGER'])
  );

  readonly recentGamificationEvents = computed(() => this.gamificationEvents().slice(0, 6));

  readonly gamificationForm = this.fb.nonNullable.group({
    enabled: [false],
    workerEnabled: [true],
    managerEnabled: [true],
    operatorEnabled: [true],
    marketologEnabled: [true],
    showInCabinet: [false],
    showInScore: [false],
    eventsEnabled: [false],
    shadowScoringEnabled: [false],
    rewardsEnabled: [false],
    competitionEnabled: [false],
    levelXp: [500, [Validators.required, Validators.min(100)]],
    tokenLevelStep: [5, [Validators.required, Validators.min(2)]],
    slaEnabled: [false],
    controlTargetHours: [14, [Validators.required, Validators.min(1), Validators.max(24)]],
    dayTargetPercent: [90, [Validators.required, Validators.min(1), Validators.max(100)]],
    messageTargetMinutes: [30, [Validators.required, Validators.min(1)]],
    messageHardMinutes: [480, [Validators.required, Validators.min(1)]],
    leadTargetMinutes: [60, [Validators.required, Validators.min(1)]],
    leadHardMinutes: [480, [Validators.required, Validators.min(1)]],
    riskTargetMinutes: [30, [Validators.required, Validators.min(1)]],
    riskHardMinutes: [240, [Validators.required, Validators.min(1)]],
    defaultTargetMinutes: [120, [Validators.required, Validators.min(1)]],
    defaultHardMinutes: [720, [Validators.required, Validators.min(1)]],
    reviewPublishedRuleEnabled: [true],
    reviewPublishedRulePoints: [10],
    orderPaidRuleEnabled: [true],
    orderPaidRulePoints: [25],
    badReviewTaskDoneRuleEnabled: [true],
    badReviewTaskDoneRulePoints: [15],
    reviewRecoveryTaskDoneRuleEnabled: [true],
    reviewRecoveryTaskDoneRulePoints: [20]
  });

  gamificationTotal(): number {
    const settings = this.gamificationSettings();
    if (!settings) {
      return 0;
    }
    return [
      settings.enabled,
      settings.workerEnabled,
      settings.managerEnabled,
      settings.operatorEnabled,
      settings.marketologEnabled,
      settings.showInCabinet,
      settings.showInScore,
      settings.eventsEnabled,
      settings.shadowScoringEnabled
    ].filter(Boolean).length;
  }

  gamificationEventLabel(eventType: string | null | undefined): string {
    return (
      {
        REVIEW_PUBLISHED: 'Отзыв опубликован',
        ORDER_PAID: 'Заказ закрыт оплатой',
        BAD_REVIEW_TASK_DONE: 'Плохой отзыв выполнен',
        REVIEW_RECOVERY_TASK_DONE: 'Восстановление выполнено',
        WORKER_DAY_100: 'Специалист закрыл день на 100%',
        WORKER_100_STREAK: 'Серия специалиста на 100%',
        MANAGER_TEAM_DAY_100: 'Команда закрыла день на 100%',
        MANAGER_TEAM_100_STREAK: 'Командная серия на 100%'
      }[eventType ?? ''] ??
      (eventType || 'Событие')
    );
  }

  gamificationRoleLabel(role: string | null | undefined): string {
    return (
      {
        WORKER: 'Специалист',
        MANAGER: 'Менеджер',
        OPERATOR: 'Оператор',
        MARKETOLOG: 'Маркетолог'
      }[role ?? ''] ??
      (role || '-')
    );
  }

  gamificationActor(event: AdminGamificationEvent): string {
    if (event.actorName) {
      return event.actorName;
    }
    if (event.actorRole) {
      return event.actorRole;
    }
    return 'Система';
  }

  gamificationEventTarget(event: AdminGamificationEvent): string {
    const parts = [
      event.orderId ? `Заказ ${event.orderId}` : null,
      event.reviewId ? `Отзыв ${event.reviewId}` : null,
      event.badReviewTaskId ? `Плохая ${event.badReviewTaskId}` : null,
      event.recoveryTaskId ? `Восстановление ${event.recoveryTaskId}` : null
    ].filter(Boolean);
    return parts.length ? parts.join(' · ') : 'Без привязки';
  }

  gamificationTimelinessLabel(event: AdminGamificationEvent): string {
    if (!event.plannedDate || !event.actualDate) {
      return 'без срока';
    }
    const delay = event.delayDays ?? 0;
    if (delay <= 0) {
      return 'в срок';
    }
    const percent = Math.round((event.timelinessMultiplier ?? 1) * 100);
    return `+${delay} дн. · ${percent}%`;
  }

  trackGamificationEvent(_index: number, event: AdminGamificationEvent): number {
    return event.id;
  }

  trackGamificationScoreActor(
    index: number,
    item: { actorUserId?: number | null; actorName?: string | null; actorRole?: string | null }
  ): string {
    return `${item.actorUserId ?? 'system'}-${item.actorRole ?? 'role'}-${item.actorName ?? index}`;
  }

  trackGamificationBalance(index: number, item: AdminGamificationBalance): string {
    return `${item.actorUserId ?? 'system'}-${item.actorRole ?? 'role'}-${item.actorName ?? index}`;
  }

  byRole<T extends { actorRole?: string | null }>(items: T[], role: string): T[] {
    return items.filter((item) => item.actorRole === role);
  }

  withoutRoles<T extends { actorRole?: string | null }>(items: T[], roles: string[]): T[] {
    return items.filter((item) => !roles.includes(item.actorRole ?? ''));
  }

  setGamificationProgressDays(days: GamificationProgressDays): void {
    if (this.gamificationProgressDays() === days) {
      return;
    }
    this.gamificationProgressDays.set(days);
    this.loadGamificationProgress();
  }

  trackGamificationType(_index: number, item: { eventType: string }): string {
    return item.eventType;
  }

  trackGamificationActor(
    index: number,
    item: { actorUserId?: number | null; actorName?: string | null; actorRole?: string | null }
  ): string {
    return `${item.actorUserId ?? 'system'}-${item.actorRole ?? 'role'}-${item.actorName ?? index}`;
  }

  saveGamificationRules(): void {
    if (!this.scope.active() || this.scope.writing() || this.saving() || this.deleting()) return;

    const raw = this.gamificationForm.getRawValue();
    const request: AdminGamificationRulesRequest = {
      rules: [
        {
          eventType: 'REVIEW_PUBLISHED',
          enabled: raw.reviewPublishedRuleEnabled,
          points: raw.reviewPublishedRulePoints
        },
        {
          eventType: 'ORDER_PAID',
          enabled: raw.orderPaidRuleEnabled,
          points: raw.orderPaidRulePoints
        },
        {
          eventType: 'BAD_REVIEW_TASK_DONE',
          enabled: raw.badReviewTaskDoneRuleEnabled,
          points: raw.badReviewTaskDoneRulePoints
        },
        {
          eventType: 'REVIEW_RECOVERY_TASK_DONE',
          enabled: raw.reviewRecoveryTaskDoneRuleEnabled,
          points: raw.reviewRecoveryTaskDoneRulePoints
        }
      ]
    };

    this.saving.set(true);
    this.error.set(null);

    this.scope.write(this.dictionariesApi.updateGamificationRules(request), this.saving).subscribe({
      next: (rules) => {
        this.saving.set(false);
        this.applyGamificationRules(rules);
        this.loadGamificationProgress();
        this.toastService.success('Правила очков сохранены', 'Предпросмотр обновлен');
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось сохранить правила очков');
        this.error.set(message);
        this.saving.set(false);
        this.toastService.error('Правила не сохранены', message);
      }
    });
  }

  rebuildGamificationLedger(): void {
    if (!this.scope.active() || this.scope.writing() || this.saving() || this.deleting()) return;

    if (!this.gamificationSettings()?.shadowScoringEnabled) {
      this.toastService.error('Ledger не пересобран', 'Сначала включите теневое начисление очков');
      return;
    }

    const days = this.gamificationProgressDays();
    this.saving.set(true);
    const confirmed = window.confirm(
      `Пересобрать shadow-ledger за ${days} дн.? Текущие shadow-записи за период будут заменены.`
    );
    if (!confirmed) {
      this.saving.set(false);
      return;
    }

    this.saving.set(true);
    this.error.set(null);

    this.scope
      .write(this.dictionariesApi.rebuildGamificationScoreLedger(days), this.saving)
      .subscribe({
        next: (result) => {
          this.saving.set(false);
          this.gamificationLedgerRebuild.set(result);
          this.loadGamificationProgress();
          this.toastService.success(
            'Ledger пересобран',
            `Событий: ${result.eventsReviewed}, записей: ${result.entriesCreated}, очков: ${result.totalPoints}`
          );
        },
        error: (err) => {
          const message = this.errorMessage(err, 'Не удалось пересобрать ledger');
          this.error.set(message);
          this.saving.set(false);
          this.toastService.error('Ledger не пересобран', message);
        }
      });
  }

  backfillGamificationEvents(): void {
    if (!this.scope.active() || this.scope.writing() || this.saving() || this.deleting()) return;

    const days = this.gamificationProgressDays();
    this.saving.set(true);
    const confirmed = window.confirm(
      `Собрать исторические события геймификации за ${days} дн.? Дубли будут пропущены, ledger будет пересобран.`
    );
    if (!confirmed) {
      this.saving.set(false);
      return;
    }

    this.saving.set(true);
    this.error.set(null);

    this.scope.write(this.dictionariesApi.backfillGamificationEvents(days), this.saving).subscribe({
      next: (result) => {
        this.saving.set(false);
        this.gamificationBackfill.set(result);
        this.gamificationLedgerRebuild.set(result.ledgerRebuild);
        this.loadGamificationProgress();
        this.toastService.success(
          'История собрана',
          `Проверено: ${result.reviewedCandidates}, новых событий: ${result.eventsCreated}, очков: ${result.ledgerRebuild.totalPoints}`
        );
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось собрать исторические события');
        this.error.set(message);
        this.saving.set(false);
        this.toastService.error('История не собрана', message);
      }
    });
  }

  saveGamificationSettings(): void {
    if (!this.scope.active() || this.scope.writing() || this.saving() || this.deleting()) return;

    if (this.gamificationForm.invalid) {
      this.gamificationForm.markAllAsTouched();
      return;
    }

    const raw = this.gamificationForm.getRawValue();
    const request: AdminGamificationSettingsRequest = {
      enabled: raw.enabled,
      workerEnabled: raw.workerEnabled,
      managerEnabled: raw.managerEnabled,
      operatorEnabled: raw.operatorEnabled,
      marketologEnabled: raw.marketologEnabled,
      showInCabinet: raw.showInCabinet,
      showInScore: raw.showInScore,
      eventsEnabled: raw.eventsEnabled,
      shadowScoringEnabled: raw.shadowScoringEnabled
    };
    const rewardRequest: GamificationRewardSettings = {
      rewardsEnabled: raw.rewardsEnabled,
      competitionEnabled: raw.competitionEnabled,
      levelXp: raw.levelXp,
      tokenLevelStep: raw.tokenLevelStep,
      slaEnabled: raw.slaEnabled,
      controlTargetHours: raw.controlTargetHours,
      dayTargetPercent: raw.dayTargetPercent,
      messageTargetMinutes: raw.messageTargetMinutes,
      messageHardMinutes: raw.messageHardMinutes,
      leadTargetMinutes: raw.leadTargetMinutes,
      leadHardMinutes: raw.leadHardMinutes,
      riskTargetMinutes: raw.riskTargetMinutes,
      riskHardMinutes: raw.riskHardMinutes,
      defaultTargetMinutes: raw.defaultTargetMinutes,
      defaultHardMinutes: raw.defaultHardMinutes
    };

    this.saving.set(true);
    this.error.set(null);

    this.scope
      .write(
        forkJoin({
          settings: this.dictionariesApi.updateGamificationSettings(request),
          rewardSettings: this.rewardsApi.updateSettings(rewardRequest)
        }),
        this.saving
      )
      .subscribe({
        next: ({ settings, rewardSettings }) => {
          this.saving.set(false);
          this.applyGamificationSettings(settings);
          this.applyRewardSettings(rewardSettings);
          this.toastService.success(
            'Геймификация сохранена',
            settings.enabled ? 'Контур включен' : 'Контур выключен'
          );
        },
        error: (err) => {
          const message = this.errorMessage(err, 'Не удалось сохранить геймификацию');
          this.error.set(message);
          this.saving.set(false);
          this.toastService.error('Геймификация не сохранена', message);
        }
      });
  }

  applyGamificationSettings(response: AdminGamificationSettings): void {
    this.gamificationSettings.set(response);
    this.gamificationForm.patchValue({
      enabled: response.enabled,
      workerEnabled: response.workerEnabled,
      managerEnabled: response.managerEnabled,
      operatorEnabled: response.operatorEnabled,
      marketologEnabled: response.marketologEnabled,
      showInCabinet: response.showInCabinet,
      showInScore: response.showInScore,
      eventsEnabled: response.eventsEnabled,
      shadowScoringEnabled: response.shadowScoringEnabled
    });
  }

  applyRewardSettings(response: GamificationRewardSettings): void {
    this.rewardSettings.set(response);
    this.gamificationForm.patchValue(response);
  }

  applyGamificationRules(response: AdminGamificationRulesResponse): void {
    this.gamificationRules.set(response.rules);
    const rule = (eventType: string): AdminGamificationRule | undefined =>
      response.rules.find((item) => item.eventType === eventType);
    this.gamificationForm.patchValue({
      reviewPublishedRuleEnabled: rule('REVIEW_PUBLISHED')?.enabled ?? true,
      reviewPublishedRulePoints: rule('REVIEW_PUBLISHED')?.points ?? 10,
      orderPaidRuleEnabled: rule('ORDER_PAID')?.enabled ?? true,
      orderPaidRulePoints: rule('ORDER_PAID')?.points ?? 25,
      badReviewTaskDoneRuleEnabled: rule('BAD_REVIEW_TASK_DONE')?.enabled ?? true,
      badReviewTaskDoneRulePoints: rule('BAD_REVIEW_TASK_DONE')?.points ?? 15,
      reviewRecoveryTaskDoneRuleEnabled: rule('REVIEW_RECOVERY_TASK_DONE')?.enabled ?? true,
      reviewRecoveryTaskDoneRulePoints: rule('REVIEW_RECOVERY_TASK_DONE')?.points ?? 20
    });
  }

  applyGamificationResponse(response: GamificationDictionaryResponse): void {
    this.applyGamificationSettings(response.settings);
    this.applyGamificationRules(response.rules);
    this.gamificationProgress.set(response.progress);
    this.gamificationScorePreview.set(response.scorePreview);
    this.gamificationScoreLedger.set(response.scoreLedger);
    this.gamificationBalances.set(response.balances);
    this.gamificationEvents.set(response.events);
  }

  loadGamificationProgress(): void {
    if (!this.gamificationSettings()) {
      this.load();
      return;
    }
    this.scope
      .read(
        'progress',
        forkJoin({
          progress: this.dictionariesApi.getGamificationProgress(this.gamificationProgressDays()),
          scorePreview: this.dictionariesApi.getGamificationScorePreview(
            this.gamificationProgressDays()
          ),
          scoreLedger: this.dictionariesApi.getGamificationScoreLedger(
            this.gamificationProgressDays()
          ),
          balances: this.dictionariesApi.getGamificationBalances(this.gamificationProgressDays())
        })
      )
      .subscribe({
        next: ({ progress, scorePreview, scoreLedger, balances }) => {
          this.gamificationProgress.set(progress);
          this.gamificationScorePreview.set(scorePreview);
          this.gamificationScoreLedger.set(scoreLedger);
          this.gamificationBalances.set(balances);
        },
        error: (err) => {
          const message = this.errorMessage(err, 'Не удалось загрузить прогресс геймификации');
          this.toastService.error('Прогресс не загружен', message);
        }
      });
  }

  resetGamificationForm(): void {
    const settings = this.gamificationSettings();
    const rule = (eventType: string): AdminGamificationRule | undefined =>
      this.gamificationRules().find((item) => item.eventType === eventType);
    this.gamificationForm.reset({
      enabled: settings?.enabled ?? false,
      workerEnabled: settings?.workerEnabled ?? true,
      managerEnabled: settings?.managerEnabled ?? true,
      operatorEnabled: settings?.operatorEnabled ?? true,
      marketologEnabled: settings?.marketologEnabled ?? true,
      showInCabinet: settings?.showInCabinet ?? false,
      showInScore: settings?.showInScore ?? false,
      eventsEnabled: settings?.eventsEnabled ?? false,
      shadowScoringEnabled: settings?.shadowScoringEnabled ?? false,
      rewardsEnabled: this.rewardSettings()?.rewardsEnabled ?? false,
      competitionEnabled: this.rewardSettings()?.competitionEnabled ?? false,
      levelXp: this.rewardSettings()?.levelXp ?? 500,
      tokenLevelStep: this.rewardSettings()?.tokenLevelStep ?? 5,
      slaEnabled: this.rewardSettings()?.slaEnabled ?? false,
      controlTargetHours: this.rewardSettings()?.controlTargetHours ?? 14,
      dayTargetPercent: this.rewardSettings()?.dayTargetPercent ?? 90,
      messageTargetMinutes: this.rewardSettings()?.messageTargetMinutes ?? 30,
      messageHardMinutes: this.rewardSettings()?.messageHardMinutes ?? 480,
      leadTargetMinutes: this.rewardSettings()?.leadTargetMinutes ?? 60,
      leadHardMinutes: this.rewardSettings()?.leadHardMinutes ?? 480,
      riskTargetMinutes: this.rewardSettings()?.riskTargetMinutes ?? 30,
      riskHardMinutes: this.rewardSettings()?.riskHardMinutes ?? 240,
      defaultTargetMinutes: this.rewardSettings()?.defaultTargetMinutes ?? 120,
      defaultHardMinutes: this.rewardSettings()?.defaultHardMinutes ?? 720,
      reviewPublishedRuleEnabled: rule('REVIEW_PUBLISHED')?.enabled ?? true,
      reviewPublishedRulePoints: rule('REVIEW_PUBLISHED')?.points ?? 10,
      orderPaidRuleEnabled: rule('ORDER_PAID')?.enabled ?? true,
      orderPaidRulePoints: rule('ORDER_PAID')?.points ?? 25,
      badReviewTaskDoneRuleEnabled: rule('BAD_REVIEW_TASK_DONE')?.enabled ?? true,
      badReviewTaskDoneRulePoints: rule('BAD_REVIEW_TASK_DONE')?.points ?? 15,
      reviewRecoveryTaskDoneRuleEnabled: rule('REVIEW_RECOVERY_TASK_DONE')?.enabled ?? true,
      reviewRecoveryTaskDoneRulePoints: rule('REVIEW_RECOVERY_TASK_DONE')?.points ?? 20
    });
  }
}
