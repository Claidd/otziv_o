import { computed, signal, WritableSignal } from '@angular/core';
import { FormBuilder, Validators } from '@angular/forms';
import { concat, finalize, forkJoin, Observable, of, tap } from 'rxjs';
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
  /**
   * Called when a save finished after its view was left (another tab, a hidden browser tab): the page should read
   * the settings again when it is shown.
   */
  onLateResult?: () => void;
};

/** The parts of the form that are saved by separate requests, in the order they are sent. */
type SettingsSection =
  | 'workerAccountAction'
  | 'nagul'
  | 'telegram'
  | 'whatsApp'
  | 'clientReports'
  | 'workerCellularAccess';

/** Fields with their own message under the input and a name in the summary above «Сохранить». */
const SETTINGS_FIELDS = {
  workerAccountActionCooldownSeconds: {
    label: 'пауза между сменой и блокировкой',
    message: 'Введите целое число от 0 до 3600 секунд.'
  },
  nagulCooldownMinutes: {
    label: 'время между выгулами',
    message: 'Введите целое число от 0 до 1440 минут.'
  },
  nagulLookaheadDays: {
    label: 'горизонт выдачи выгула',
    message: 'Введите целое число от 0 до 365 дней.'
  },
  accountWalkedCounterThreshold: {
    label: 'порог выгулянного аккаунта',
    message: 'Введите целое число от 1 до 30.'
  },
  accountWalkDelayDays: {
    label: 'задержка публикации',
    message: 'Введите целое число от 0 до 30 дней.'
  },
  morningReportTime: {
    label: 'время утренней рассылки',
    message: 'Укажите время в виде ЧЧ:ММ, например 11:30.'
  },
  eveningReportTime: {
    label: 'время вечерней рассылки',
    message: 'Укажите время в виде ЧЧ:ММ, например 22:00.'
  },
  telegramReportZone: {
    label: 'часовой пояс',
    message: 'Укажите часовой пояс, например Asia/Irkutsk.'
  },
  whatsAppGroupSyncIntervalMinutes: {
    label: 'интервал проверки WhatsApp',
    message: 'Введите целое число от 5 до 1440 минут.'
  }
} as const;

export type WorkSettingsField = keyof typeof SETTINGS_FIELDS;

const WHOLE_NUMBER = /^\d+$/;

export class AdminWorkSettingsFacade {
  private readonly fb = new FormBuilder();
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly deleting = signal(false);
  readonly error = signal<string | null>(null);
  /**
   * True only after every section was read from the server. Until then the form holds placeholders, and saving it
   * would replace the real settings with them.
   */
  readonly settingsLoaded = signal(false);
  /** The last failure of «Синхронизировать сейчас»: shown at the button, not as a failure of the whole tab. */
  readonly whatsAppSyncError = signal<string | null>(null);
  /** The last failure of «Скопировать id общих чатов». */
  readonly sharedChatSyncError = signal<string | null>(null);
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
    // A failed or interrupted reload must not leave saving open on the values of an earlier visit.
    this.settingsLoaded.set(false);
    this.error.set(null);
    this.readSettings(new Set());
  }

  /**
   * Reads every section. The sections named in `keepForm` refresh only their signals: the form keeps what the user
   * typed there (used after a save that stopped half way).
   */
  private readSettings(keepForm: ReadonlySet<SettingsSection>): void {
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
          if (keepForm.has('nagul')) this.nagulSettings.set(response.nagulSettings);
          else this.applyNagulSettings(response.nagulSettings);
          if (keepForm.has('workerAccountAction')) {
            this.workerAccountActionSettings.set(response.workerAccountActionSettings);
          } else {
            this.applyWorkerAccountActionSettings(response.workerAccountActionSettings);
          }
          if (keepForm.has('telegram')) this.telegramReportSettings.set(response.telegramReportSettings);
          else this.applyTelegramReportSettings(response.telegramReportSettings);
          if (keepForm.has('whatsApp')) this.whatsAppGroupSyncSettings.set(response.whatsAppGroupSyncSettings);
          else this.applyWhatsAppGroupSyncSettings(response.whatsAppGroupSyncSettings);
          if (keepForm.has('clientReports')) {
            this.clientPublicationProgressReportSettings.set(
              response.clientPublicationProgressReportSettings
            );
          } else {
            this.applyClientPublicationProgressReportSettings(
              response.clientPublicationProgressReportSettings
            );
          }
          if (response.workerCellularAccessSettings) {
            if (keepForm.has('workerCellularAccess')) {
              this.workerCellularAccessSettings.set(response.workerCellularAccessSettings);
            } else {
              this.applyWorkerCellularAccessSettings(response.workerCellularAccessSettings);
            }
          }
          // A full read rewrites every field: the form holds the saved values again. After a partly refused save
          // the kept sections stay unsaved, so the form stays dirty then.
          if (keepForm.size === 0) this.settingsForm.markAsPristine();
          this.settingsLoaded.set(true);
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
      [Validators.required, Validators.min(0), Validators.max(3600), Validators.pattern(WHOLE_NUMBER)]
    ],
    // The server keeps these as whole numbers and would silently drop a fraction («1,5 минуты» → 1).
    nagulCooldownMinutes: [
      60,
      [Validators.required, Validators.min(0), Validators.max(1440), Validators.pattern(WHOLE_NUMBER)]
    ],
    nagulLookaheadDays: [
      60,
      [Validators.required, Validators.min(0), Validators.max(365), Validators.pattern(WHOLE_NUMBER)]
    ],
    accountWalkedCounterThreshold: [
      3,
      [Validators.required, Validators.min(1), Validators.max(30), Validators.pattern(WHOLE_NUMBER)]
    ],
    accountWalkDelayDays: [
      2,
      [Validators.required, Validators.min(0), Validators.max(30), Validators.pattern(WHOLE_NUMBER)]
    ],
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
      [Validators.required, Validators.min(5), Validators.max(1440), Validators.pattern(WHOLE_NUMBER)]
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

  /** A read, a save or a sync is running: the other actions of the tab wait for it. */
  readonly busy = computed(
    () =>
      this.loading() ||
      this.saving() ||
      this.scope.writing() ||
      this.syncingWhatsAppGroups() ||
      this.syncingSharedChatLinks()
  );

  /** «Сохранить» is offered only for settings that came from the server and while nothing else runs. */
  readonly canSaveSettings = computed(() => this.settingsLoaded() && !this.busy());

  /** «Синхронизировать сейчас» and «Скопировать id общих чатов». */
  readonly canRunSync = computed(() => this.settingsLoaded() && !this.busy());

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
    if (!this.scope.active() || this.saving() || this.deleting()) return;

    if (this.busy()) {
      this.toastService.error(
        'Настройки не сохранены',
        'Дождитесь окончания текущей операции и нажмите «Сохранить» ещё раз.'
      );
      return;
    }

    if (!this.settingsLoaded()) {
      this.toastService.error(
        'Настройки не сохранены',
        'Настройки ещё не загрузились с сервера. Нажмите «Обновить» и повторите.'
      );
      return;
    }

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

    // The mobile access control is written only when this form changed it: a tab opened before another
    // administrator switched on «Боевой режим» must not quietly put back the mode it was loaded with.
    const cellularChanged = this.canApplyMaintenance()
      && workerCellularAccessChanged(this.workerCellularAccessSettings(), workerCellularAccessRequest);

    this.saving.set(true);
    if (
      cellularChanged &&
      requiresWorkerCellularEnforceConfirmation(
        this.workerCellularAccessSettings()?.mode,
        workerCellularAccessRequest.mode
      ) &&
      !window.confirm(this.enforceConfirmationText(raw))
    ) {
      this.saving.set(false);
      return;
    }
    if (
      cellularChanged &&
      this.workerCellularAccessSettings()?.mode === 'ENFORCE' &&
      workerCellularAccessRequest.mode !== 'ENFORCE' &&
      !window.confirm('Снять «Боевой режим» контроля мобильного доступа? Специалисты снова смогут работать через Wi-Fi, VPN или компьютер, если эти причины не блокируются.')
    ) {
      this.saving.set(false);
      return;
    }

    this.saving.set(true);
    this.error.set(null);

    const steps: { section: SettingsSection; request: Observable<unknown> }[] = [
      {
        section: 'workerAccountAction',
        request: this.dictionariesApi.updateWorkerAccountActionSettings(workerAccountActionRequest)
      },
      { section: 'nagul', request: this.dictionariesApi.updateNagulSettings(nagulRequest) },
      { section: 'telegram', request: this.dictionariesApi.updateTelegramReportSettings(telegramRequest) },
      { section: 'whatsApp', request: this.dictionariesApi.updateWhatsAppGroupSyncSettings(whatsAppRequest) },
      {
        section: 'clientReports',
        request: this.dictionariesApi.updateClientPublicationProgressReportSettings(
          clientPublicationProgressReportRequest
        )
      }
    ];
    if (cellularChanged) {
      steps.push({
        section: 'workerCellularAccess',
        request: this.dictionariesApi.updateWorkerCellularAccessSettings(workerCellularAccessRequest)
      });
    }

    let completed = 0;
    const command = this.reportWhenViewLeft(
      concat(...steps.map((step) => step.request)).pipe(
        tap(() => {
          completed += 1;
        })
      ),
      (outcome) => {
        // The view was left while saving: its callbacks are suppressed, but the owner still learns the result.
        if (outcome.failed) {
          const message = this.errorMessage(outcome.error, 'Не удалось сохранить настройки');
          this.toastService.error(
            completed > 0 ? 'Настройки сохранены частично' : 'Настройки не сохранены',
            `Применено разделов: ${completed} из ${steps.length}. ${message}`
          );
        } else {
          this.toastService.success('Настройки сохранены', 'Все разделы применены.');
        }
        this.readAgainAfterLeftCommand();
      }
    );
    this.scope.write(command.request, this.saving).subscribe({
      complete: () => {
        command.received();
        this.saving.set(false);
        this.load();
        this.toastService.success(
          'Настройки сохранены',
          'Все разделы применены; состояние перечитано с сервера.'
        );
      },
      error: (err) => {
        command.received();
        const message = this.errorMessage(err, 'Не удалось сохранить настройки');
        this.error.set(message);
        this.saving.set(false);
        // Sections after the refused one were never sent: their edits stay in the form, the saved ones are re-read.
        const notSaved = new Set(steps.slice(completed).map((step) => step.section));
        this.readSettings(notSaved);
        this.toastService.error(
          completed > 0 ? 'Настройки сохранены частично' : 'Настройки не сохранены',
          completed > 0
            ? `Применено разделов: ${completed} из ${steps.length}. Остальные изменения остались в форме — исправьте ошибку и сохраните ещё раз. ${message}`
            : `Изменения остались в форме — исправьте ошибку и сохраните ещё раз. ${message}`
        );
      }
    });
  }

  runWhatsAppGroupSync(): void {
    if (!this.scope.active() || this.syncingWhatsAppGroups()) return;
    if (!this.syncAllowed()) return;

    this.syncingWhatsAppGroups.set(true);
    this.whatsAppSyncError.set(null);

    this.scope
      .write(this.dictionariesApi.runWhatsAppGroupSync(), this.syncingWhatsAppGroups)
      .subscribe({
        next: (settings) => {
          this.syncingWhatsAppGroups.set(false);
          // Only the result of the run: unsaved edits of the WhatsApp fields stay in the form.
          this.whatsAppGroupSyncSettings.set(settings);
          this.toastService.success(
            'WhatsApp-группы проверены',
            `Новых привязок: ${settings.lastLinkedCount}`
          );
          this.restoreInterruptedRead();
        },
        error: (err: unknown) => {
          const message = this.errorMessage(
            err,
            'Не удалось запустить синхронизацию WhatsApp-групп'
          );
          this.whatsAppSyncError.set(message);
          this.syncingWhatsAppGroups.set(false);
          this.toastService.error('WhatsApp не синхронизирован', message);
          this.restoreInterruptedRead();
        }
      });
  }

  runSharedChatLinkSync(): void {
    if (!this.scope.active() || this.syncingSharedChatLinks()) return;
    if (!this.syncAllowed()) return;

    this.syncingSharedChatLinks.set(true);
    this.sharedChatSyncError.set(null);

    this.scope
      .write(this.dictionariesApi.runSharedChatLinkSync(), this.syncingSharedChatLinks)
      .subscribe({
        next: (response) => {
          this.syncingSharedChatLinks.set(false);
          this.toastService.success(
            'Общие чаты синхронизированы',
            this.sharedChatSyncSummary(response)
          );
          this.restoreInterruptedRead();
        },
        error: (err: unknown) => {
          const message = this.errorMessage(err, 'Не удалось синхронизировать общие чаты');
          this.sharedChatSyncError.set(message);
          this.syncingSharedChatLinks.set(false);
          this.toastService.error('Общие чаты не синхронизированы', message);
          this.restoreInterruptedRead();
        }
      });
  }

  /** A sync never starts unnoticed: it says why it waits instead of doing nothing. */
  private syncAllowed(): boolean {
    if (this.busy()) {
      this.toastService.error(
        'Синхронизация не запущена',
        'Дождитесь окончания текущей операции и повторите.'
      );
      return false;
    }
    if (!this.settingsLoaded()) {
      this.toastService.error(
        'Синхронизация не запущена',
        'Настройки ещё не загрузились с сервера. Нажмите «Обновить» и повторите.'
      );
      return false;
    }
    return true;
  }

  /**
   * A command cancels every read of the view when it starts and when it answers. If «Обновить» was pressed meanwhile,
   * that read is gone: start it again so the tab does not stay without data.
   */
  private restoreInterruptedRead(): void {
    if (!this.settingsLoaded() && !this.loading()) this.load();
  }

  /** After a save whose view was left: read again now if the tab is open and untouched, otherwise when it is shown. */
  private readAgainAfterLeftCommand(): void {
    if (this.scope.active()) {
      if (!this.settingsForm.dirty) this.load();
      return;
    }
    this.deps.onLateResult?.();
  }

  /**
   * DictionaryViewScope suppresses the callbacks of a command whose view was left; the command itself still runs to
   * its end. `late` receives that end when no callback did (`received` is called by every callback that ran).
   */
  private reportWhenViewLeft<T>(
    request: Observable<T>,
    late: (outcome: { failed: false } | { failed: true; error: unknown }) => void
  ): { request: Observable<T>; received: () => void } {
    let received = false;
    let outcome: { failed: false } | { failed: true; error: unknown } | null = null;
    return {
      request: request.pipe(
        tap({
          error: (error: unknown) => {
            outcome = { failed: true, error };
          },
          complete: () => {
            outcome = { failed: false };
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

  /** The confirmation names only the reasons that are switched on. */
  private enforceConfirmationText(raw: {
    blockNonCellularNetwork: boolean;
    blockVpnProxyOrDatacenter: boolean;
    blockDesktopOrUnknownDevice: boolean;
    blockUnknownNetwork: boolean;
    enforceNativeVirtualDevice: boolean;
  }): string {
    const cases: string[] = [];
    if (raw.blockNonCellularNetwork) cases.push('через домашнюю сеть или Wi-Fi');
    if (raw.blockVpnProxyOrDatacenter) cases.push('через VPN, прокси, Tor или дата-центр');
    if (raw.blockDesktopOrUnknownDevice) cases.push('с компьютера или неопределённого устройства');
    if (raw.blockUnknownNetwork) cases.push('из сети, которую не удалось определить');
    if (raw.enforceNativeVirtualDevice) cases.push('из эмулятора приложения');
    return cases.length > 0
      ? `Включить боевой режим? В защищённых подразделах специалисты получат отказ, если заходят ${cases.join(', ')}.`
      : 'Включить боевой режим? Причины блокировки не выбраны, поэтому отказов не будет.';
  }

  /** The message under a field once it was touched and is wrong, otherwise null. */
  settingsFieldError(field: WorkSettingsField): string | null {
    const control = this.settingsForm.controls[field];
    return control.invalid && control.touched ? SETTINGS_FIELDS[field].message : null;
  }

  /** The line above «Сохранить»: which fields stop the form from being saved. */
  settingsInvalidSummary(): string {
    const labels = (Object.keys(SETTINGS_FIELDS) as WorkSettingsField[])
      .filter((field) => this.settingsForm.controls[field].invalid)
      .map((field) => SETTINGS_FIELDS[field].label);
    return labels.length > 0 ? `Исправьте: ${labels.join(', ')}.` : '';
  }

  /**
   * A hint only: the server checks the zone itself and accepts a few forms the browser does not know, so the field
   * is never blocked by this.
   */
  telegramZoneWarning(): string | null {
    const zone = this.settingsForm.controls.telegramReportZone.value.trim();
    if (!zone) return null;
    try {
      Intl.DateTimeFormat('ru-RU', { timeZone: zone }).resolvedOptions();
      return null;
    } catch {
      return 'Такой часовой пояс не распознан. Проверьте написание, например Asia/Irkutsk: сервер может его не принять.';
    }
  }

  /** «2026-11-21 morning 11:30 Asia/Irkutsk» → «утро: 21.11.2026 в 11:30 (Asia/Irkutsk)». */
  telegramRunLabel(key: string | null | undefined, period: string): string {
    const value = (key ?? '').trim();
    if (!value) return `${period}: ещё не было`;
    const match = /^(\d{4})-(\d{2})-(\d{2}) \S+ (\d{1,2}:\d{2})(?::\d{2})? (.+)$/.exec(value);
    return match
      ? `${period}: ${match[3]}.${match[2]}.${match[1]} в ${match[4]} (${match[5]})`
      : `${period}: ${value}`;
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
    this.settingsForm.patchValue(this.workerCellularAccessFormValue(response));
  }

  private workerCellularAccessFormValue(response: AdminWorkerCellularAccessSettings) {
    const reasons = new Set(response.enforcedReasons);
    return {
      workerCellularAccessMode: response.mode,
      blockNonCellularNetwork: reasons.has('NON_CELLULAR_NETWORK'),
      blockVpnProxyOrDatacenter: reasons.has('VPN_PROXY_OR_DATACENTER'),
      blockDesktopOrUnknownDevice: reasons.has('DESKTOP_OR_UNKNOWN_DEVICE'),
      blockUnknownNetwork: reasons.has('UNKNOWN_NETWORK'),
      enforceNativeVirtualDevice: response.enforceNativeVirtualDevice
    };
  }

  workerCellularAccessModeLabel(mode: WorkerCellularAccessMode): string {
    switch (mode) {
      case 'OFF':
        return 'выключено';
      case 'AUDIT':
        return 'наблюдение без блокировки';
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
    const cellular = this.workerCellularAccessSettings();
    const controls = this.settingsForm.controls;
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
        this.clientPublicationProgressReportSettings()?.enabled ?? true,
      // Mobile access comes from the loaded settings too; without them the fields keep their values instead of
      // falling back to the «Боевой режим» placeholders of the form.
      ...(cellular
        ? this.workerCellularAccessFormValue(cellular)
        : {
            workerCellularAccessMode: controls.workerCellularAccessMode.value,
            blockNonCellularNetwork: controls.blockNonCellularNetwork.value,
            blockVpnProxyOrDatacenter: controls.blockVpnProxyOrDatacenter.value,
            blockDesktopOrUnknownDevice: controls.blockDesktopOrUnknownDevice.value,
            blockUnknownNetwork: controls.blockUnknownNetwork.value,
            enforceNativeVirtualDevice: controls.enforceNativeVirtualDevice.value
          })
    });
  }
}

/** The mobile access request differs from what the server returned when the settings were loaded. */
function workerCellularAccessChanged(
  loaded: AdminWorkerCellularAccessSettings | null | undefined,
  next: WorkerCellularAccessSettingsRequest
): boolean {
  if (!loaded) return true;
  const reasons = (list: readonly string[]) => [...list].sort().join(',');
  return loaded.mode !== next.mode
    || loaded.enforceNativeVirtualDevice !== next.enforceNativeVirtualDevice
    || reasons(loaded.enforcedReasons ?? []) !== reasons(next.enforcedReasons);
}
