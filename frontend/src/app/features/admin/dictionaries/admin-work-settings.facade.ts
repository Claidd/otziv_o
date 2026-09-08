import { signal, WritableSignal } from '@angular/core';
import { FormBuilder, Validators } from '@angular/forms';
import { concat, forkJoin, Observable, of, tap } from 'rxjs';
import {
  AdminClientPublicationProgressReportSettings,
  AdminNagulSettings,
  AdminSharedChatLinkSyncResponse,
  AdminTelegramReportScheduleSettings,
  AdminWhatsAppGroupSyncSettings,
  AdminWorkerAccountActionSettings,
  AdminWorkerCellularAccessSettings,
  ClientPublicationProgressReportSettingsRequest,
  NagulSettingsRequest,
  TelegramReportScheduleSettingsRequest,
  WhatsAppGroupSyncSettingsRequest,
  WorkerCellularAccessMode,
  WorkerCellularAccessSettingsRequest
} from '../../../core/admin-dictionaries.api';
import { apiErrorMessage } from '../../../shared/api-error-message';
import { ToastService } from '../../../shared/toast.service';
import {
  requiresWorkerCellularEnforceConfirmation,
  workerCellularAccessReasons
} from './worker-cellular-access-settings';
import { DictionaryViewScope } from './dictionary-view-scope';
import { AdminWorkSettingsApi } from '../../../core/admin-work-settings.api';
type Deps = {
  api: AdminWorkSettingsApi;
  toast: Pick<ToastService, 'success' | 'error'>;
  isActive: () => boolean;
  selectedId: WritableSignal<number | null>;
  search: () => string;
  canApplyMaintenance: () => boolean;
};

export class AdminWorkSettingsFacade {
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
  private canApplyMaintenance() {
    return this.deps.canApplyMaintenance();
  }
  load(): void {
    if (!this.scope.active()) return;
    this.error.set(null);
    this.scope
      .read(
        'settings',
        forkJoin({
          nagulSettings: this.dictionariesApi.getNagulSettings(),
          workerAccountActionSettings: this.dictionariesApi.getWorkerAccountActionSettings(),
          telegramReportSettings: this.dictionariesApi.getTelegramReportSettings(),
          whatsAppGroupSyncSettings: this.dictionariesApi.getWhatsAppGroupSyncSettings(),
          clientPublicationProgressReportSettings:
            this.dictionariesApi.getClientPublicationProgressReportSettings(),
          workerCellularAccessSettings: this.canApplyMaintenance()
            ? this.dictionariesApi.getWorkerCellularAccessSettings()
            : of(null)
        }),
        this.loading
      )
      .subscribe({
        next: (response) => {
          this.applyNagulSettings(response.nagulSettings);
          this.applyWorkerAccountActionSettings(response.workerAccountActionSettings);
          this.applyTelegramReportSettings(response.telegramReportSettings);
          this.applyWhatsAppGroupSyncSettings(response.whatsAppGroupSyncSettings);
          this.applyClientPublicationProgressReportSettings(
            response.clientPublicationProgressReportSettings
          );
          if (response.workerCellularAccessSettings)
            this.applyWorkerCellularAccessSettings(response.workerCellularAccessSettings);
        },
        error: (error) => this.failLoad(error)
      });
  }

  readonly nagulSettings = signal<AdminNagulSettings | null>(null);

  readonly workerAccountActionSettings = signal<AdminWorkerAccountActionSettings | null>(null);

  readonly telegramReportSettings = signal<AdminTelegramReportScheduleSettings | null>(null);

  readonly whatsAppGroupSyncSettings = signal<AdminWhatsAppGroupSyncSettings | null>(null);

  readonly clientPublicationProgressReportSettings =
    signal<AdminClientPublicationProgressReportSettings | null>(null);

  readonly workerCellularAccessSettings = signal<AdminWorkerCellularAccessSettings | null>(null);

  readonly settingsForm = this.fb.nonNullable.group({
    workerAccountActionCooldownEnabled: [true],
    workerAccountActionCooldownSeconds: [
      60,
      [Validators.required, Validators.min(0), Validators.max(3600), Validators.pattern(/^\d+$/)]
    ],
    nagulCooldownMinutes: [60, [Validators.required, Validators.min(0), Validators.max(1440)]],
    nagulLookaheadDays: [60, [Validators.required, Validators.min(0), Validators.max(365)]],
    accountWalkedCounterThreshold: [
      3,
      [Validators.required, Validators.min(1), Validators.max(30)]
    ],
    accountWalkDelayDays: [2, [Validators.required, Validators.min(0), Validators.max(30)]],
    morningReportEnabled: [true],
    morningReportTime: [
      '11:30',
      [Validators.required, Validators.pattern(/^([01]\d|2[0-3]):[0-5]\d$/)]
    ],
    eveningReportEnabled: [true],
    eveningReportTime: [
      '22:00',
      [Validators.required, Validators.pattern(/^([01]\d|2[0-3]):[0-5]\d$/)]
    ],
    telegramReportZone: ['Asia/Irkutsk', Validators.required],
    whatsAppGroupSyncEnabled: [true],
    whatsAppGroupSyncIntervalMinutes: [
      30,
      [Validators.required, Validators.min(5), Validators.max(1440)]
    ],
    clientPublicationProgressReportsEnabled: [true],
    workerCellularAccessMode: ['ENFORCE' as WorkerCellularAccessMode, Validators.required],
    blockNonCellularNetwork: [true],
    blockVpnProxyOrDatacenter: [true],
    blockDesktopOrUnknownDevice: [false],
    blockUnknownNetwork: [false],
    enforceNativeVirtualDevice: [true]
  });

  readonly syncingWhatsAppGroups = signal(false);

  readonly syncingSharedChatLinks = signal(false);

  settingsTotal(): number {
    return (
      (this.nagulSettings() ? 1 : 0) +
      (this.workerAccountActionSettings() ? 1 : 0) +
      (this.telegramReportSettings() ? 1 : 0) +
      (this.whatsAppGroupSyncSettings() ? 1 : 0) +
      (this.clientPublicationProgressReportSettings() ? 1 : 0)
    );
  }

  saveSettings(): void {
    if (!this.scope.active() || this.scope.writing() || this.saving() || this.deleting()) return;

    if (this.settingsForm.invalid) {
      this.settingsForm.markAllAsTouched();
      return;
    }

    const raw = this.settingsForm.getRawValue();
    const workerAccountActionRequest: AdminWorkerAccountActionSettings = {
      enabled: raw.workerAccountActionCooldownEnabled,
      cooldownSeconds: raw.workerAccountActionCooldownSeconds
    };
    const nagulRequest: NagulSettingsRequest = {
      cooldownMinutes: Number(raw.nagulCooldownMinutes ?? 0),
      lookaheadDays: Number(raw.nagulLookaheadDays ?? 60),
      accountWalkedCounterThreshold: Number(raw.accountWalkedCounterThreshold ?? 3),
      accountWalkDelayDays: Number(raw.accountWalkDelayDays ?? 2)
    };
    const telegramRequest: TelegramReportScheduleSettingsRequest = {
      morningEnabled: raw.morningReportEnabled,
      morningTime: raw.morningReportTime,
      eveningEnabled: raw.eveningReportEnabled,
      eveningTime: raw.eveningReportTime,
      zone: raw.telegramReportZone.trim()
    };
    const whatsAppRequest: WhatsAppGroupSyncSettingsRequest = {
      enabled: raw.whatsAppGroupSyncEnabled,
      intervalMinutes: Number(raw.whatsAppGroupSyncIntervalMinutes ?? 30)
    };
    const clientPublicationProgressReportRequest: ClientPublicationProgressReportSettingsRequest = {
      enabled: raw.clientPublicationProgressReportsEnabled
    };
    const workerCellularAccessRequest: WorkerCellularAccessSettingsRequest = {
      mode: raw.workerCellularAccessMode,
      enforcedReasons: workerCellularAccessReasons(raw),
      enforceNativeVirtualDevice: raw.enforceNativeVirtualDevice
    };

    this.saving.set(true);
    if (
      this.canApplyMaintenance() &&
      requiresWorkerCellularEnforceConfirmation(
        this.workerCellularAccessSettings()?.mode,
        workerCellularAccessRequest.mode
      ) &&
      !window.confirm(
        'Включить боевой режим? Специалисты через домашнюю сеть/Wi-Fi или VPN получат отказ в защищённых подразделах.'
      )
    ) {
      this.saving.set(false);
      return;
    }

    this.saving.set(true);
    this.error.set(null);

    const requests: Observable<unknown>[] = [
      this.dictionariesApi.updateWorkerAccountActionSettings(workerAccountActionRequest),
      this.dictionariesApi.updateNagulSettings(nagulRequest),
      this.dictionariesApi.updateTelegramReportSettings(telegramRequest),
      this.dictionariesApi.updateWhatsAppGroupSyncSettings(whatsAppRequest),
      this.dictionariesApi.updateClientPublicationProgressReportSettings(
        clientPublicationProgressReportRequest
      )
    ];
    if (this.canApplyMaintenance()) {
      requests.push(
        this.dictionariesApi.updateWorkerCellularAccessSettings(workerCellularAccessRequest)
      );
    }

    let completed = 0;
    this.scope
      .write(
        concat(...requests).pipe(
          tap(() => {
            completed += 1;
          })
        ),
        this.saving
      )
      .subscribe({
        complete: () => {
          this.saving.set(false);
          this.load();
          this.toastService.success(
            'Настройки сохранены',
            'Все разделы применены; состояние перечитано с сервера.'
          );
        },
        error: (err) => {
          const message = this.errorMessage(err, 'Не удалось сохранить настройки');
          this.error.set(message);
          this.saving.set(false);
          this.load();
          this.toastService.error(
            completed > 0 ? 'Настройки сохранены частично' : 'Настройки не сохранены',
            completed > 0
              ? `Применено разделов: ${completed} из ${requests.length}. Состояние перечитано с сервера. ${message}`
              : message
          );
        }
      });
  }

  runWhatsAppGroupSync(): void {
    if (!this.scope.active() || this.scope.writing() || this.saving() || this.deleting()) return;

    if (this.syncingWhatsAppGroups() || this.saving()) {
      return;
    }

    this.syncingWhatsAppGroups.set(true);
    this.error.set(null);

    this.scope
      .write(this.dictionariesApi.runWhatsAppGroupSync(), this.syncingWhatsAppGroups)
      .subscribe({
        next: (settings) => {
          this.syncingWhatsAppGroups.set(false);
          this.applyWhatsAppGroupSyncSettings(settings);
          this.toastService.success(
            'WhatsApp-группы проверены',
            `Новых привязок: ${settings.lastLinkedCount}`
          );
        },
        error: (err: unknown) => {
          const message = this.errorMessage(
            err,
            'Не удалось запустить синхронизацию WhatsApp-групп'
          );
          this.error.set(message);
          this.syncingWhatsAppGroups.set(false);
          this.toastService.error('WhatsApp не синхронизирован', message);
        }
      });
  }

  runSharedChatLinkSync(): void {
    if (!this.scope.active() || this.scope.writing() || this.saving() || this.deleting()) return;

    if (this.syncingSharedChatLinks() || this.saving()) {
      return;
    }

    this.syncingSharedChatLinks.set(true);
    this.error.set(null);

    this.scope
      .write(this.dictionariesApi.runSharedChatLinkSync(), this.syncingSharedChatLinks)
      .subscribe({
        next: (response) => {
          this.syncingSharedChatLinks.set(false);
          this.toastService.success(
            'Общие чаты синхронизированы',
            this.sharedChatSyncSummary(response)
          );
        },
        error: (err: unknown) => {
          const message = this.errorMessage(err, 'Не удалось синхронизировать общие чаты');
          this.error.set(message);
          this.syncingSharedChatLinks.set(false);
          this.toastService.error('Общие чаты не синхронизированы', message);
        }
      });
  }

  applyNagulSettings(response: AdminNagulSettings): void {
    this.nagulSettings.set(response);
    this.settingsForm.patchValue({
      nagulCooldownMinutes: response.cooldownMinutes,
      nagulLookaheadDays: response.lookaheadDays,
      accountWalkedCounterThreshold: response.accountWalkedCounterThreshold,
      accountWalkDelayDays: response.accountWalkDelayDays
    });
  }

  applyWorkerAccountActionSettings(response: AdminWorkerAccountActionSettings): void {
    this.workerAccountActionSettings.set(response);
    this.settingsForm.patchValue({
      workerAccountActionCooldownEnabled: response.enabled,
      workerAccountActionCooldownSeconds: response.cooldownSeconds
    });
  }

  applyTelegramReportSettings(response: AdminTelegramReportScheduleSettings): void {
    this.telegramReportSettings.set(response);
    this.settingsForm.patchValue({
      morningReportEnabled: response.morningEnabled,
      morningReportTime: response.morningTime,
      eveningReportEnabled: response.eveningEnabled,
      eveningReportTime: response.eveningTime,
      telegramReportZone: response.zone
    });
  }

  applyWhatsAppGroupSyncSettings(response: AdminWhatsAppGroupSyncSettings): void {
    this.whatsAppGroupSyncSettings.set(response);
    this.settingsForm.patchValue({
      whatsAppGroupSyncEnabled: response.enabled,
      whatsAppGroupSyncIntervalMinutes: response.intervalMinutes
    });
  }

  applyClientPublicationProgressReportSettings(
    response: AdminClientPublicationProgressReportSettings
  ): void {
    this.clientPublicationProgressReportSettings.set(response);
    this.settingsForm.patchValue({
      clientPublicationProgressReportsEnabled: response.enabled
    });
  }

  applyWorkerCellularAccessSettings(response: AdminWorkerCellularAccessSettings): void {
    this.workerCellularAccessSettings.set(response);
    const reasons = new Set(response.enforcedReasons);
    this.settingsForm.patchValue({
      workerCellularAccessMode: response.mode,
      blockNonCellularNetwork: reasons.has('NON_CELLULAR_NETWORK'),
      blockVpnProxyOrDatacenter: reasons.has('VPN_PROXY_OR_DATACENTER'),
      blockDesktopOrUnknownDevice: reasons.has('DESKTOP_OR_UNKNOWN_DEVICE'),
      blockUnknownNetwork: reasons.has('UNKNOWN_NETWORK'),
      enforceNativeVirtualDevice: response.enforceNativeVirtualDevice
    });
  }

  workerCellularAccessModeLabel(mode: WorkerCellularAccessMode): string {
    switch (mode) {
      case 'OFF':
        return 'выключено';
      case 'AUDIT':
        return 'аудит без блокировки';
      case 'ENFORCE':
        return 'боевой режим';
    }
  }

  workerProtectedSectionLabel(section: string): string {
    switch (section) {
      case 'nagul':
        return 'Выгул';
      case 'publish':
        return 'Публикация';
      case 'recovery':
        return 'Восстановление';
      case 'bad':
        return 'Плохие';
      default:
        return section;
    }
  }

  sharedChatSyncSummary(response: AdminSharedChatLinkSyncResponse): string {
    const parts = [
      `компаний обновлено: ${response.updatedCompanies}`,
      `WhatsApp: ${response.whatsappLinked}`,
      `Telegram: ${response.telegramLinked}`,
      `MAX: ${response.maxLinked}`
    ];
    if (response.conflictGroups > 0) {
      parts.push(`конфликтов: ${response.conflictGroups}`);
    }
    return parts.join(', ');
  }

  resetSettingsForm(): void {
    this.settingsForm.reset({
      workerAccountActionCooldownEnabled: this.workerAccountActionSettings()?.enabled ?? true,
      workerAccountActionCooldownSeconds: this.workerAccountActionSettings()?.cooldownSeconds ?? 60,
      nagulCooldownMinutes: this.nagulSettings()?.cooldownMinutes ?? 60,
      nagulLookaheadDays: this.nagulSettings()?.lookaheadDays ?? 60,
      accountWalkedCounterThreshold: this.nagulSettings()?.accountWalkedCounterThreshold ?? 3,
      accountWalkDelayDays: this.nagulSettings()?.accountWalkDelayDays ?? 2,
      morningReportEnabled: this.telegramReportSettings()?.morningEnabled ?? true,
      morningReportTime: this.telegramReportSettings()?.morningTime ?? '11:30',
      eveningReportEnabled: this.telegramReportSettings()?.eveningEnabled ?? true,
      eveningReportTime: this.telegramReportSettings()?.eveningTime ?? '22:00',
      telegramReportZone: this.telegramReportSettings()?.zone ?? 'Asia/Irkutsk',
      whatsAppGroupSyncEnabled: this.whatsAppGroupSyncSettings()?.enabled ?? true,
      whatsAppGroupSyncIntervalMinutes: this.whatsAppGroupSyncSettings()?.intervalMinutes ?? 30,
      clientPublicationProgressReportsEnabled:
        this.clientPublicationProgressReportSettings()?.enabled ?? true
    });
  }
}
