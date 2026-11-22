import { computed, signal } from '@angular/core';
import { AbstractControl, FormBuilder, ValidationErrors, Validators } from '@angular/forms';
import { finalize, Observable, tap } from 'rxjs';

import {
  ContractorLegacyRewardManualGroup,
  ContractorLegacyRewardReconciliation,
  ContractorPaymentSystemStatus
} from '../../../core/contractor-payments.api';

import { apiErrorMessage } from '../../../shared/api-error-message';

import { ToastService } from '../../../shared/toast.service';

import { businessDateIso, businessDateTimeStamp } from '../../../shared/business-date';

import {
  CONTRACTOR_SYSTEM_ACTIVATION_CONFIRMATION,
  canActivateContractorSystem as isContractorSystemActivationAllowed,
  canChangeContractorRouting as isContractorRoutingChangeAllowed,
  contractorRoutingConfirmation,
  contractorSystemActivationDate,
  contractorSystemModeLabel
} from './contractor-payment-system-settings';
import { AdminContractorSystemApi } from '../../../core/admin-contractor-system.api';
import { DictionaryViewScope } from './dictionary-view-scope';
type Deps = {
  api: Pick<
    AdminContractorSystemApi,
    | 'getSystemStatus'
    | 'activateSystem'
    | 'updateSystemRouting'
    | 'getLegacyRewardReconciliation'
    | 'prepareLegacyRewardReconciliation'
    | 'applyLegacyRewardReconciliation'
    | 'resolveLegacyRewardManualGroup'
  >;
  toast: Pick<ToastService, 'success' | 'error'>;
  isActive: () => boolean;
  ownerAllowed: () => boolean;
};

/** How a command ended, reported after its view was left. */
type CommandOutcome<T> = { failed: false; value: T | undefined } | { failed: true; error: unknown };

/** The server trims the text and refuses an empty one: spaces alone are not a reason. */
function notBlank(control: AbstractControl): ValidationErrors | null {
  const value = control.value;
  return typeof value === 'string' && value.length > 0 && value.trim().length === 0
    ? { blank: true }
    : null;
}

/** Limits of the server: request validation for activation and routing, column sizes for the reconciliation. */
const COMMAND_REASON_MAX = 500;
const RECONCILIATION_REASON_MAX = 1000;
const EVIDENCE_REFERENCE_MAX = 500;

const RUN_STATUS_LABELS: Record<string, string> = {
  NOT_PREPARED: 'не подготовлен',
  PREPARED: 'подготовлен',
  AUTO_APPLIED: 'автоматическая часть применена'
};

const CATEGORY_LABELS: Record<string, string> = {
  ACTIVE_REVIEW_RECOVERY: 'Идёт восстановление отзывов',
  DELETED_ORDER: 'Заказ удалён',
  COMPLETION_DATE_REQUIRES_EVIDENCE: 'Нужна дата выполнения',
  IDENTITY_OR_SOURCE_AMBIGUOUS: 'Неясен получатель',
  DATED_COMPLETED_ORDER: 'Дата выполнения известна'
};

const GROUP_STATUS_LABELS: Record<string, string> = {
  PENDING: 'Ожидает',
  APPLIED: 'Применено'
};

/** «2026-11-21» → «2026-11-20». */
function previousIsoDate(value: string | null | undefined): string | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec((value ?? '').trim());
  if (!match) return null;
  const date = new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3]) - 1));
  return date.toISOString().slice(0, 10);
}

/** «2026-11-21T15:30» and «2026-11-21 15:30:00.123» → «2026-11-21T15:30:00», comparable as text. */
function wallClock(value: string): string {
  const text = value.trim().replace(' ', 'T').slice(0, 19);
  return text.length === 16 ? `${text}:00` : text;
}

export class AdminContractorSystemFacade {
  private readonly fb = new FormBuilder();
  readonly scope: DictionaryViewScope;
  constructor(private readonly deps: Deps) {
    this.scope = new DictionaryViewScope(deps.isActive);
  }
  private get contractorPaymentsApi() {
    return this.deps.api;
  }
  private get toastService() {
    return this.deps.toast;
  }
  private canControlContractorSystem() {
    return this.deps.ownerAllowed();
  }
  deactivate(): void {
    this.scope.deactivate();
    this.contractorSystemLoadEpoch++;
    this.contractorSystemLoading.set(false);
  }
  destroy(): void {
    this.scope.destroy();
  }

  contractorSystemLoadEpoch = 0;

  readonly contractorSystemStatus = signal<ContractorPaymentSystemStatus | null>(null);

  readonly contractorSystemLoading = signal(false);

  readonly contractorSystemSaving = signal(false);

  readonly contractorSystemError = signal<string | null>(null);

  readonly contractorSystemActivationOpen = signal(false);

  readonly contractorSystemRoutingOpen = signal(false);

  readonly contractorRoutingTargetEnabled = signal(false);

  readonly contractorSystemToday = businessDateIso();

  readonly contractorActivationConfirmation = CONTRACTOR_SYSTEM_ACTIVATION_CONFIRMATION;

  readonly contractorLegacyReconciliation = signal<ContractorLegacyRewardReconciliation | null>(
    null
  );

  readonly contractorLegacyReconciliationSaving = signal(false);

  readonly contractorLegacyManualGroup = signal<ContractorLegacyRewardManualGroup | null>(null);

  readonly contractorLegacyManualOpen = signal(false);

  /** A refusal of the manual confirmation, shown inside its window (the page behind is covered). */
  readonly contractorLegacyManualError = signal<string | null>(null);

  readonly contractorLegacyAutoConfirmation = 'ПРИМЕНИТЬ АВТОСВЕРКУ';

  readonly contractorLegacyManualConfirmation = 'ПОДТВЕРДИТЬ РУЧНУЮ СВЕРКУ';

  readonly canActivateContractorSystem = computed(() =>
    isContractorSystemActivationAllowed(
      this.contractorSystemStatus(),
      this.canControlContractorSystem()
    )
  );

  readonly canChangeContractorRouting = computed(() =>
    isContractorRoutingChangeAllowed(
      this.contractorSystemStatus(),
      this.canControlContractorSystem()
    )
  );

  /**
   * The owner may apply or confirm the reconciliation only before the new system is switched on: afterwards the
   * server refuses both.
   */
  readonly canApplyContractorLegacy = computed(
    () => this.canControlContractorSystem() && !this.contractorSystemStatus()?.systemEnabled
  );

  /** The confirmed completion date must be earlier than the day the snapshot was prepared. */
  readonly contractorLegacyManualMaxDate = computed(() =>
    previousIsoDate(this.contractorLegacyReconciliation()?.startDate)
  );

  readonly contractorSystemActivationForm = this.fb.nonNullable.group({
    attributionStartDate: [
      contractorSystemActivationDate(businessDateIso()),
      [Validators.required]
    ],
    reason: ['', [Validators.required, notBlank, Validators.maxLength(COMMAND_REASON_MAX)]],
    confirmation: ['', [Validators.required]]
  });

  readonly contractorSystemRoutingForm = this.fb.nonNullable.group({
    reason: ['', [Validators.required, notBlank, Validators.maxLength(COMMAND_REASON_MAX)]],
    confirmation: ['', [Validators.required]]
  });

  readonly contractorLegacyAutoForm = this.fb.nonNullable.group({
    reason: ['', [Validators.required, notBlank, Validators.maxLength(RECONCILIATION_REASON_MAX)]],
    confirmation: ['', Validators.required]
  });

  readonly contractorLegacyManualForm = this.fb.nonNullable.group({
    completedOn: ['', Validators.required],
    evidenceReference: [
      '',
      [Validators.required, notBlank, Validators.maxLength(EVIDENCE_REFERENCE_MAX)]
    ],
    reason: ['', [Validators.required, notBlank, Validators.maxLength(RECONCILIATION_REASON_MAX)]],
    confirmation: ['', Validators.required]
  });

  loadContractorPaymentSystemStatus(): void {
    if (!this.scope.active()) return;
    this.loadContractorLegacyReconciliation();
    const requestId = ++this.contractorSystemLoadEpoch;
    this.contractorSystemLoading.set(true);
    this.contractorSystemError.set(null);
    this.scope
      .read(
        'loadContractorPaymentSystemStatus',
        this.contractorPaymentsApi.getSystemStatus(),
        this.contractorSystemLoading
      )
      .subscribe({
        next: (status) => {
          if (requestId !== this.contractorSystemLoadEpoch || !this.scope.active()) {
            return;
          }
          this.contractorSystemStatus.set(status);
          this.contractorSystemLoading.set(false);
        },
        error: (error: unknown) => {
          if (requestId !== this.contractorSystemLoadEpoch || !this.scope.active()) {
            return;
          }
          this.contractorSystemError.set(
            apiErrorMessage(error, 'Не удалось загрузить статус новой системы расчётов.')
          );
          this.contractorSystemLoading.set(false);
        }
      });
  }

  loadContractorLegacyReconciliation(): void {
    if (!this.scope.active()) return;
    this.scope
      .read(
        'loadContractorLegacyReconciliation',
        this.contractorPaymentsApi.getLegacyRewardReconciliation()
      )
      .subscribe({
        next: (snapshot) => this.contractorLegacyReconciliation.set(snapshot),
        error: (error: unknown) =>
          this.contractorSystemError.set(
            apiErrorMessage(error, 'Не удалось загрузить снимок сверки старых начислений.')
          )
      });
  }

  prepareContractorLegacyReconciliation(): void {
    if (!this.scope.active() || this.scope.writing()) return;
    if (
      this.contractorLegacyReconciliationSaving() ||
      this.contractorSystemStatus()?.systemEnabled
    ) {
      return;
    }
    this.contractorLegacyReconciliationSaving.set(true);
    this.contractorSystemError.set(null);
    const command = this.reportWhenViewLeft(
      this.contractorPaymentsApi.prepareLegacyRewardReconciliation(),
      (outcome) => {
        if (outcome.failed) {
          this.reportLegacyReconciliationError(outcome.error, 'Не удалось подготовить проверку.');
          return;
        }
        if (outcome.value) this.contractorLegacyReconciliation.set(outcome.value);
        this.toastService.success('Проверка подготовлена', 'Начисления не изменялись.');
      }
    );
    this.scope
      .write(command.request, this.contractorLegacyReconciliationSaving)
      .subscribe({
        next: (snapshot) => {
          command.received();
          this.contractorLegacyReconciliation.set(snapshot);
          this.contractorLegacyReconciliationSaving.set(false);
          this.contractorLegacyAutoForm.reset({ reason: '', confirmation: '' });
          this.toastService.success('Проверка подготовлена', 'Начисления не изменялись.');
        },
        error: (error: unknown) => {
          command.received();
          this.finishLegacyReconciliationError(error, 'Не удалось подготовить проверку.');
        }
      });
  }

  /** The AUTO form is complete: a reason that is not only spaces and the phrase typed exactly. */
  contractorLegacyAutoReady(): boolean {
    return (
      this.contractorLegacyAutoForm.valid &&
      this.contractorLegacyAutoForm.controls.confirmation.value.trim() ===
        this.contractorLegacyAutoConfirmation
    );
  }

  applyContractorLegacyAutomatic(): void {
    if (!this.scope.active() || this.scope.writing()) return;
    const snapshot = this.contractorLegacyReconciliation();
    const raw = this.contractorLegacyAutoForm.getRawValue();
    if (
      !this.canApplyContractorLegacy() ||
      !snapshot?.runId ||
      !snapshot.snapshotHash ||
      !this.contractorLegacyAutoReady() ||
      this.contractorLegacyReconciliationSaving()
    ) {
      this.contractorLegacyAutoForm.markAllAsTouched();
      return;
    }
    this.contractorLegacyReconciliationSaving.set(true);
    const command = this.reportWhenViewLeft(
      this.contractorPaymentsApi.applyLegacyRewardReconciliation(snapshot.runId, {
        snapshotHash: snapshot.snapshotHash,
        reason: raw.reason.trim(),
        confirmation: raw.confirmation.trim()
      }),
      (outcome) => {
        if (outcome.failed) {
          this.reportLegacyReconciliationError(outcome.error, 'Автоматическая сверка не применена.');
          return;
        }
        if (outcome.value) this.contractorLegacyReconciliation.set(outcome.value);
        this.contractorLegacyAutoForm.reset({ reason: '', confirmation: '' });
        this.toastService.success(
          'Автоматическая сверка применена',
          'Неоднозначные группы оставлены для ручной проверки.'
        );
        this.loadContractorPaymentSystemStatus();
      }
    );
    this.scope
      .write(command.request, this.contractorLegacyReconciliationSaving)
      .subscribe({
        next: (updated) => {
          command.received();
          this.contractorLegacyReconciliation.set(updated);
          this.contractorLegacyReconciliationSaving.set(false);
          this.contractorLegacyAutoForm.reset({ reason: '', confirmation: '' });
          this.toastService.success(
            'Автоматическая сверка применена',
            'Неоднозначные группы оставлены для ручной проверки.'
          );
          // Readiness for activation depends on the reconciliation: the switch and its blockers are read again.
          this.loadContractorPaymentSystemStatus();
        },
        error: (error: unknown) => {
          command.received();
          this.finishLegacyReconciliationError(error, 'Автоматическая сверка не применена.');
        }
      });
  }

  openContractorLegacyManual(group: ContractorLegacyRewardManualGroup): void {
    if (!this.canApplyContractorLegacy() || group.status !== 'PENDING') {
      return;
    }
    this.contractorLegacyManualError.set(null);
    this.contractorLegacyManualGroup.set(group);
    this.contractorLegacyManualForm.reset({
      completedOn: '',
      evidenceReference: '',
      reason: '',
      confirmation: ''
    });
    this.contractorLegacyManualOpen.set(true);
  }

  closeContractorLegacyManual(): void {
    if (!this.contractorLegacyReconciliationSaving()) {
      this.contractorLegacyManualOpen.set(false);
      this.contractorLegacyManualGroup.set(null);
      this.contractorLegacyManualError.set(null);
    }
  }

  /**
   * The manual form is complete: a date earlier than the day of the snapshot, an evidence and a reason that are not
   * only spaces, and the phrase typed exactly.
   */
  contractorLegacyManualReady(): boolean {
    const raw = this.contractorLegacyManualForm.getRawValue();
    const maxDate = this.contractorLegacyManualMaxDate();
    return (
      this.contractorLegacyManualForm.valid &&
      raw.confirmation.trim() === this.contractorLegacyManualConfirmation &&
      (!maxDate || raw.completedOn <= maxDate)
    );
  }

  resolveContractorLegacyManual(): void {
    if (!this.scope.active() || this.scope.writing()) return;
    const snapshot = this.contractorLegacyReconciliation();
    const group = this.contractorLegacyManualGroup();
    const raw = this.contractorLegacyManualForm.getRawValue();
    if (
      !this.canApplyContractorLegacy() ||
      !snapshot?.runId ||
      !snapshot.snapshotHash ||
      !group ||
      !this.contractorLegacyManualReady() ||
      this.contractorLegacyReconciliationSaving()
    ) {
      this.contractorLegacyManualForm.markAllAsTouched();
      return;
    }
    this.contractorLegacyReconciliationSaving.set(true);
    this.contractorLegacyManualError.set(null);
    const command = this.reportWhenViewLeft(
      this.contractorPaymentsApi.resolveLegacyRewardManualGroup(snapshot.runId, group.orderId, {
        snapshotHash: snapshot.snapshotHash,
        groupHash: group.groupHash,
        completedOn: raw.completedOn,
        evidenceReference: raw.evidenceReference.trim(),
        reason: raw.reason.trim(),
        confirmation: raw.confirmation.trim()
      }),
      (outcome) => {
        if (outcome.failed) {
          const message = apiErrorMessage(outcome.error, 'Ручная сверка не применена.');
          this.contractorLegacyManualError.set(message);
          this.toastService.error('Сверка старых начислений', message);
          return;
        }
        if (outcome.value) this.contractorLegacyReconciliation.set(outcome.value);
        this.contractorLegacyManualOpen.set(false);
        this.contractorLegacyManualGroup.set(null);
        this.toastService.success(
          'Ручная сверка подтверждена',
          'Доказательство и снимок записаны в журнал действий.'
        );
        this.loadContractorPaymentSystemStatus();
      }
    );
    this.scope
      .write(command.request, this.contractorLegacyReconciliationSaving)
      .subscribe({
        next: (updated) => {
          command.received();
          this.contractorLegacyReconciliation.set(updated);
          this.contractorLegacyReconciliationSaving.set(false);
          this.contractorLegacyManualOpen.set(false);
          this.contractorLegacyManualGroup.set(null);
          this.toastService.success(
            'Ручная сверка подтверждена',
            'Доказательство и снимок записаны в журнал действий.'
          );
          // Readiness for activation depends on the confirmed groups: the switch and its blockers are read again.
          this.loadContractorPaymentSystemStatus();
        },
        error: (error: unknown) => {
          command.received();
          const message = apiErrorMessage(error, 'Ручная сверка не применена.');
          // The window covers the page: the refusal is shown inside it.
          this.contractorLegacyManualError.set(message);
          this.contractorLegacyReconciliationSaving.set(false);
          this.toastService.error('Сверка старых начислений', message);
        }
      });
  }

  finishLegacyReconciliationError(error: unknown, fallback: string): void {
    this.reportLegacyReconciliationError(error, fallback);
    this.contractorLegacyReconciliationSaving.set(false);
  }

  private reportLegacyReconciliationError(error: unknown, fallback: string): void {
    const message = apiErrorMessage(error, fallback);
    this.contractorSystemError.set(message);
    this.toastService.error('Сверка старых начислений', message);
  }

  /**
   * A hint by the clock of this computer: the snapshot lives 30 minutes and only on the day it was prepared. The
   * server decides by the database clock, so nothing is blocked by this.
   */
  contractorLegacySnapshotStale(): boolean {
    const run = this.contractorLegacyReconciliation();
    if (!run?.runId) return false;
    // A finished reconciliation has nothing left to apply: no reason to prepare a new snapshot.
    if ((run.autoRemainingRows ?? 0) === 0 && (run.manualRemainingOrders ?? 0) === 0) return false;
    if (run.startDate && run.startDate !== businessDateIso()) return true;
    return !!run.expiresAt && wallClock(run.expiresAt) <= wallClock(businessDateTimeStamp());
  }

  contractorLegacyRunStatusLabel(status: string | null | undefined): string {
    return RUN_STATUS_LABELS[status ?? ''] ?? (status || '—');
  }

  contractorLegacyCategoryLabel(category: string | null | undefined): string {
    return CATEGORY_LABELS[category ?? ''] ?? (category || '—');
  }

  contractorLegacyGroupStatusLabel(status: string | null | undefined): string {
    return GROUP_STATUS_LABELS[status ?? ''] ?? (status || '—');
  }

  requestContractorSystemActivation(event: Event): void {
    event.preventDefault();
    if (!this.canActivateContractorSystem() || this.contractorSystemSaving()) {
      return;
    }
    this.contractorSystemError.set(null);
    this.contractorSystemActivationForm.reset({
      attributionStartDate: contractorSystemActivationDate(businessDateIso()),
      reason: '',
      confirmation: ''
    });
    this.contractorSystemActivationOpen.set(true);
  }

  closeContractorSystemActivation(): void {
    if (!this.contractorSystemSaving()) {
      this.contractorSystemActivationOpen.set(false);
    }
  }

  contractorSystemActivationReady(): boolean {
    return (
      this.contractorSystemActivationForm.valid &&
      this.contractorSystemActivationForm.controls.confirmation.value.trim() ===
        CONTRACTOR_SYSTEM_ACTIVATION_CONFIRMATION
    );
  }

  activateContractorSystem(): void {
    if (!this.scope.active() || this.scope.writing()) return;
    const status = this.contractorSystemStatus();
    if (
      !status ||
      !this.canActivateContractorSystem() ||
      !this.contractorSystemActivationReady() ||
      this.contractorSystemSaving()
    ) {
      this.contractorSystemActivationForm.markAllAsTouched();
      return;
    }

    const raw = this.contractorSystemActivationForm.getRawValue();
    this.contractorSystemSaving.set(true);
    this.contractorSystemError.set(null);
    const fallback = 'Активация не выполнена. Обновите статус и проверьте причины блокировки.';
    const command = this.reportWhenViewLeft(
      this.contractorPaymentsApi.activateSystem({
        attributionStartDate: raw.attributionStartDate,
        confirmation: raw.confirmation.trim(),
        reason: raw.reason.trim(),
        expectedRevision: status.revision
      }),
      (outcome) => {
        // The page was left while activating: the window must not stay open over a stale «выключена».
        if (outcome.failed) {
          const message = apiErrorMessage(outcome.error, fallback);
          this.contractorSystemError.set(message);
          this.toastService.error('Новая система не активирована', message);
          return;
        }
        if (outcome.value) this.contractorSystemStatus.set(outcome.value);
        this.contractorSystemActivationOpen.set(false);
        this.toastService.success(
          'Новая система расчётов активирована',
          'Возврат к старому учёту невозможен.'
        );
      }
    );
    this.scope
      .write(command.request, this.contractorSystemSaving)
      .subscribe({
        next: (updated) => {
          command.received();
          this.contractorSystemStatus.set(updated);
          this.contractorSystemSaving.set(false);
          this.contractorSystemActivationOpen.set(false);
          this.toastService.success(
            'Новая система расчётов активирована',
            'Возврат к старому учёту невозможен.'
          );
        },
        error: (error: unknown) => {
          command.received();
          const message = apiErrorMessage(error, fallback);
          this.contractorSystemError.set(message);
          this.contractorSystemSaving.set(false);
          this.toastService.error('Новая система не активирована', message);
        }
      });
  }

  openContractorRoutingChange(enabled: boolean): void {
    if (!this.canChangeContractorRouting() || this.contractorSystemSaving()) {
      return;
    }
    this.contractorSystemError.set(null);
    this.contractorRoutingTargetEnabled.set(enabled);
    this.contractorSystemRoutingForm.reset({ reason: '', confirmation: '' });
    this.contractorSystemRoutingOpen.set(true);
  }

  closeContractorRoutingChange(): void {
    if (!this.contractorSystemSaving()) {
      this.contractorSystemRoutingOpen.set(false);
    }
  }

  contractorRoutingConfirmationPrompt(): string {
    return contractorRoutingConfirmation(this.contractorRoutingTargetEnabled());
  }

  contractorRoutingChangeReady(): boolean {
    return (
      this.contractorSystemRoutingForm.valid &&
      this.contractorSystemRoutingForm.controls.confirmation.value.trim() ===
        this.contractorRoutingConfirmationPrompt()
    );
  }

  updateContractorRouting(): void {
    if (!this.scope.active() || this.scope.writing()) return;
    const status = this.contractorSystemStatus();
    if (
      !status ||
      !this.canChangeContractorRouting() ||
      !this.contractorRoutingChangeReady() ||
      this.contractorSystemSaving()
    ) {
      this.contractorSystemRoutingForm.markAllAsTouched();
      return;
    }

    const enabled = this.contractorRoutingTargetEnabled();
    const raw = this.contractorSystemRoutingForm.getRawValue();
    this.contractorSystemSaving.set(true);
    this.contractorSystemError.set(null);
    const fallback = 'Не удалось изменить режим выдачи реквизитов. Обновите статус и повторите.';
    const succeeded = (updated: ContractorPaymentSystemStatus) => {
      this.contractorSystemStatus.set(updated);
      this.contractorSystemRoutingOpen.set(false);
      this.toastService.success(
        enabled ? 'Выдача реквизитов запрошена' : 'Выдача реквизитов приостановлена',
        enabled && !updated.liveRoutingEffective
          ? 'Запрос сохранён, но боевые предохранители пока не дают выдавать реквизиты.'
          : 'Новые счета будут обрабатываться по обновлённому режиму.'
      );
    };
    const command = this.reportWhenViewLeft(
      this.contractorPaymentsApi.updateSystemRouting({
        enabled,
        confirmation: raw.confirmation.trim(),
        reason: raw.reason.trim(),
        expectedRevision: status.revision
      }),
      (outcome) => {
        if (outcome.failed) {
          const message = apiErrorMessage(outcome.error, fallback);
          this.contractorSystemError.set(message);
          this.toastService.error('Режим реквизитов не изменён', message);
          return;
        }
        if (outcome.value) succeeded(outcome.value);
        else this.contractorSystemRoutingOpen.set(false);
      }
    );
    this.scope
      .write(command.request, this.contractorSystemSaving)
      .subscribe({
        next: (updated) => {
          command.received();
          this.contractorSystemSaving.set(false);
          succeeded(updated);
        },
        error: (error: unknown) => {
          command.received();
          const message = apiErrorMessage(error, fallback);
          this.contractorSystemError.set(message);
          this.contractorSystemSaving.set(false);
          this.toastService.error('Режим реквизитов не изменён', message);
        }
      });
  }

  contractorPaymentSystemModeLabel(): string {
    const status = this.contractorSystemStatus();
    return status ? contractorSystemModeLabel(status.mode) : '—';
  }

  /**
   * DictionaryViewScope suppresses the callbacks of a command whose view was left (another tab, a hidden browser
   * tab); the command itself still runs to its end. `late` receives that end when no callback did: every callback
   * that runs calls `received` first.
   */
  private reportWhenViewLeft<T>(
    request: Observable<T>,
    late: (outcome: CommandOutcome<T>) => void
  ): { request: Observable<T>; received: () => void } {
    let received = false;
    let last: T | undefined;
    let outcome: CommandOutcome<T> | null = null;
    return {
      request: request.pipe(
        tap({
          next: (value) => {
            last = value;
          },
          error: (error: unknown) => {
            outcome = { failed: true, error };
          },
          complete: () => {
            outcome = { failed: false, value: last };
          }
        }),
        finalize(() => {
          if (!received && outcome) late(outcome);
        })
      ),
      received: () => {
        received = true;
      }
    };
  }
}
