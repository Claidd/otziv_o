import { Component, computed, inject, output, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import {
  ManagerReportReviewManagerSetting,
  ManagerReportReviewSettings,
  ManagerReportReviewSettingsApi,
  ManagerReportReviewSettingsRequest
} from '../../../core/manager-report-review-settings.api';
import { apiErrorMessage } from '../../../shared/api-error-message';
import { ToastService } from '../../../shared/toast.service';

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

  readonly managerCount = output<number>();
  readonly settings = signal<ManagerReportReviewSettings | null>(null);
  readonly loading = signal(true);
  readonly saving = signal(false);
  readonly managerSavingId = signal<number | null>(null);
  readonly error = signal<string | null>(null);
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
    minimumAnswerScore: [75, [Validators.required, Validators.min(60), Validators.max(100)]],
    maxAnswerCharacters: [420, [Validators.required, Validators.min(120), Validators.max(2000)]],
    maxPlanCharacters: [600, [Validators.required, Validators.min(120), Validators.max(3000)]],
    fastPasteSeconds: [12, [Validators.required, Validators.min(1), Validators.max(120)]],
    fastPasteMinCharacters: [140, [Validators.required, Validators.min(40), Validators.max(2000)]],
    copyGramSize: [4, [Validators.required, Validators.min(2), Validators.max(12)]],
    copySimilarityPercent: [65, [Validators.required, Validators.min(30), Validators.max(100)]],
    aiTimeoutSeconds: [25, [Validators.required, Validators.min(5), Validators.max(60)]],
    questionGenerationMaxTokens: [8000, [Validators.required, Validators.min(2000), Validators.max(16000)]],
    questionGenerationRetryMaxTokens: [12000, [Validators.required, Validators.min(2000), Validators.max(24000)]]
  });

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
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

  save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const request: ManagerReportReviewSettingsRequest = this.form.getRawValue();
    if (request.reminderThreeMinutes <= request.reminderOneMinutes) {
      this.error.set('Срок прохождения должен быть больше времени первого напоминания.');
      return;
    }
    if (request.questionGenerationRetryMaxTokens < request.questionGenerationMaxTokens) {
      this.error.set('Лимит повторной генерации не может быть меньше основного лимита.');
      return;
    }
    if (!request.enabled && !window.confirm(
      'Выключить аудит полностью? Новые аудиты не будут формироваться, а текущие незавершённые аудиты закроются.'
    )) {
      return;
    }
    this.saving.set(true);
    this.error.set(null);
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

  private apply(settings: ManagerReportReviewSettings): void {
    this.settings.set(settings);
    this.managerCount.emit(settings.managers.length);
    const { managers: _managers, ...formValue } = settings;
    this.form.setValue(formValue);
  }
}
