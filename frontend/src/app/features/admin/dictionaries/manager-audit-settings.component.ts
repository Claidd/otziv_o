import { Component, ElementRef, computed, inject, output, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import {
  ManagerReportReviewManagerSetting,
  ManagerReportReviewEvidenceSettings,
  ManagerReportReviewSettings,
  ManagerReportReviewSettingsApi,
  ManagerReportReviewSettingsRequest
} from '../../../core/manager-report-review-settings.api';
import { apiErrorMessage } from '../../../shared/api-error-message';
import { ToastService } from '../../../shared/toast.service';

export const DEFAULT_MANAGER_EVIDENCE_SETTINGS: ManagerReportReviewEvidenceSettings = {
  diagnosisMode: 'SHADOW', diagnosisPilotManagerIds: [], diagnosisMaxRequestsPerHour: 60,
  diagnosisMaxOutputTokens: 8000, diagnosisTimeoutSeconds: 45, diagnosisThinkingEnabled: true,
  answerMode: 'SHADOW', answerPilotManagerIds: [], answerMaxTokens: 8000, answerTimeoutSeconds: 90,
  answerThinkingEnabled: false, questionTimeoutSeconds: 90, questionThinkingEnabled: true,
  evidenceMaxAgeMinutes: 15
};

type PilotField = 'diagnosisPilotManagerIds' | 'answerPilotManagerIds';

/**
 * Number fields in the order they stand on the page. `folded` fields live in «Генерация вопросов», which is closed
 * until opened; a wrong value there is shown by opening it.
 */
const NUMBER_FIELDS: { path: string; label: string; folded?: boolean }[] = [
  { path: 'minimumReadSeconds', label: 'Минимальное чтение' },
  { path: 'testMinimumReadSeconds', label: 'Чтение в тестовом режиме' },
  { path: 'reminderOneMinutes', label: 'Первое напоминание' },
  { path: 'reminderThreeMinutes', label: 'Срок прохождения' },
  { path: 'evidence.diagnosisMaxRequestsPerHour', label: 'Максимум запросов в час' },
  { path: 'evidence.diagnosisMaxOutputTokens', label: 'Лимит ответа' },
  { path: 'evidence.diagnosisTimeoutSeconds', label: 'Таймаут диагностики' },
  { path: 'evidence.answerMaxTokens', label: 'Лимит проверки ответа' },
  { path: 'evidence.answerTimeoutSeconds', label: 'Таймаут проверки ответа' },
  { path: 'evidence.evidenceMaxAgeMinutes', label: 'Допустимый возраст текущих фактов' },
  { path: 'maxQuestionCount', label: 'Максимум вопросов', folded: true },
  { path: 'questionGenerationMaxTokens', label: 'Лимит генерации', folded: true },
  { path: 'questionGenerationRetryMaxTokens', label: 'Лимит повторной генерации', folded: true },
  { path: 'evidence.questionTimeoutSeconds', label: 'Таймаут генерации', folded: true }
];

@Component({
  selector: 'app-manager-audit-settings',
  imports: [ReactiveFormsModule],
  templateUrl: './manager-audit-settings.component.html',
  styleUrl: './manager-audit-settings.component.scss'
})
export class ManagerAuditSettingsComponent {
  private readonly fb = inject(FormBuilder);
  private readonly api = inject(ManagerReportReviewSettingsApi);
  private readonly toast = inject(ToastService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly managerCount = output<number>();
  readonly settings = signal<ManagerReportReviewSettings | null>(null);
  readonly loading = signal(true);
  readonly saving = signal(false);
  readonly managerSavingId = signal<number | null>(null);
  readonly error = signal<string | null>(null);
  /**
   * The error belongs to «Сохранить настройки» (a wrong field, a refused save): it is shown next to that button, at
   * the bottom of the form, instead of the banner at the top.
   */
  readonly errorAtSave = signal(false);
  readonly managerSearch = signal('');

  readonly enabledManagers = computed(
    () => this.settings()?.managers.filter((manager) => manager.auditEnabled).length ?? 0
  );
  readonly connectedGroups = computed(
    () => this.settings()?.managers.filter((manager) => manager.auditGroupConnected).length ?? 0
  );
  readonly filteredManagers = computed(() => {
    const query = this.managerSearch().trim().toLocaleLowerCase('ru');
    const managers = this.settings()?.managers ?? [];
    return query
      ? managers.filter((manager) => manager.managerName.toLocaleLowerCase('ru').includes(query))
      : managers;
  });

  readonly form = this.fb.nonNullable.group({
    enabled: [true],
    managerGroupsEnabled: [true],
    restrictionEnabled: [true],
    maxQuestionCount: [8, [Validators.required, Validators.min(1), Validators.max(12)]],
    minimumReadSeconds: [60, [Validators.required, Validators.min(30), Validators.max(300)]],
    testMinimumReadSeconds: [10, [Validators.required, Validators.min(3), Validators.max(30)]],
    reminderOneMinutes: [60, [Validators.required, Validators.min(5), Validators.max(1440)]],
    reminderThreeMinutes: [180, [Validators.required, Validators.min(10), Validators.max(4320)]],
    questionGenerationMaxTokens: [8000, [Validators.required, Validators.min(2000), Validators.max(16000)]],
    questionGenerationRetryMaxTokens: [12000, [Validators.required, Validators.min(2000), Validators.max(24000)]] ,
    evidence: this.fb.nonNullable.group({
      diagnosisMode: ['SHADOW' as ManagerReportReviewEvidenceSettings['diagnosisMode'], Validators.required],
      diagnosisPilotManagerIds: this.fb.nonNullable.control<number[]>([]),
      diagnosisMaxRequestsPerHour: [60, [Validators.required, Validators.min(1), Validators.max(1000)]],
      diagnosisMaxOutputTokens: [8000, [Validators.required, Validators.min(1500), Validators.max(12000)]],
      diagnosisTimeoutSeconds: [45, [Validators.required, Validators.min(10), Validators.max(60)]],
      diagnosisThinkingEnabled: [true],
      answerMode: ['SHADOW' as ManagerReportReviewEvidenceSettings['answerMode'], Validators.required],
      answerPilotManagerIds: this.fb.nonNullable.control<number[]>([]),
      answerMaxTokens: [8000, [Validators.required, Validators.min(2000), Validators.max(16000)]],
      answerTimeoutSeconds: [90, [Validators.required, Validators.min(5), Validators.max(240)]],
      answerThinkingEnabled: [false],
      questionTimeoutSeconds: [90, [Validators.required, Validators.min(5), Validators.max(240)]],
      questionThinkingEnabled: [true],
      evidenceMaxAgeMinutes: [15, [Validators.required, Validators.min(1), Validators.max(120)]]
    })
  });

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.errorAtSave.set(false);
    this.api.settings().subscribe({
      next: (settings) => {
        this.apply(settings);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.error.set(apiErrorMessage(err, 'Не удалось загрузить настройки аудита'));
        this.loading.set(false);
      }
    });
  }

  /** «Обновить»: reading the settings again drops typed edits, so it asks first. */
  refresh(): void {
    if (this.form.dirty && !window.confirm('Несохранённые изменения пропадут. Обновить настройки?')) {
      return;
    }
    this.load();
  }

  save(): void {
    if (this.saving()) return;
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.reportInvalidFields();
      return;
    }
    const current = this.settings();
    if (!current) return;
    const { managers: _managers, ...previous } = current;
    const request: ManagerReportReviewSettingsRequest = { ...previous, ...this.form.getRawValue() };
    const evidence = request.evidence!;
    if ((evidence.diagnosisMode === 'PILOT' && !this.visiblePilotIds(evidence.diagnosisPilotManagerIds).length)
      || (evidence.answerMode === 'PILOT' && !this.visiblePilotIds(evidence.answerPilotManagerIds).length)) {
      this.refuseSave('Выберите хотя бы одного менеджера для каждого включённого пилота.');
      return;
    }
    if (request.reminderThreeMinutes <= request.reminderOneMinutes) {
      this.refuseSave('Срок прохождения должен быть больше времени первого напоминания.');
      return;
    }
    if (request.questionGenerationRetryMaxTokens < request.questionGenerationMaxTokens) {
      this.refuseSave('Лимит повторной генерации не может быть меньше основного лимита.');
      return;
    }
    // Asked only when the saved audit is switched off now, not on every save while it stays off.
    if (current.enabled && !request.enabled && !window.confirm(
      'Выключить аудит полностью? Новые аудиты не будут формироваться, а текущие незавершённые аудиты закроются.'
    )) {
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    this.errorAtSave.set(false);
    this.api.update(request).subscribe({
      next: (settings) => {
        this.apply(settings);
        this.saving.set(false);
        this.toast.success(
          'Настройки аудита сохранены',
          settings.enabled ? 'Формирование аудитов включено' : 'Аудит полностью выключен'
        );
      },
      error: (err: unknown) => {
        const message = apiErrorMessage(err, 'Не удалось сохранить настройки аудита');
        this.error.set(message);
        this.errorAtSave.set(true);
        this.saving.set(false);
        this.toast.error('Настройки не сохранены', message);
      }
    });
  }

  toggleManager(manager: ManagerReportReviewManagerSetting): void {
    if (this.managerSavingId() != null) {
      return;
    }
    const enabled = !manager.auditEnabled;
    if (!enabled && !window.confirm(
      `Выключить аудит для «${manager.managerName}»? Текущий незавершённый аудит этого менеджера будет закрыт.`
    )) {
      return;
    }
    this.managerSavingId.set(manager.managerId);
    this.error.set(null);
    this.errorAtSave.set(false);
    this.api.updateManager(manager.managerId, enabled).subscribe({
      next: (updated) => {
        const current = this.settings();
        if (current) {
          const managers = current.managers.map((item) =>
            item.managerId === updated.managerId ? updated : item
          );
          this.settings.set({ ...current, managers });
          this.managerCount.emit(managers.length);
        }
        this.managerSavingId.set(null);
        this.toast.success(
          enabled ? 'Аудит менеджера включён' : 'Аудит менеджера выключен',
          updated.managerName
        );
      },
      error: (err: unknown) => {
        const message = apiErrorMessage(err, 'Не удалось изменить аудит менеджера');
        this.error.set(message);
        this.managerSavingId.set(null);
        this.toast.error('Переключатель не сохранён', message);
      }
    });
  }

  trackManager(_index: number, manager: ManagerReportReviewManagerSetting): number {
    return manager.managerId;
  }

  /** A wrong number after a save attempt: its hint turns red and names the allowed range. */
  invalid(path: string): boolean {
    const control = this.form.get(path);
    return !!control && control.invalid && control.touched;
  }

  /** «Аудит работает» is what the server has; a switch moved but not saved yet is marked so. */
  summaryUnsaved(): boolean {
    const saved = this.settings();
    return !!saved && saved.enabled !== this.form.controls.enabled.value;
  }

  /**
   * What the selected answer check does to the access restriction or to reminders: in «Сверка качества» neither works,
   * in a pilot only for its managers, and nothing works while the AI is unavailable.
   */
  rolloutNote(kind: 'restriction' | 'reminders'): string {
    const mode = this.form.controls.evidence.controls.answerMode.value;
    if (kind === 'restriction') {
      // A switched-off restriction is said to be off: a sentence about its scope would read as if it worked.
      if (!this.form.controls.restrictionEnabled.value) return 'Ограничение доступа выключено.';
      const scope = mode === 'SHADOW'
        ? 'В режиме «Сверка качества» ограничение доступа не действует.'
        : mode === 'PILOT'
          ? 'Ограничение действует только для менеджеров пилота проверки ответов.'
          : 'Ограничение действует для всех менеджеров.';
      return `${scope} Пока ИИ недоступен, доступ не ограничивается.`;
    }
    const scope = mode === 'SHADOW'
      ? 'В режиме «Сверка качества» напоминания не отправляются и срок не отслеживается.'
      : mode === 'PILOT'
        ? 'Напоминания и срок действуют только для менеджеров пилота проверки ответов.'
        : 'Напоминания и срок действуют для всех менеджеров.';
    return `${scope} Пока ИИ недоступен, напоминания не отправляются.`;
  }

  /**
   * Pilot managers stored on the server but missing from the list (deactivated or without the manager role): the
   * list cannot show them, so they are named separately and can be removed.
   */
  hiddenPilotIds(field: PilotField): number[] {
    const visible = new Set((this.settings()?.managers ?? []).map((manager) => manager.managerId));
    return this.form.controls.evidence.controls[field].value.filter((id) => !visible.has(id));
  }

  removePilotId(field: PilotField, id: number): void {
    const control = this.form.controls.evidence.controls[field];
    control.setValue(control.value.filter((item) => item !== id));
    control.markAsDirty();
  }

  private visiblePilotIds(ids: number[]): number[] {
    const visible = new Set((this.settings()?.managers ?? []).map((manager) => manager.managerId));
    return ids.filter((id) => visible.has(id));
  }

  private refuseSave(message: string): void {
    this.error.set(message);
    this.errorAtSave.set(true);
    this.toast.error('Настройки не сохранены', message);
  }

  /** Names the wrong fields, opens «Генерация вопросов» when one is inside and brings the first one into view. */
  private reportInvalidFields(): void {
    const wrong = NUMBER_FIELDS.filter((field) => this.form.get(field.path)?.invalid);
    const message = wrong.length
      ? `Проверьте ${wrong.length === 1 ? 'поле' : 'поля'}: ${wrong.map((field) => `«${field.label}»`).join(', ')}. Допустимые значения написаны под полями.`
      : 'Проверьте выделенные поля.';
    this.refuseSave(message);
    if (wrong.some((field) => field.folded)) {
      const advanced = this.host.nativeElement.querySelector<HTMLDetailsElement>('details.advanced-card');
      if (advanced) advanced.open = true;
    }
    const first = wrong[0];
    if (!first) return;
    const name = first.path.split('.').pop()!;
    const element = this.host.nativeElement.querySelector<HTMLElement>(`[formcontrolname="${name}"]`);
    if (!element) return;
    element.focus({ preventScroll: true });
    if (typeof element.scrollIntoView === 'function') {
      element.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }
  }

  private apply(settings: ManagerReportReviewSettings): void {
    this.settings.set(settings);
    this.managerCount.emit(settings.managers.length);
    this.form.patchValue({ ...settings, evidence: settings.evidence ?? DEFAULT_MANAGER_EVIDENCE_SETTINGS });
    // The form now holds what the server has: only later edits count as unsaved.
    this.form.markAsPristine();
  }
}
