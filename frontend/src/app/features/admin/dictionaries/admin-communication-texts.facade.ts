import { computed, signal, WritableSignal } from '@angular/core';
import { FormBuilder, Validators } from '@angular/forms';
import { Observable } from 'rxjs';
import type { AdminCommunicationTextsApi } from '../../../core/admin-communication-texts.api';
import type {
  AdminPromoText,
  AdminManagerText,
  DictionaryOption,
  PromoButtonSlot,
  PromoTextAssignment,
  PromoTextAssignmentRequest,
  PromoTextManagementResponse,
  PromoTextRequest,
  ManagerTextRequest,
} from '../../../core/admin-dictionaries.api';
import type { ToastService } from '../../../shared/toast.service';
import { apiErrorMessage } from '../../../shared/api-error-message';
import { DictionaryViewScope } from './dictionary-view-scope';
import { DictionaryEditorSession } from './dictionary-editor-session';

const PROMO_TEXT_LABELS: Record<number, string> = {
  1: 'предложение',
  2: 'напоминание',
  3: 'данные',
  4: 'ответы',
  5: 'ссылка на проверку',
  6: 'напоминание заказа',
  7: 'угроза',
  10: 'рассылка',
  11: 'пояснение',
  12: 'текст повторного заказа',
};

type Deps = {
  api: AdminCommunicationTextsApi;
  toast: Pick<ToastService, 'success' | 'error'>;
  isActive: () => boolean;
  tab: () => string;
  selectedId: WritableSignal<number | null>;
  search: () => string;
};

export class AdminCommunicationTextsFacade {
  private readonly fb = new FormBuilder();
  private readonly scope: DictionaryViewScope;
  private assignmentGeneration = 0;
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly deleting = signal(false);
  readonly error = signal<string | null>(null);

  readonly promoTexts = signal<AdminPromoText[]>([]);

  readonly managerTexts = signal<AdminManagerText[]>([]);

  readonly promoManagers = signal<DictionaryOption[]>([]);

  readonly promoAssignments = signal<PromoTextAssignment[]>([]);

  readonly promoButtons = signal<PromoButtonSlot[]>([]);

  readonly selectedPromoManagerId = signal<number | null>(null);

  readonly promoTextForm = this.fb.nonNullable.group({
    text: ['', Validators.required],
  });

  readonly managerTextForm = this.fb.nonNullable.group({
    payText: [''],
    beginText: [''],
    offerText: [''],
    reminderText: [''],
    startText: [''],
  });

  readonly selectedManagerText = computed(() => {
    const managerId = this.selectedId();
    return managerId == null
      ? null
      : (this.managerTexts().find((managerText) => managerText.managerId === managerId) ?? null);
  });

  private readonly promoEditor = new DictionaryEditorSession(this.promoTextForm);
  private readonly managerEditor = new DictionaryEditorSession(this.managerTextForm);
  constructor(private readonly deps: Deps) {
    this.scope = new DictionaryViewScope(deps.isActive);
  }
  private get selectedId() {
    return this.deps.selectedId;
  }
  deactivate(): void {
    this.promoEditor.change();
    this.managerEditor.change();
    this.assignmentGeneration++;
    this.scope.deactivate();
  }
  destroy(): void {
    this.promoEditor.destroy();
    this.managerEditor.destroy();
    this.scope.destroy();
  }
  load(): void {
    if (!this.scope.active()) return;
    this.error.set(null);
    if (this.deps.tab() === 'promo') {
      this.reloadPromoManagement();
      return;
    }
    this.scope
      .read('texts', this.deps.api.getManagerTexts(this.deps.search()), this.loading)
      .subscribe({
        next: (rows) => this.managerTexts.set(rows),
        error: (err) =>
          this.fail(err, 'Справочник не загрузился', 'Не удалось загрузить тексты менеджеров'),
      });
  }
  clearSelection(): void {
    this.promoEditor.change();
    this.managerEditor.change();
    this.selectedId.set(null);
    this.error.set(null);
    this.promoTextForm.reset({ text: '' });
    this.managerTextForm.reset({
      payText: '',
      beginText: '',
      offerText: '',
      reminderText: '',
      startText: '',
    });
  }

  selectPromoText(promoText: AdminPromoText): void {
    this.promoEditor.change();
    this.selectedId.set(promoText.id);
    this.promoTextForm.setValue({ text: promoText.text });
    this.error.set(null);
  }

  selectManagerText(managerText: AdminManagerText): void {
    this.managerEditor.change();
    this.selectedId.set(managerText.managerId);
    this.managerTextForm.setValue({
      payText: managerText.payText,
      beginText: managerText.beginText,
      offerText: managerText.offerText,
      reminderText: managerText.reminderText,
      startText: managerText.startText,
    });
    this.error.set(null);
  }

  selectPromoManager(value: string | number): void {
    this.assignmentGeneration++;
    const managerId = Number(value);
    this.selectedPromoManagerId.set(Number.isFinite(managerId) && managerId > 0 ? managerId : null);
    this.error.set(null);
  }

  promoTextLabel(promoText: AdminPromoText): string {
    return PROMO_TEXT_LABELS[promoText.position] ?? `Текст #${promoText.position}`;
  }

  promoTextMeta(promoText: AdminPromoText): string {
    return `ID ${promoText.id} · позиция ${promoText.position}`;
  }

  promoTextPreview(value: string): string {
    const normalized = value.replace(/\s+/g, ' ').trim();
    return normalized.length > 110 ? `${normalized.slice(0, 110)}...` : normalized;
  }

  promoUsageSummary(promoText: AdminPromoText): string {
    const usages = this.promoAssignments()
      .filter((assignment) => assignment.promoTextId === promoText.id)
      .map((assignment) => `${assignment.managerTitle}: ${assignment.buttonLabel}`);
    if (usages.length) {
      return usages.slice(0, 2).join(', ') + (usages.length > 2 ? ` +${usages.length - 2}` : '');
    }
    const defaultButtons = this.promoButtons()
      .filter((button) => button.defaultPromoTextId === promoText.id)
      .map((button) => `${button.sectionTitle}: ${button.buttonLabel}`);
    return defaultButtons.length
      ? `по умолчанию: ${defaultButtons.slice(0, 2).join(', ')}`
      : 'не назначен';
  }

  managerTextSummary(managerText: AdminManagerText): string {
    const fields = [
      ['оплата', managerText.payText],
      ['начало', managerText.beginText],
      ['оффер', managerText.offerText],
      ['напоминание', managerText.reminderText],
      ['старт', managerText.startText],
    ];
    const filled = fields.filter(([, value]) => value.trim().length > 0).map(([label]) => label);
    return filled.length ? filled.join(', ') : 'тексты не заполнены';
  }

  managerTextPreview(managerText: AdminManagerText): string {
    const value =
      [
        managerText.payText,
        managerText.beginText,
        managerText.offerText,
        managerText.reminderText,
        managerText.startText,
      ].find((text) => text.trim().length > 0) ?? '';
    return this.promoTextPreview(value);
  }

  selectedPromoManagerTitle(): string {
    const managerId = this.selectedPromoManagerId();
    return (
      this.promoManagers().find((manager) => manager.id === managerId)?.title ??
      'Выберите менеджера'
    );
  }

  promoAssignmentValue(button: PromoButtonSlot): number | '' {
    return this.promoAssignmentFor(button)?.promoTextId ?? '';
  }

  promoDefaultTextLabel(button: PromoButtonSlot): string {
    const defaultText = this.promoTexts().find((text) => text.id === button.defaultPromoTextId);
    return defaultText
      ? this.promoTextLabel(defaultText)
      : `позиция ${button.defaultPromoPosition}`;
  }

  promoAssignedTextLabel(button: PromoButtonSlot): string {
    const assignment = this.promoAssignmentFor(button);
    if (!assignment?.promoTextId) {
      return `по умолчанию: ${this.promoDefaultTextLabel(button)}`;
    }
    const text = this.promoTexts().find((promoText) => promoText.id === assignment.promoTextId);
    return text ? this.promoTextLabel(text) : assignment.promoTextLabel;
  }

  private promoAssignmentFor(button: PromoButtonSlot): PromoTextAssignment | null {
    const managerId = this.selectedPromoManagerId();
    if (managerId == null) {
      return null;
    }
    return (
      this.promoAssignments().find(
        (assignment) =>
          assignment.managerId === managerId &&
          assignment.section === button.section &&
          assignment.buttonKey === button.buttonKey,
      ) ?? null
    );
  }

  savePromoText(): void {
    if (!this.scope.active() || this.saving()) return;
    if (this.promoTextForm.invalid) {
      this.promoTextForm.markAllAsTouched();
      return;
    }
    const request: PromoTextRequest = {
      text: this.promoTextForm.controls.text.value.trim(),
    };
    const selectedId = this.selectedId();
    const call =
      selectedId == null
        ? this.deps.api.createPromoText(request)
        : this.deps.api.updatePromoText(selectedId, request);
    const editor = this.promoEditor.capture(selectedId);
    this.saving.set(true);
    this.error.set(null);
    this.scope.write(call, this.saving).subscribe({
      next: (saved) => {
        this.saving.set(false);
        if (this.promoEditor.sameSelection(editor, this.selectedId()))
          this.selectedId.set(saved.id);
        this.deps.toast.success('Промо-текст сохранен', `ID ${saved.id}`);
        this.reloadPromoManagement();
      },
      error: (err) => {
        if (this.promoEditor.sameSelection(editor, this.selectedId()))
          this.fail(err, 'Запись не сохранена', 'Не удалось сохранить промо-текст');
      },
    });
  }

  saveManagerText(): void {
    if (!this.scope.active() || this.saving()) return;
    const managerId = this.selectedId();
    if (managerId == null) {
      this.error.set('Выберите менеджера для редактирования текстов.');
      return;
    }
    const raw = this.managerTextForm.getRawValue();
    const request: ManagerTextRequest = {
      payText: raw.payText,
      beginText: raw.beginText,
      offerText: raw.offerText,
      reminderText: raw.reminderText,
      startText: raw.startText,
    };
    const editor = this.managerEditor.capture(managerId);
    this.saving.set(true);
    this.error.set(null);
    this.scope.write(this.deps.api.updateManagerText(managerId, request), this.saving).subscribe({
      next: (saved) => {
        this.saving.set(false);
        if (!this.managerEditor.sameSelection(editor, this.selectedId())) return;
        this.managerTexts.update((items) =>
          items.map((item) => (item.managerId === saved.managerId ? saved : item)),
        );
        if (this.managerEditor.sameDraft(editor, this.selectedId()))
          this.managerTextForm.setValue({
            payText: saved.payText,
            beginText: saved.beginText,
            offerText: saved.offerText,
            reminderText: saved.reminderText,
            startText: saved.startText,
          });
        this.deps.toast.success('Тексты менеджера сохранены', saved.managerTitle);
      },
      error: (err) => {
        if (!this.managerEditor.sameSelection(editor, this.selectedId())) return;
        const message = apiErrorMessage(err, 'Не удалось сохранить тексты менеджера');
        this.error.set(message);
        this.saving.set(false);
        this.deps.toast.error('Тексты не сохранены', message);
      },
    });
  }

  savePromoAssignment(button: PromoButtonSlot, value: string): void {
    if (!this.scope.active() || this.saving()) return;
    const managerId = this.selectedPromoManagerId();
    if (managerId == null) {
      this.error.set('Выберите менеджера для назначения промо-текста.');
      return;
    }
    const generation = this.assignmentGeneration;
    this.saving.set(true);
    this.error.set(null);
    const request: Observable<PromoTextAssignment | void> = value
      ? this.deps.api.savePromoTextAssignment({
          managerId,
          section: button.section,
          buttonKey: button.buttonKey,
          promoTextId: Number(value),
        } satisfies PromoTextAssignmentRequest)
      : this.deps.api.resetPromoTextAssignment(managerId, button.section, button.buttonKey);
    this.scope.write(request, this.saving).subscribe({
      next: () => {
        this.saving.set(false);
        if (generation !== this.assignmentGeneration || managerId !== this.selectedPromoManagerId())
          return;
        this.deps.toast.success(
          'Назначение сохранено',
          `${button.sectionTitle}: ${button.buttonLabel}`,
        );
        this.reloadPromoManagement();
      },
      error: (err: unknown) => {
        if (generation !== this.assignmentGeneration || managerId !== this.selectedPromoManagerId())
          return;
        const message = apiErrorMessage(err, 'Не удалось сохранить назначение');
        this.error.set(message);
        this.saving.set(false);
        this.deps.toast.error('Назначение не сохранено', message);
      },
    });
  }

  private applyPromoManagement(
    response: PromoTextManagementResponse,
    allowDefaultSelection = true,
  ): void {
    this.promoTexts.set(response.texts);
    this.promoManagers.set(response.managers);
    this.promoAssignments.set(response.assignments);
    this.promoButtons.set(response.buttons);
    const selectedManagerId = this.selectedPromoManagerId();
    const hasSelectedManager =
      selectedManagerId != null &&
      response.managers.some((manager) => manager.id === selectedManagerId);
    if (allowDefaultSelection && !hasSelectedManager) {
      this.assignmentGeneration++;
      this.selectedPromoManagerId.set(response.managers[0]?.id ?? null);
    }
  }
  private reloadPromoManagement(): void {
    const generation = this.assignmentGeneration;
    this.scope
      .read('texts', this.deps.api.getPromoTextManagement(this.deps.search()), this.loading)
      .subscribe({
        next: (response) =>
          this.applyPromoManagement(response, generation === this.assignmentGeneration),
        error: (err) => this.fail(err, 'Промо не обновилось', 'Не удалось обновить промо-тексты'),
      });
  }
  private fail(error: unknown, title: string, fallback: string): void {
    const message = apiErrorMessage(error, fallback);
    this.error.set(message);
    this.deps.toast.error(title, message);
  }
}
