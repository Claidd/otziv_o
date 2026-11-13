import { HttpErrorResponse } from '@angular/common/http';
import { Component, EventEmitter, Input, OnInit, Output, computed, inject, signal } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { firstValueFrom, type Observable } from 'rxjs';
import { AuthService } from '../../../core/auth.service';
import type { CommonInvoiceDetailsResponse } from '../../../core/common-billing.api';
import {
  CommonManualPaymentAttributionApi,
  type CommonManualPaymentAttributionRequest,
  type CommonManualPaymentMode,
  type CommonManualPaymentOptions,
  type CommonManualPaymentOtherRecipient,
  type CommonManualPaymentRecipientCandidate
} from '../../../core/common-manual-payment-attribution.api';
import {
  isManualPaymentTaskRecipient,
  isRetryablePaymentRouteError,
  manualPaymentRecipientEffect,
  manualPaymentRouteErrorMessage
} from '../../../shared/manual-payment-routing';
import {
  commonManualPaymentDraftAfterRouteRefresh,
  commonManualPaymentInitialRecipientKey
} from '../../../shared/common-manual-payment-route-refresh';
import { businessDateTimeStamp } from '../../../shared/business-date';
import { COMMON_INVOICE_GUIDE, CommonInvoiceGuideComponent, type GuideSectionContent } from './common-invoice-guide.component';

/** One more order paid by the same bank transfer (an ordinary order only). */
export interface PaymentMarkTransferLine {
  orderId: number;
  amountKopecks: number;
}

export interface PaymentMarkTransfer {
  lines: PaymentMarkTransferLine[];
  reference: string;
  totalRubles: string;
}

/**
 * Where the window reads its recipients and where it sends the mark. A common invoice needs none: the
 * window talks to its own API. An ordinary order gives its source, and the window stays the same.
 */
export interface PaymentMarkSource {
  options(): Observable<CommonManualPaymentOptions>;
  otherRecipients(): Observable<{ recipients: CommonManualPaymentOtherRecipient[] }>;
  /** Sends the mark; what it resolves with is passed to (settled). */
  confirm(request: CommonManualPaymentAttributionRequest, transfer: PaymentMarkTransfer | null): Observable<unknown>;
  /** When present, one transfer may pay several orders: checks that the order can join it. */
  transferLine?(orderId: number, recipientProfileId: number): Observable<PaymentMarkTransferLine>;
  /**
   * When present, a mark that names the owner is always a request for the owner's confirmation, whoever
   * presses the button; the text says what happens after the button. A common invoice has none: there the
   * owner and an administrator credit the owner at once.
   */
  ownerApprovalConsequence?: string;
}

/** The manual opened by «Инструкция». */
export interface PaymentMarkGuide {
  sections: readonly GuideSectionContent[];
  section: string;
  heading: string;
  intro: string;
}

const COMMON_INVOICE_PAYMENT_GUIDE: PaymentMarkGuide = {
  sections: COMMON_INVOICE_GUIDE,
  section: 'payment',
  heading: 'Работа с общим счётом',
  intro: 'Что означает каждый блок панели и когда нажимать каждую кнопку.'
};

export interface CommonManualPaymentDraftRow {
  rowKey: string;
  recipientKey: string;
  amountRubles: string;
}

export function commonManualPaymentRublesToKopecks(raw: string): number | null {
  const clean = (raw ?? '').trim().replace(/\s+/g, '').replace(',', '.');
  if (!/^\d+(?:\.\d{1,2})?$/.test(clean)) {
    return null;
  }
  const [rubles, fraction = ''] = clean.split('.');
  const result = Number(rubles) * 100 + Number(fraction.padEnd(2, '0'));
  return Number.isSafeInteger(result) && result > 0 ? result : null;
}

export function commonManualPaymentTotalKopecks(rows: readonly CommonManualPaymentDraftRow[]): number | null {
  let total = 0;
  for (const row of rows) {
    const amount = commonManualPaymentRublesToKopecks(row.amountRubles);
    if (amount == null) {
      return null;
    }
    total += amount;
    if (!Number.isSafeInteger(total)) {
      return null;
    }
  }
  return total;
}

/**
 * The audit trail always needs a reason. When the manager leaves the comment empty,
 * the reason states exactly what the chosen recipients mean.
 */
export function commonManualPaymentDefaultReason(
  selected: readonly Pick<CommonManualPaymentRecipientCandidate, 'originalRecipient'>[]
): string {
  if (selected.length > 1) {
    return 'Оплата распределена между несколькими получателями';
  }
  return selected[0]?.originalRecipient
    ? 'Оплата поступила получателю из счёта'
    : 'Клиент перевёл деньги не получателю из счёта';
}

/**
 * The invoice's own people: its recipient, the owner and participants of its orders. Everyone else
 * is chosen through the separate «+» button, for one receipt only.
 */
export function isCommonInvoiceOwnRecipient(candidate: CommonManualPaymentRecipientCandidate): boolean {
  return candidate.originalRecipient
    || candidate.currentParticipant
    || candidate.recipientType === 'OWNER'
    || candidate.key === 'OWNER'
    || isManualPaymentTaskRecipient(candidate);
}

@Component({
  selector: 'app-common-manual-payment-attribution-modal',
  imports: [FormsModule, NgTemplateOutlet, CommonInvoiceGuideComponent],
  templateUrl: './common-manual-payment-attribution-modal.component.html',
  styleUrl: '../../../shared/payment-mark-modal.scss'
})
export class CommonManualPaymentAttributionModalComponent implements OnInit {
  private readonly api = inject(CommonManualPaymentAttributionApi);
  private readonly auth = inject(AuthService);

  @Input() invoiceId = 0;
  @Input() mode: CommonManualPaymentMode = 'STANDARD';
  @Input() invoiceTitle = '';
  /** An ordinary order: its recipients and its way of saving. Without it the window serves a common invoice. */
  @Input() source: PaymentMarkSource | null = null;
  /** The line above the title; «Общий счёт №…» when empty. */
  @Input() kicker = '';
  @Input() guide: PaymentMarkGuide = COMMON_INVOICE_PAYMENT_GUIDE;
  /** Splitting one receipt between several people exists for a common invoice only. */
  @Input() splitAllowed = true;

  @Output() readonly closed = new EventEmitter<void>();
  @Output() readonly completed = new EventEmitter<CommonInvoiceDetailsResponse>();
  /** With a source: what its confirm resolved with. */
  @Output() readonly settled = new EventEmitter<unknown>();

  readonly options = signal<CommonManualPaymentOptions | null>(null);
  readonly rows = signal<CommonManualPaymentDraftRow[]>([]);
  /** Several people received parts of one transfer: a rare case behind its own link. */
  readonly splitMode = signal(false);
  readonly reason = signal('');
  readonly loading = signal(true);
  readonly saving = signal(false);
  readonly error = signal('');
  /** People outside the invoice, loaded when the «+» picker is opened for the first time. */
  readonly others = signal<CommonManualPaymentOtherRecipient[] | null>(null);
  readonly othersLoading = signal(false);
  readonly othersError = signal('');
  readonly pickerOpen = signal(false);
  /** The manual of the common invoice, opened at «Оплата». */
  readonly guideOpen = signal(false);
  readonly pickerQuery = signal('');
  /** Recipients added through «+»: they count for this receipt only, the invoice keeps its recipient. */
  readonly oneOff = signal<CommonManualPaymentOtherRecipient[]>([]);
  /** One bank transfer that paid several orders of the same recipient (a source with transferLine only). */
  readonly transferOpen = signal(false);
  readonly transferLines = signal<PaymentMarkTransferLine[]>([]);
  readonly transferOrderId = signal('');
  readonly transferReference = signal('');
  readonly transferRubles = signal('');

  private idempotencyKey = this.newKey();
  // Business-zone time: the server reads it as its own clock, whatever the device is set to.
  private readonly effectiveAt = businessDateTimeStamp();
  readonly isTaskRecipient = isManualPaymentTaskRecipient;
  readonly recipientEffect = manualPaymentRecipientEffect;

  readonly title = computed(() => this.mode === 'TBANK_FALLBACK'
    ? 'Клиент уже оплатил'
    : this.mode === 'TBANK_ROUTE_FALLBACK'
      ? 'Клиент оплатил не по ссылке'
      : 'Отметить оплату');
  readonly reconcilesCurrentTbank = computed(() => this.mode === 'TBANK_ROUTE_FALLBACK');
  /**
   * The owner is among the recipients. For an invoice by a bank link the server offers the owner only where
   * the bank profile lets the owner take card transfers; a payment by the link itself is marked by the bank.
   */
  readonly ownerOffered = computed(() => (this.options()?.candidates ?? [])
    .some(candidate => candidate.recipientType === 'OWNER' || candidate.key === 'OWNER'));
  readonly totalKopecks = computed(() => commonManualPaymentTotalKopecks(this.rows()));
  readonly differenceKopecks = computed(() => {
    const options = this.options();
    const total = this.totalKopecks();
    return options && total != null ? total - options.remainingKopecks : null;
  });
  readonly selectedKey = computed(() => this.rows()[0]?.recipientKey ?? '');
  /** The list a manager chooses from: the invoice's own people and those added through «+». */
  readonly listedCandidates = computed(() => {
    const candidates = this.options()?.candidates ?? [];
    const own = candidates.filter(isCommonInvoiceOwnRecipient);
    const listed = own.length ? own : candidates;
    const keys = new Set(listed.map(candidate => candidate.key));
    return [...listed, ...this.oneOff().map(item => item.candidate).filter(candidate => !keys.has(candidate.key))];
  });
  readonly pickerResults = computed(() => {
    const query = this.pickerQuery().trim().toLocaleLowerCase('ru');
    const listed = new Set(this.listedCandidates().map(candidate => candidate.key));
    return (this.others() ?? [])
      .filter(item => !listed.has(item.candidate.key))
      .filter(item => !query || item.candidate.label.toLocaleLowerCase('ru').includes(query));
  });
  readonly selectedCandidates = computed(() => this.rows()
    .map(row => this.candidate(row.recipientKey))
    .filter((candidate): candidate is CommonManualPaymentRecipientCandidate => candidate != null));
  readonly everyRowHasRecipient = computed(() => this.rows().length > 0
    && this.selectedCandidates().length === this.rows().length);
  /** The server lets only the owner or an administrator credit money to the owner. */
  readonly ownerNeedsApproval = computed(() => {
    this.auth.tokenParsed();
    return (Boolean(this.source?.ownerApprovalConsequence) || !this.auth.hasAnyRealmRole(['ADMIN', 'OWNER']))
      && this.selectedCandidates().some(candidate => candidate.recipientType === 'OWNER' || candidate.key === 'OWNER');
  });
  /** A transfer of several orders is credited to one contractor profile. */
  readonly transferRecipientProfileId = computed(() => {
    const selected = this.selectedCandidates();
    return this.source?.transferLine && !this.splitMode() && selected.length === 1
      ? selected[0].recipientProfileId ?? null
      : null;
  });
  readonly canSubmit = computed(() => Boolean(
    this.options()
      && this.options()!.remainingKopecks > 0
      && this.everyRowHasRecipient()
      && this.totalKopecks() === this.options()!.remainingKopecks
      && (!this.transferLines().length || (this.transferReference().trim() && this.transferRubles().trim()))
      && !this.loading()
      && !this.saving()
  ));
  readonly submitLabel = computed(() => {
    if (this.saving()) {
      return this.reconcilesCurrentTbank() ? 'Сверяем банковскую оплату и сохраняем…' : 'Сохраняем…';
    }
    return this.ownerNeedsApproval() ? 'Отправить владельцу на подтверждение' : 'Оплачен — закрыть счёт';
  });
  /** What the button will do, in place of the former acknowledgement checkboxes. */
  readonly consequence = computed(() => {
    const selected = this.selectedCandidates();
    if (!this.everyRowHasRecipient()) {
      return 'Выберите, кому пришли деньги.';
    }
    if (this.ownerNeedsApproval()) {
      return this.source?.ownerApprovalConsequence
        ?? 'Поступление владельцу подтверждает владелец или администратор. Счёт закроется после его подтверждения.';
    }
    const effect = (selected.length === 1
      ? this.recipientEffect(selected[0])
      : 'Суммы будут учтены у выбранных получателей; их лимиты и резервы обновятся.').trim().replace(/[.!?…]*$/u, '.');
    // Money went past the invoice recipient: the server cancels that person's reserve and credits the actual one.
    const release = selected.some(candidate => !candidate.originalRecipient)
      && (this.options()?.candidates ?? []).some(candidate => candidate.originalRecipient && candidate.recipientType !== 'OWNER')
      ? ' Резерв получателя из счёта при этом снимется.'
      : '';
    return `Счёт закроется сразу, второго подтверждения не будет. ${effect}${release} Если ошиблись получателем, его исправит администратор.`;
  });

  ngOnInit(): void {
    void this.load();
  }

  async decideOwnerReport(accept: boolean): Promise<void> {
    const report = this.options()?.ownerReport;
    if (!report?.canDecide || this.saving()) return;
    this.saving.set(true);
    this.error.set('');
    try {
      if (accept) {
        const details = await firstValueFrom(this.api.confirm(this.invoiceId,
          report.attentionMode ? 'TBANK_FALLBACK' : 'STANDARD', report.request));
        this.completed.emit(details);
      } else {
        await firstValueFrom(this.api.rejectOwnerReport(this.invoiceId, report.id));
        await this.load();
      }
    } catch (error) {
      this.error.set(error instanceof HttpErrorResponse ? error.error?.message || 'Не удалось сохранить решение' : 'Не удалось сохранить решение');
    } finally {
      this.saving.set(false);
    }
  }

  async load(preserveDraft = false): Promise<void> {
    const draftReason = this.reason();
    this.loading.set(true);
    this.error.set('');
    try {
      const options = await firstValueFrom(this.source ? this.source.options() : this.api.options(this.invoiceId));
      // The recipient of a bank link is the bank: nobody of the list is «в счёте», the owner included.
      const candidates = (options.candidates ?? []).map(candidate => this.reconcilesCurrentTbank() && candidate.originalRecipient
        ? { ...candidate, originalRecipient: false } : candidate);
      this.options.set({
        ...options,
        candidates,
        history: options.history ?? []
      });
      const refreshedDraft = commonManualPaymentDraftAfterRouteRefresh(
        { ...options, candidates },
        preserveDraft ? draftReason : '',
        () => this.newKey()
      );
      // First opening of an invoice with a card number: its recipient is already chosen.
      // After a route change, and for a bank link, the choice stays explicit.
      const initialKey = options.initialRecipientKey
        ?? (!preserveDraft && this.mode === 'STANDARD'
          ? commonManualPaymentInitialRecipientKey({ ...options, candidates })
          : '');
      this.rows.set(refreshedDraft.rows.map(row => ({ ...row, recipientKey: initialKey })));
      this.splitMode.set(false);
      this.oneOff.set([]);
      this.others.set(null);
      this.pickerOpen.set(false);
      this.transferLines.set([]);
      // A report waiting for the owner may name a one-off recipient: load the names to show it.
      const known = new Set(candidates.map(candidate => candidate.key));
      if ((options.ownerReport?.request.attributions ?? []).some(line => !known.has(line.recipientKey))) {
        void this.loadOthers();
      }
      this.reason.set(refreshedDraft.reason);
    } catch (error) {
      this.options.set(null);
      this.error.set(manualPaymentRouteErrorMessage(error, this.errorMessage(error,
        this.source ? 'Не удалось загрузить получателей оплаты.' : 'Не удалось загрузить получателей общего счёта.')));
    } finally {
      this.loading.set(false);
    }
  }

  selectRecipient(recipientKey: string): void {
    this.updateRecipient(0, recipientKey);
    this.error.set('');
  }

  startSplit(): void {
    this.splitMode.set(true);
  }

  /** Back to one recipient for the whole amount. */
  cancelSplit(): void {
    const options = this.options();
    const first = this.rows()[0];
    if (!options || !first) {
      return;
    }
    this.rows.set([{
      ...first,
      amountRubles: (options.remainingKopecks / 100).toFixed(2).replace('.', ',')
    }]);
    this.splitMode.set(false);
  }

  /** Opens the list of people outside the invoice; it is loaded once per window. */
  async openPicker(): Promise<void> {
    this.pickerOpen.set(true);
    this.pickerQuery.set('');
    if (this.others() == null && !this.othersLoading()) {
      await this.loadOthers();
    }
  }

  async loadOthers(): Promise<void> {
    this.othersLoading.set(true);
    this.othersError.set('');
    try {
      const response = await firstValueFrom(this.source
        ? this.source.otherRecipients()
        : this.api.otherRecipients(this.invoiceId));
      this.others.set(response.recipients ?? []);
    } catch (error) {
      this.othersError.set(this.errorMessage(error, 'Не удалось загрузить сотрудников.'));
    } finally {
      this.othersLoading.set(false);
    }
  }

  /**
   * Adds a person for this receipt only. With one recipient the newcomer replaces an earlier one-off
   * choice and becomes selected; in a split it joins the list and fills the first empty row.
   */
  pickOther(item: CommonManualPaymentOtherRecipient): void {
    if (this.splitMode()) {
      this.oneOff.update(list => list.some(other => other.candidate.key === item.candidate.key) ? list : [...list, item]);
      const emptyIndex = this.rows().findIndex(row => !row.recipientKey);
      if (emptyIndex >= 0) {
        this.updateRecipient(emptyIndex, item.candidate.key);
      }
    } else {
      this.oneOff.set([item]);
      this.selectRecipient(item.candidate.key);
    }
    this.pickerOpen.set(false);
    this.error.set('');
  }

  isOneOff(candidate: CommonManualPaymentRecipientCandidate): boolean {
    return this.oneOff().some(item => item.candidate.key === candidate.key);
  }

  /** Why this person is offered although the invoice was not issued to them. */
  otherTag(item: CommonManualPaymentOtherRecipient): string {
    return item.previousPerformer ? 'работал с этим клиентом' : 'сотрудник менеджера';
  }

  addRow(): void {
    const options = this.options();
    const listed = this.listedCandidates();
    if (!options || this.rows().length >= Math.min(20, listed.length)) {
      return;
    }
    const used = new Set(this.rows().map(row => row.recipientKey));
    const candidate = listed.find(item => !used.has(item.key));
    if (!candidate) {
      return;
    }
    this.rows.update(rows => [...rows, {
      rowKey: this.newKey(),
      recipientKey: '',
      amountRubles: ''
    }]);
  }

  removeRow(index: number): void {
    if (this.rows().length <= 1) {
      return;
    }
    this.rows.update(rows => rows.filter((_, rowIndex) => rowIndex !== index));
  }

  updateRecipient(index: number, recipientKey: string): void {
    this.rows.update(rows => rows.map((row, rowIndex) =>
      rowIndex === index ? { ...row, recipientKey } : row));
    // Orders of one transfer were checked against the previous recipient.
    this.transferLines.set([]);
  }

  async addTransferOrder(): Promise<void> {
    const options = this.options();
    const profileId = this.transferRecipientProfileId();
    const orderId = Number(this.transferOrderId());
    if (!options || !this.source?.transferLine || profileId == null || this.saving()) {
      return;
    }
    if (!Number.isSafeInteger(orderId) || orderId <= 0 || orderId === options.invoiceId
      || this.transferLines().some(line => line.orderId === orderId)) {
      this.error.set('Введите номер другого заказа.');
      return;
    }
    this.saving.set(true);
    try {
      const line = await firstValueFrom(this.source.transferLine(orderId, profileId));
      this.transferLines.update(lines => [...lines, line]);
      this.transferOrderId.set('');
      this.error.set('');
    } catch (error) {
      this.error.set(this.errorMessage(error, 'Не удалось проверить заказ.'));
    } finally {
      this.saving.set(false);
    }
  }

  removeTransferOrder(orderId: number): void {
    this.transferLines.update(lines => lines.filter(line => line.orderId !== orderId));
  }

  updateAmount(index: number, amountRubles: string): void {
    this.rows.update(rows => rows.map((row, rowIndex) =>
      rowIndex === index ? { ...row, amountRubles } : row));
  }

  candidate(recipientKey: string): CommonManualPaymentRecipientCandidate | null {
    return this.options()?.candidates.find(item => item.key === recipientKey)
      ?? this.oneOff().find(item => item.candidate.key === recipientKey)?.candidate
      ?? this.others()?.find(item => item.candidate.key === recipientKey)?.candidate
      ?? null;
  }

  /** One short line under a recipient: who it is for the accounting. */
  recipientHint(candidate: CommonManualPaymentRecipientCandidate): string {
    if (this.isTaskRecipient(candidate)) {
      const receiver = candidate.taskRecipientName || candidate.label;
      return `Платёжное задание №${candidate.manualPaymentTaskId} · получатель в банке: ${receiver}`;
    }
    if (candidate.recipientType === 'OWNER') {
      return 'Деньги получил владелец';
    }
    const limit = `Доступный лимит: ${this.money(candidate.availableKopecks)}`;
    // A switched-off payment profile only stops new invoices from being issued to this person.
    return candidate.profileEnabled ? limit : `${limit} · новые счета на него сейчас не выставляются, но эту оплату учесть можно`;
  }

  projectedOverrun(row: CommonManualPaymentDraftRow): number {
    const candidate = this.candidate(row.recipientKey);
    const amount = commonManualPaymentRublesToKopecks(row.amountRubles);
    if (!candidate || candidate.recipientType === 'OWNER' || candidate.availableKopecks == null || amount == null) {
      return 0;
    }
    return Math.max(0, amount - candidate.availableKopecks);
  }

  close(): void {
    if (!this.saving()) {
      this.closed.emit();
    }
  }

  async submit(): Promise<void> {
    const options = this.options();
    if (!options || !this.canSubmit()) {
      this.error.set(this.validationMessage());
      return;
    }
    const selected = this.rows().map(row => ({ row, candidate: this.candidate(row.recipientKey) }));
    if (selected.some(item => !item.candidate)) {
      this.error.set('Один из выбранных получателей больше недоступен. Обновите окно.');
      return;
    }
    if (new Set(selected.map(item => item.row.recipientKey)).size !== selected.length) {
      this.error.set('Один получатель не должен повторяться в нескольких строках.');
      return;
    }
    const reason = this.reason().trim() || commonManualPaymentDefaultReason(selected.map(item => item.candidate!));
    if (reason.length > 500) {
      this.error.set('Комментарий не должен превышать 500 символов.');
      return;
    }
    // Pressing the button is the confirmation: the dialog names the recipient and the effect next to it.
    const request: CommonManualPaymentAttributionRequest = {
      idempotencyKey: this.idempotencyKey,
      finalAccountingAcknowledged: true,
      paymentReceived: true,
      effectiveAt: this.effectiveAt,
      reason,
      receiptUrl: '',
      attributions: selected.map(({ row, candidate }) => ({
        rowKey: row.rowKey,
        recipientKey: candidate!.key,
        recipientType: candidate!.recipientType ?? null,
        recipientProfileId: candidate!.recipientProfileId ?? null,
        amountKopecks: commonManualPaymentRublesToKopecks(row.amountRubles)!
      }))
    };

    this.saving.set(true);
    this.error.set('');
    try {
      if (this.source) {
        const transfer = this.transferLines().length
          ? { lines: this.transferLines(), reference: this.transferReference(), totalRubles: this.transferRubles() }
          : null;
        this.settled.emit(await firstValueFrom(this.source.confirm(request, transfer)));
        return;
      }
      const details = await firstValueFrom(this.api.confirm(this.invoiceId, this.mode, request));
      if (details.summary.status !== 'PAID') {
        await this.load();
        return;
      }
      this.completed.emit(details);
    } catch (error) {
      if (isRetryablePaymentRouteError(error)) {
        // The route changed under the dialog: the recipient must be chosen again, nothing is preselected.
        this.idempotencyKey = this.newKey();
        this.rows.set([]);
        await this.load(true);
        if (this.options()) {
          this.error.set(manualPaymentRouteErrorMessage(error, 'Маршрут оплаты изменился. Выберите получателя ещё раз.'));
        }
      } else {
        this.error.set(manualPaymentRouteErrorMessage(error, 'Не удалось подтвердить фактическое поступление.'));
      }
    } finally {
      this.saving.set(false);
    }
  }

  validationMessage(): string {
    const options = this.options();
    const total = this.totalKopecks();
    if (!options) {
      return 'Данные получателей не загружены.';
    }
    if (!this.everyRowHasRecipient()) {
      return 'Выберите, кому пришли деньги.';
    }
    if (total == null) {
      return 'Укажите положительные суммы с точностью не более двух знаков.';
    }
    if (total !== options.remainingKopecks) {
      return 'Сумма по получателям должна точно совпадать с остатком общего счёта.';
    }
    return '';
  }

  money(kopecks: number | null | undefined): string {
    return `${new Intl.NumberFormat('ru-RU', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2
    }).format((kopecks ?? 0) / 100)} ₽`;
  }

  historyDate(value: string): string {
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? value : date.toLocaleString('ru-RU');
  }

  private newKey(): string {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
      return crypto.randomUUID();
    }
    return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 14)}`;
  }

  private errorMessage(error: unknown, fallback: string): string {
    if (error instanceof HttpErrorResponse) {
      const payload = error.error;
      const backend = typeof payload === 'string'
        ? payload
        : payload && typeof payload === 'object'
          ? ('message' in payload && typeof payload.message === 'string'
            ? payload.message
            : 'detail' in payload && typeof payload.detail === 'string'
              ? payload.detail
              : '')
          : '';
      if (backend.trim()) {
        return backend.trim();
      }
    }
    return error instanceof Error && error.message.trim() ? error.message.trim() : fallback;
  }
}
