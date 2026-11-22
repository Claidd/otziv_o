import { computed, signal, WritableSignal } from '@angular/core';
import { FormBuilder, Validators } from '@angular/forms';
import { Observable, finalize, tap } from 'rxjs';
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
import { apiErrorDetail, apiErrorMessage } from '../../../shared/api-error-message';
import {
  FIRST_ORDER_REVIEW_PROMO_INDEX,
  REPEAT_ORDER_REVIEW_PROMO_INDEX,
} from '../../../shared/order-review-copy-text';
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

/** Positions of the texts specialists copy when they send reviews to a client for checking. */
const SPECIALIST_REVIEW_POSITIONS: ReadonlySet<number> = new Set([
  FIRST_ORDER_REVIEW_PROMO_INDEX + 1,
  REPEAT_ORDER_REVIEW_PROMO_INDEX + 1,
]);

/** Database columns: text_promo.promo_text is varchar(2555), managers.pay_text is varchar(1000). */
const PROMO_TEXT_STORED_LIMIT = 2555;
const PAY_TEXT_LIMIT = 1000;
/** The server stores every line break of a promo text as the marker «lineSep». */
const PROMO_LINE_BREAK_STORED = 'lineSep'.length;
/** How much of a text a choice of the button lists shows after its name. */
const PROMO_OPTION_PREVIEW = 40;

type Deps = {
  api: AdminCommunicationTextsApi;
  toast: Pick<ToastService, 'success' | 'error'>;
  isActive: () => boolean;
  tab: () => string;
  selectedId: WritableSignal<number | null>;
  search: () => string;
};

/** A button choice sent to the server and not answered yet. */
type PendingAssignment = {
  managerId: number;
  section: string;
  buttonKey: string;
  value: number | '';
};

/** Letters as the database counts them. */
function letterCount(text: string): number {
  return Array.from(text).length;
}

/** The length the server stores: line breaks become «lineSep» after \r\n and \r are turned into \n. */
function storedPromoTextLength(text: string): number {
  const normalized = text.replace(/\r\n?/g, '\n');
  const lineBreaks = normalized.split('\n').length - 1;
  return letterCount(normalized) + lineBreaks * (PROMO_LINE_BREAK_STORED - 1);
}

export class AdminCommunicationTextsFacade {
  private readonly fb = new FormBuilder();
  private readonly scope: DictionaryViewScope;
  private assignmentGeneration = 0;
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly deleting = signal(false);
  readonly error = signal<string | null>(null);

  /** Every promo text: choices of the button lists and names of default texts never depend on the search. */
  readonly allPromoTexts = signal<AdminPromoText[]>([]);

  /** The search applied by the last load: typing in the field changes nothing until the search is run. */
  private readonly promoQuery = signal('');

  /** Rows of the promo table: all texts narrowed by the applied search, as the server narrowed them. */
  readonly promoTexts = computed(() => {
    const query = this.promoQuery().toLowerCase();
    const texts = this.allPromoTexts();
    return query ? texts.filter((promoText) => this.promoTextMatches(promoText, query)) : texts;
  });

  readonly managerTexts = signal<AdminManagerText[]>([]);

  readonly promoManagers = signal<DictionaryOption[]>([]);

  readonly promoAssignments = signal<PromoTextAssignment[]>([]);

  readonly promoButtons = signal<PromoButtonSlot[]>([]);

  readonly selectedPromoManagerId = signal<number | null>(null);

  /** A choice being saved: its list keeps showing it, and a failure brings the saved text back. */
  private readonly pendingAssignment = signal<PendingAssignment | null>(null);

  readonly promoTextForm = this.fb.nonNullable.group({
    // The server trims the text and refuses an empty one: spaces and line breaks alone are no text.
    text: ['', [Validators.required, Validators.pattern(/\S/)]],
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
      this.promoQuery.set(this.deps.search().trim());
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

  /** A choice of the button lists: the name and the start of the text, so new «Текст #13» differ. */
  promoTextOptionLabel(promoText: AdminPromoText): string {
    const label = this.promoTextLabel(promoText);
    const text = promoText.text.replace(/\s+/g, ' ').trim();
    if (!text) return label;
    const chars = Array.from(text);
    if (chars.length <= PROMO_OPTION_PREVIEW) return `${label} — ${text}`;
    const cut = chars.slice(0, PROMO_OPTION_PREVIEW).join('');
    const lastSpace = cut.lastIndexOf(' ');
    const start = lastSpace > PROMO_OPTION_PREVIEW / 2 ? cut.slice(0, lastSpace) : cut;
    return `${label} — ${start.replace(/[\s,.;:—-]+$/, '')}…`;
  }

  /**
   * Where a text stands: buttons that show it by default (to every manager without a personal choice),
   * the specialists' review messages and personal choices of managers.
   */
  promoUsageSummary(promoText: AdminPromoText): string {
    const parts = this.promoUsageParts(promoText);
    return parts.length ? parts.join('; ') : 'не назначен';
  }

  /** The line under the promo text being edited: where it stands and that an edit changes it there. */
  selectedPromoTextUsage(): string | null {
    const promoText = this.selectedPromoText();
    if (!promoText) return null;
    const parts = this.promoUsageParts(promoText);
    return parts.length
      ? `Где стоит: ${parts.join('; ')}. Правка изменит его везде.`
      : 'Текст пока не стоит ни на одной кнопке.';
  }

  /** The editor title of the promo tab: the name of the text being edited, or a new text. */
  selectedPromoTextTitle(): string {
    const id = this.selectedId();
    if (id == null) return 'Новый промо-текст';
    const promoText = this.selectedPromoText();
    return promoText
      ? `Промо-текст «${this.promoTextLabel(promoText)}», ID ${id}`
      : `Промо-текст, ID ${id}`;
  }

  /** Only the pay text reaches clients; the four other fields are kept but used nowhere now. */
  managerTextSummary(managerText: AdminManagerText): string {
    const unused = [
      managerText.beginText,
      managerText.offerText,
      managerText.reminderText,
      managerText.startText,
    ].filter((value) => value.trim().length > 0).length;
    const unusedNote = unused === 1 ? '1 поле не используется' : `${unused} поля не используются`;
    if (managerText.payText.trim()) {
      return unused ? `оплата · ${unusedNote}` : 'оплата';
    }
    return unused ? `оплата не заполнена · ${unusedNote}` : 'тексты не заполнены';
  }

  /** The example of a manager row is the pay text: the only one clients receive. */
  managerTextPreview(managerText: AdminManagerText): string {
    return this.promoTextPreview(managerText.payText);
  }

  selectedPromoManagerTitle(): string {
    const managerId = this.selectedPromoManagerId();
    return (
      this.promoManagers().find((manager) => manager.id === managerId)?.title ??
      'Выберите менеджера'
    );
  }

  promoAssignmentValue(button: PromoButtonSlot): number | '' {
    const pending = this.pendingAssignment();
    if (
      pending &&
      pending.managerId === this.selectedPromoManagerId() &&
      pending.section === button.section &&
      pending.buttonKey === button.buttonKey
    ) {
      return pending.value;
    }
    return this.promoAssignmentFor(button)?.promoTextId ?? '';
  }

  promoDefaultTextLabel(button: PromoButtonSlot): string {
    const defaultText = this.allPromoTexts().find((text) => text.id === button.defaultPromoTextId);
    return defaultText
      ? this.promoTextLabel(defaultText)
      : `текста на позиции ${button.defaultPromoPosition} нет`;
  }

  promoAssignedTextLabel(button: PromoButtonSlot): string {
    const assignment = this.promoAssignmentFor(button);
    if (!assignment?.promoTextId) {
      return `по умолчанию: ${this.promoDefaultTextLabel(button)}`;
    }
    const text = this.allPromoTexts().find((promoText) => promoText.id === assignment.promoTextId);
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
    const text = this.promoTextForm.controls.text.value.trim();
    const storedLength = storedPromoTextLength(text);
    if (storedLength > PROMO_TEXT_STORED_LIMIT) {
      this.refuseTooLong(
        'Промо-текст слишком длинный',
        `Можно до ${PROMO_TEXT_STORED_LIMIT} знаков, каждый перенос строки считается за ` +
          `${PROMO_LINE_BREAK_STORED}. Сейчас ${storedLength}.`,
      );
      return;
    }
    const request: PromoTextRequest = { text };
    const selectedId = this.selectedId();
    const subject = this.promoTextSubject(selectedId);
    const call =
      selectedId == null
        ? this.deps.api.createPromoText(request)
        : this.deps.api.updatePromoText(selectedId, request);
    const editor = this.promoEditor.capture(selectedId);
    this.saving.set(true);
    this.error.set(null);
    // The list and a failure are settled even after another text, tab or a hidden window: otherwise the
    // row keeps the old text for the next visit, or the failure passes without a word.
    const settled = call.pipe(
      tap({
        next: (saved) => this.applySavedPromoText(saved, selectedId == null),
        error: (err: unknown) => this.reportSaveFailure(err, 'Промо-текст не сохранён', subject),
      }),
    );
    this.scope.write(settled, this.saving).subscribe({
      next: (saved) => {
        this.saving.set(false);
        // Checked before the new id is taken: the form is clean only if nothing was typed since sending.
        const unchanged = this.promoEditor.sameDraft(editor, this.selectedId());
        if (this.promoEditor.sameSelection(editor, this.selectedId()))
          this.selectedId.set(saved.id);
        if (unchanged) this.promoTextForm.markAsPristine();
        this.deps.toast.success('Промо-текст сохранён', `ID ${saved.id}`);
        this.reloadPromoManagement();
      },
      error: () => undefined,
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
    const payLength = letterCount(raw.payText);
    if (payLength > PAY_TEXT_LIMIT) {
      this.refuseTooLong(
        'Текст оплаты слишком длинный',
        `Можно до ${PAY_TEXT_LIMIT} знаков. Сейчас ${payLength}.`,
      );
      return;
    }
    const request: ManagerTextRequest = {
      payText: raw.payText,
      beginText: raw.beginText,
      offerText: raw.offerText,
      reminderText: raw.reminderText,
      startText: raw.startText,
    };
    const subject = this.selectedManagerText()?.managerTitle ?? `ID ${managerId}`;
    const editor = this.managerEditor.capture(managerId);
    this.saving.set(true);
    this.error.set(null);
    // The row takes the saved texts even after another manager, tab or a hidden window: a later visit fills
    // the form from it, and a stale row would bring the old texts back with the next save.
    const settled = this.deps.api.updateManagerText(managerId, request).pipe(
      tap({
        next: (saved) =>
          this.managerTexts.update((items) =>
            items.map((item) => (item.managerId === saved.managerId ? saved : item)),
          ),
        error: (err: unknown) =>
          this.reportSaveFailure(err, 'Тексты менеджера не сохранены', subject),
      }),
    );
    this.scope.write(settled, this.saving).subscribe({
      next: (saved) => {
        this.saving.set(false);
        if (this.managerEditor.sameDraft(editor, this.selectedId())) {
          this.managerTextForm.setValue({
            payText: saved.payText,
            beginText: saved.beginText,
            offerText: saved.offerText,
            reminderText: saved.reminderText,
            startText: saved.startText,
          });
          // The form holds what the server has: only later typing counts as unsaved.
          this.managerTextForm.markAsPristine();
        }
        this.deps.toast.success('Тексты менеджера сохранены', saved.managerTitle);
      },
      error: () => undefined,
    });
  }

  savePromoAssignment(button: PromoButtonSlot, value: string): void {
    if (!this.scope.active() || this.saving()) return;
    const managerId = this.selectedPromoManagerId();
    if (managerId == null) {
      this.error.set('Выберите менеджера для назначения промо-текста.');
      return;
    }
    const promoTextId = value ? Number(value) : null;
    const generation = this.assignmentGeneration;
    const subject = `${this.promoManagerTitle(managerId)}, ${button.sectionTitle} · ${button.buttonLabel}`;
    const pending: PendingAssignment = {
      managerId,
      section: button.section,
      buttonKey: button.buttonKey,
      value: promoTextId ?? '',
    };
    this.pendingAssignment.set(pending);
    this.saving.set(true);
    this.error.set(null);
    const request: Observable<PromoTextAssignment | void> =
      promoTextId != null
        ? this.deps.api.savePromoTextAssignment({
            managerId,
            section: button.section,
            buttonKey: button.buttonKey,
            promoTextId,
          } satisfies PromoTextAssignmentRequest)
        : this.deps.api.resetPromoTextAssignment(managerId, button.section, button.buttonKey);
    // The answer is applied even after another manager, tab or a hidden window: a return to this manager must
    // show the saved choice, and a failure must not pass without a word.
    const settled = request.pipe(
      tap({
        next: (saved) => this.applySavedAssignment(pending, saved || null),
        error: (err: unknown) => this.reportSaveFailure(err, 'Назначение не сохранено', subject),
      }),
      finalize(() => {
        if (this.pendingAssignment() === pending) this.pendingAssignment.set(null);
      }),
    );
    this.scope.write(settled, this.saving).subscribe({
      next: () => {
        this.saving.set(false);
        this.deps.toast.success('Назначение сохранено', subject);
        if (generation === this.assignmentGeneration && managerId === this.selectedPromoManagerId())
          this.reloadPromoManagement();
      },
      error: () => undefined,
    });
  }

  private promoTextMatches(promoText: AdminPromoText, query: string): boolean {
    return [
      String(promoText.id),
      String(promoText.position),
      promoText.text,
      this.promoTextLabel(promoText),
    ].some((value) => value.toLowerCase().includes(query));
  }

  private selectedPromoText(): AdminPromoText | null {
    const id = this.selectedId();
    return id == null ? null : (this.allPromoTexts().find((text) => text.id === id) ?? null);
  }

  private promoUsageParts(promoText: AdminPromoText): string[] {
    const defaults = this.promoButtons()
      .filter((button) => button.defaultPromoTextId === promoText.id)
      .map((button) => `${button.sectionTitle} · ${button.buttonLabel}`);
    const personal = this.promoAssignments()
      .filter((assignment) => assignment.promoTextId === promoText.id)
      .map(
        (assignment) =>
          `${assignment.managerTitle} (${assignment.sectionTitle} · ${assignment.buttonLabel})`,
      );
    return [
      ...(defaults.length ? [`по умолчанию — ${defaults.join(', ')}`] : []),
      ...(SPECIALIST_REVIEW_POSITIONS.has(promoText.position)
        ? ['специалисты — отзывы на проверку']
        : []),
      ...(personal.length ? [`лично — ${personal.join(', ')}`] : []),
    ];
  }

  private promoTextSubject(id: number | null): string {
    if (id == null) return 'новый текст';
    const promoText = this.allPromoTexts().find((text) => text.id === id);
    return promoText ? `«${this.promoTextLabel(promoText)}», ID ${id}` : `ID ${id}`;
  }

  private promoManagerTitle(managerId: number): string {
    return (
      this.promoManagers().find((manager) => manager.id === managerId)?.title ?? `ID ${managerId}`
    );
  }

  private applySavedPromoText(saved: AdminPromoText, created: boolean): void {
    this.allPromoTexts.update((texts) =>
      texts.some((text) => text.id === saved.id)
        ? texts.map((text) => (text.id === saved.id ? { ...text, ...saved } : text))
        : created
          ? [...texts, saved]
          : texts,
    );
  }

  private applySavedAssignment(
    pending: PendingAssignment,
    saved: PromoTextAssignment | null,
  ): void {
    const sameSlot = (assignment: PromoTextAssignment) =>
      assignment.managerId === pending.managerId &&
      assignment.section === pending.section &&
      assignment.buttonKey === pending.buttonKey;
    const assignments = this.promoAssignments();
    const others = assignments.filter((assignment) => !sameSlot(assignment));
    if (pending.value === '') {
      this.promoAssignments.set(others);
      return;
    }
    const button = this.promoButtons().find(
      (slot) => slot.section === pending.section && slot.buttonKey === pending.buttonKey,
    );
    const promoText = this.allPromoTexts().find((text) => text.id === pending.value);
    this.promoAssignments.set([
      ...others,
      {
        id: 0,
        managerTitle: this.promoManagerTitle(pending.managerId),
        sectionTitle: button?.sectionTitle ?? pending.section,
        buttonLabel: button?.buttonLabel ?? pending.buttonKey,
        outputPosition: button?.outputPosition ?? 0,
        promoTextLabel: promoText ? this.promoTextLabel(promoText) : '',
        ...assignments.find(sameSlot),
        ...(saved ?? {}),
        managerId: pending.managerId,
        section: pending.section,
        buttonKey: pending.buttonKey,
        promoTextId: pending.value,
      },
    ]);
  }

  private applyPromoManagement(
    response: PromoTextManagementResponse,
    allowDefaultSelection = true,
  ): void {
    this.allPromoTexts.set(response.texts);
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
  /** Always every text: the table is narrowed here by the applied search, the button lists never are. */
  private reloadPromoManagement(): void {
    const generation = this.assignmentGeneration;
    this.scope.read('texts', this.deps.api.getPromoTextManagement(), this.loading).subscribe({
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
  /** A failed save is never silent: the error line and the toast name the record, even after a switch. */
  private reportSaveFailure(error: unknown, title: string, subject: string): void {
    const reason = apiErrorDetail(error);
    this.error.set(`${title}: ${subject}. ${reason}`);
    this.deps.toast.error(title, `${subject}. ${reason}`);
  }
  /** A text longer than its database column is refused before it is sent. */
  private refuseTooLong(title: string, message: string): void {
    this.error.set(`${title}. ${message}`);
    this.deps.toast.error(title, message);
  }
}
