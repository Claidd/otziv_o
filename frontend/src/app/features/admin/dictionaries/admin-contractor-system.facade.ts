import { computed, signal } from '@angular/core';
import { FormBuilder, Validators } from '@angular/forms';

import {
  ContractorLegacyRewardManualGroup,
  ContractorLegacyRewardReconciliation,
  ContractorPaymentSystemStatus
} from '../../../core/contractor-payments.api';

import { apiErrorMessage } from '../../../shared/api-error-message';

import { ToastService } from '../../../shared/toast.service';

import { businessDateIso } from '../../../shared/business-date';

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

  readonly contractorSystemActivationForm = this.fb.nonNullable.group({
    attributionStartDate: [
      contractorSystemActivationDate(businessDateIso()),
      [Validators.required]
    ],
    reason: ['', [Validators.required]],
    confirmation: ['', [Validators.required]]
  });

  readonly contractorSystemRoutingForm = this.fb.nonNullable.group({
    reason: ['', [Validators.required]],
    confirmation: ['', [Validators.required]]
  });

  readonly contractorLegacyAutoForm = this.fb.nonNullable.group({
    reason: ['', Validators.required],
    confirmation: ['', Validators.required]
  });

  readonly contractorLegacyManualForm = this.fb.nonNullable.group({
    completedOn: ['', Validators.required],
    evidenceReference: ['', Validators.required],
    reason: ['', Validators.required],
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
    this.scope
      .write(
        this.contractorPaymentsApi.prepareLegacyRewardReconciliation(),
        this.contractorLegacyReconciliationSaving
      )
      .subscribe({
        next: (snapshot) => {
          this.contractorLegacyReconciliation.set(snapshot);
          this.contractorLegacyReconciliationSaving.set(false);
          this.contractorLegacyAutoForm.reset({ reason: '', confirmation: '' });
          this.toastService.success('Dry-run сверки подготовлен', 'Начисления не изменялись.');
        },
        error: (error: unknown) =>
          this.finishLegacyReconciliationError(error, 'Не удалось подготовить dry-run сверки.')
      });
  }

  applyContractorLegacyAutomatic(): void {
    if (!this.scope.active() || this.scope.writing()) return;
    const snapshot = this.contractorLegacyReconciliation();
    const raw = this.contractorLegacyAutoForm.getRawValue();
    if (
      !this.canControlContractorSystem() ||
      !snapshot?.runId ||
      !snapshot.snapshotHash ||
      this.contractorLegacyAutoForm.invalid ||
      raw.confirmation.trim() !== this.contractorLegacyAutoConfirmation ||
      this.contractorLegacyReconciliationSaving()
    ) {
      this.contractorLegacyAutoForm.markAllAsTouched();
      return;
    }
    this.contractorLegacyReconciliationSaving.set(true);
    this.scope
      .write(
        this.contractorPaymentsApi.applyLegacyRewardReconciliation(snapshot.runId, {
          snapshotHash: snapshot.snapshotHash,
          reason: raw.reason.trim(),
          confirmation: raw.confirmation.trim()
        }),
        this.contractorLegacyReconciliationSaving
      )
      .subscribe({
        next: (updated) => {
          this.contractorLegacyReconciliation.set(updated);
          this.contractorLegacyReconciliationSaving.set(false);
          this.contractorLegacyAutoForm.reset({ reason: '', confirmation: '' });
          this.toastService.success(
            'Автоматическая сверка применена',
            'Неоднозначные группы оставлены для ручной проверки.'
          );
        },
        error: (error: unknown) =>
          this.finishLegacyReconciliationError(error, 'Автоматическая сверка не применена.')
      });
  }

  openContractorLegacyManual(group: ContractorLegacyRewardManualGroup): void {
    if (!this.canControlContractorSystem() || group.status !== 'PENDING') {
      return;
    }
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
    }
  }

  resolveContractorLegacyManual(): void {
    if (!this.scope.active() || this.scope.writing()) return;
    const snapshot = this.contractorLegacyReconciliation();
    const group = this.contractorLegacyManualGroup();
    const raw = this.contractorLegacyManualForm.getRawValue();
    if (
      !this.canControlContractorSystem() ||
      !snapshot?.runId ||
      !snapshot.snapshotHash ||
      !group ||
      this.contractorLegacyManualForm.invalid ||
      raw.confirmation.trim() !== this.contractorLegacyManualConfirmation ||
      this.contractorLegacyReconciliationSaving()
    ) {
      this.contractorLegacyManualForm.markAllAsTouched();
      return;
    }
    this.contractorLegacyReconciliationSaving.set(true);
    this.scope
      .write(
        this.contractorPaymentsApi.resolveLegacyRewardManualGroup(snapshot.runId, group.orderId, {
          snapshotHash: snapshot.snapshotHash,
          groupHash: group.groupHash,
          completedOn: raw.completedOn,
          evidenceReference: raw.evidenceReference.trim(),
          reason: raw.reason.trim(),
          confirmation: raw.confirmation.trim()
        }),
        this.contractorLegacyReconciliationSaving
      )
      .subscribe({
        next: (updated) => {
          this.contractorLegacyReconciliation.set(updated);
          this.contractorLegacyReconciliationSaving.set(false);
          this.contractorLegacyManualOpen.set(false);
          this.contractorLegacyManualGroup.set(null);
          this.toastService.success(
            'Ручная сверка подтверждена',
            'Evidence и точный снимок сохранены в аудите.'
          );
        },
        error: (error: unknown) =>
          this.finishLegacyReconciliationError(error, 'Ручная сверка не применена.')
      });
  }

  finishLegacyReconciliationError(error: unknown, fallback: string): void {
    const message = apiErrorMessage(error, fallback);
    this.contractorSystemError.set(message);
    this.contractorLegacyReconciliationSaving.set(false);
    this.toastService.error('Сверка старых начислений', message);
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
    this.scope
      .write(
        this.contractorPaymentsApi.activateSystem({
          attributionStartDate: raw.attributionStartDate,
          confirmation: raw.confirmation.trim(),
          reason: raw.reason.trim(),
          expectedRevision: status.revision
        }),
        this.contractorSystemSaving
      )
      .subscribe({
        next: (updated) => {
          this.contractorSystemStatus.set(updated);
          this.contractorSystemSaving.set(false);
          this.contractorSystemActivationOpen.set(false);
          this.toastService.success(
            'Новая система расчётов активирована',
            'Возврат к старому учёту невозможен.'
          );
        },
        error: (error: unknown) => {
          const message = apiErrorMessage(
            error,
            'Активация не выполнена. Обновите статус и проверьте причины блокировки.'
          );
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
    this.scope
      .write(
        this.contractorPaymentsApi.updateSystemRouting({
          enabled,
          confirmation: raw.confirmation.trim(),
          reason: raw.reason.trim(),
          expectedRevision: status.revision
        }),
        this.contractorSystemSaving
      )
      .subscribe({
        next: (updated) => {
          this.contractorSystemStatus.set(updated);
          this.contractorSystemSaving.set(false);
          this.contractorSystemRoutingOpen.set(false);
          this.toastService.success(
            enabled ? 'Выдача реквизитов запрошена' : 'Выдача реквизитов приостановлена',
            enabled && !updated.liveRoutingEffective
              ? 'Запрос сохранён, но боевые предохранители пока не дают выдавать реквизиты.'
              : 'Новые счета будут обрабатываться по обновлённому режиму.'
          );
        },
        error: (error: unknown) => {
          const message = apiErrorMessage(
            error,
            'Не удалось изменить режим выдачи реквизитов. Обновите статус и повторите.'
          );
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
}
