import { DatePipe } from '@angular/common';
import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Observable } from 'rxjs';
import { AuthService } from '../../core/auth.service';
import { CompanyDeepReportLaunchService } from '../../core/company-deep-report-launch.service';
import { CompanyCreateResult } from '../../core/company-create.api';
import { LeadItem, LeadPage, LeadPerson } from '../../core/leads.api';
import { OperatorApi, OperatorBoard, OperatorBoardSection } from '../../core/operator.api';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';
import { apiErrorMessage } from '../../shared/api-error-message';
import { copyTextToClipboard } from '../../shared/clipboard-copy';
import { CompanyCreateModalComponent } from '../../shared/company-create-modal.component';
import { LoadErrorCardComponent } from '../../shared/load-error-card.component';
import { MobileBottomPagerComponent } from '../../shared/mobile/mobile-bottom-pager.component';
import { MobileNavIntentService } from '../../shared/mobile/mobile-nav-intent.service';
import { MobileStatusSheetComponent } from '../../shared/mobile/mobile-status-sheet.component';
import {
  MobileStatusItem,
  MobileStatusSliderComponent
} from '../../shared/mobile/mobile-status-slider.component';
import { ToastService } from '../../shared/toast.service';
import {
  DEFAULT_OPERATOR_MOBILE_SECTION,
  OPERATOR_MOBILE_SECTIONS
} from './operator-board.mobile';

type OperatorAction = 'send' | 'toWork';

type OperatorPromoItem = {
  label: string;
  text: string;
  icon: string;
};

type OperatorMetric = {
  label: string;
  value: string;
  icon: string;
  tone: 'green' | 'blue' | 'yellow' | 'pink';
  section?: OperatorBoardSection;
  action?: 'phones';
};

type OperatorSectionTab = {
  key: OperatorBoardSection;
  label: string;
  value: string;
  icon: string;
  tone: 'green' | 'blue';
};

@Component({
  selector: 'app-operator-board',
  imports: [
    AdminLayoutComponent,
    CompanyCreateModalComponent,
    DatePipe,
    FormsModule,
    LoadErrorCardComponent,
    MobileBottomPagerComponent,
    MobileStatusSheetComponent,
    MobileStatusSliderComponent,
    RouterLink
  ],
  templateUrl: './operator-board.component.html',
  styleUrl: '../leads/leads-board.component.scss'
})
export class OperatorBoardComponent {
  private readonly operatorApi = inject(OperatorApi);
  private readonly auth = inject(AuthService);
  private readonly toastService = inject(ToastService);
  private readonly companyDeepReportLaunch = inject(CompanyDeepReportLaunchService);
  private readonly router = inject(Router);
  private readonly mobileNavIntent = inject(MobileNavIntentService);
  private lastMobileNavIntentStamp = 0;
  private readonly emptyPage: LeadPage = {
    content: [],
    pageNumber: 0,
    pageSize: 10,
    totalElements: 0,
    totalPages: 0,
    first: true,
    last: true
  };

  readonly pageSizeOptions = [1, 5, 10];
  readonly phonesRoute = '/admin/dictionaries/phones';
  readonly board = signal<OperatorBoard | null>(null);
  readonly activeSection = signal<OperatorBoardSection>('queue');
  readonly keyword = signal('');
  readonly pageNumber = signal(0);
  readonly pageSize = signal(10);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly copied = signal<string | null>(null);
  readonly mutationKey = signal<string | null>(null);
  readonly commentDrafts = signal<Record<number, string>>({});
  readonly mobileStatusSheetOpen = signal(false);
  readonly bindModalOpen = signal(false);
  readonly telephoneIdDraft = signal('');
  readonly bindSaving = signal(false);
  readonly bindError = signal<string | null>(null);
  readonly companyCreateLead = signal<LeadItem | null>(null);

  readonly canManagePhones = computed(() => this.auth.hasAnyRealmRole(['ADMIN', 'OWNER']));
  readonly currentPage = computed(() => this.board()?.leads ?? this.emptyPage);
  readonly currentLeads = computed(() => this.currentPage().content);
  readonly emptyMessage = computed(() => {
    const board = this.board();

    if (!board) {
      return 'Доска еще не загружена';
    }

    if (board.requireDeviceId) {
      return 'Укажи ID телефона, чтобы привязать рабочее устройство.';
    }

    if (this.activeSection() === 'sent') {
      return 'Сейчас нет отправленных лидов.';
    }

    if (this.keyword().trim()) {
      return 'По этому номеру ничего не найдено.';
    }

    if (!board.timerExpired && !this.keyword().trim()) {
      return 'Телефон на паузе после лимита отправок.';
    }

    return 'Сейчас нет лидов для выдачи.';
  });
  readonly promoItems = computed<OperatorPromoItem[]>(() => {
    const text = this.board()?.text;
    return [
      { label: 'начало', text: text?.beginText ?? '', icon: 'waving_hand' },
      { label: 'оффер', text: text?.offerText ?? '', icon: 'sell' },
      { label: 'повтор', text: text?.offer2Text ?? '', icon: 'history' },
      { label: 'старт', text: text?.startText ?? '', icon: 'rocket_launch' }
    ];
  });
  readonly metrics = computed<OperatorMetric[]>(() => {
    const board = this.board();
    const metrics: OperatorMetric[] = [
      {
        label: 'К выдаче',
        value: String(board?.queueTotal ?? 0),
        icon: 'support_agent',
        tone: 'green',
        section: 'queue'
      },
      {
        label: 'Отправленные',
        value: String(board?.sentTotal ?? 0),
        icon: 'outgoing_mail',
        tone: 'blue',
        section: 'sent'
      }
    ];

    if (this.canManagePhones()) {
      metrics.push({
        label: 'Телефон',
        value: board?.telephoneId ? `#${board.telephoneId}` : '-',
        icon: 'phone_iphone',
        tone: 'blue',
        action: 'phones'
      });
    }

    metrics.push({
        label: 'Таймер',
        value: this.timerState(),
        icon: 'timer',
        tone: 'yellow'
    });

    return metrics;
  });
  readonly sectionTabs = computed<OperatorSectionTab[]>(() => {
    const board = this.board();
    return OPERATOR_MOBILE_SECTIONS.map((section) => ({
      ...section,
      value: String(section.key === 'queue' ? board?.queueTotal ?? 0 : board?.sentTotal ?? 0)
    }));
  });
  readonly mobileStatusItems = computed<MobileStatusItem[]>(() => this.sectionTabs().map((tab) => ({
    key: tab.key,
    label: tab.label,
    value: tab.value,
    icon: tab.icon,
    tone: tab.tone
  })));

  constructor() {
    const initialIntent = this.mobileNavIntent.intent();
    if (initialIntent?.tab === 'operator') {
      this.lastMobileNavIntentStamp = initialIntent.stamp;
      if (initialIntent.mode === 'menu') {
        this.mobileStatusSheetOpen.set(true);
      } else {
        this.activeSection.set(DEFAULT_OPERATOR_MOBILE_SECTION);
        this.pageNumber.set(0);
      }
      this.mobileNavIntent.clear(initialIntent.stamp);
    }

    effect(() => {
      const intent = this.mobileNavIntent.intent();
      if (!intent || intent.tab !== 'operator' || intent.stamp === this.lastMobileNavIntentStamp) {
        return;
      }
      this.lastMobileNavIntentStamp = intent.stamp;

      untracked(() => {
        if (intent.mode === 'menu') {
          this.mobileStatusSheetOpen.set(true);
        } else {
          this.mobileStatusSheetOpen.set(false);
          this.activeSection.set(DEFAULT_OPERATOR_MOBILE_SECTION);
          this.pageNumber.set(0);
          this.loadBoard();
        }
        this.mobileNavIntent.clear(intent.stamp);
      });
    });
    this.loadBoard();
  }

  loadBoard(): void {
    this.loading.set(true);
    this.error.set(null);

    this.operatorApi.getBoard({
      keyword: this.keyword(),
      pageNumber: this.pageNumber(),
      pageSize: this.pageSize(),
      section: this.activeSection()
    }).subscribe({
      next: (board) => {
        this.activeSection.set(board.section ?? this.activeSection());
        this.board.set(board);
        this.mergeCommentDrafts(board);
        this.bindModalOpen.set(board.requireDeviceId);
        this.loading.set(false);
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось загрузить доску оператора');
        this.error.set(message);
        this.loading.set(false);
        this.toastService.error('Оператор не загрузился', message);
      }
    });
  }

  search(): void {
    this.pageNumber.set(0);
    this.loadBoard();
  }

  clearSearch(): void {
    this.keyword.set('');
    this.search();
  }

  setSection(section: OperatorBoardSection): void {
    if (this.activeSection() === section) {
      return;
    }

    this.activeSection.set(section);
    this.pageNumber.set(0);
    this.loadBoard();
  }

  selectMobileSection(key: string): void {
    if (!this.sectionTabs().some((section) => section.key === key)) {
      return;
    }

    this.mobileStatusSheetOpen.set(false);
    this.setSection(key as OperatorBoardSection);
  }

  closeMobileStatusSheet(): void {
    this.mobileStatusSheetOpen.set(false);
  }

  changePageSize(value: string | number): void {
    const parsed = Number(value);
    this.pageSize.set(Number.isFinite(parsed) && parsed > 0 ? parsed : 10);
    this.pageNumber.set(0);
    this.loadBoard();
  }

  previousPage(): void {
    if (this.currentPage().first) {
      return;
    }

    this.pageNumber.update((page) => Math.max(page - 1, 0));
    this.loadBoard();
  }

  nextPage(): void {
    if (this.currentPage().last) {
      return;
    }

    this.pageNumber.update((page) => page + 1);
    this.loadBoard();
  }

  openBindModal(): void {
    this.bindError.set(null);
    this.bindModalOpen.set(true);
  }

  handleMetricClick(metric: OperatorMetric): void {
    if (metric.section) {
      this.setSection(metric.section);
      return;
    }

    if (metric.action === 'phones') {
      this.openPhones();
      return;
    }
  }

  closeBindModal(): void {
    if (this.bindSaving()) {
      return;
    }

    this.bindModalOpen.set(false);
    this.bindError.set(null);
  }

  setTelephoneIdDraft(value: unknown): void {
    this.telephoneIdDraft.set(String(value ?? ''));
  }

  saveDeviceToken(): void {
    const telephoneId = Number(this.telephoneIdDraft());

    if (!Number.isFinite(telephoneId) || telephoneId <= 0) {
      this.bindError.set('Введите корректный ID телефона');
      return;
    }

    this.bindSaving.set(true);
    this.bindError.set(null);

    this.operatorApi.bindDevice(telephoneId).subscribe({
      next: () => {
        this.bindSaving.set(false);
        this.bindModalOpen.set(false);
        this.toastService.success('Телефон привязан', `ID ${telephoneId}`);
        this.loadBoard();
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось привязать телефон');
        this.bindError.set(message);
        this.bindSaving.set(false);
        this.toastService.error(this.deviceTokenErrorTitle(message), message);
      }
    });
  }

  commentFor(lead: LeadItem): string {
    return this.commentDrafts()[lead.id] ?? lead.commentsLead ?? '';
  }

  setComment(lead: LeadItem, value: string): void {
    this.commentDrafts.update((drafts) => ({
      ...drafts,
      [lead.id]: value
    }));
  }

  assigneeName(person?: LeadPerson): string {
    return person?.fio || person?.username || (person?.id ? `ID ${person.id}` : '-');
  }

  whatsappUrl(lead: LeadItem): string {
    return `https://wa.me/${lead.telephoneLead}`;
  }

  openCompanyCreate(lead: LeadItem): void {
    this.companyCreateLead.set(lead);
  }

  closeCompanyCreate(): void {
    this.companyCreateLead.set(null);
  }

  handleCompanyCreated(result: CompanyCreateResult): void {
    this.companyCreateLead.set(null);
    this.toastService.success('Компания создана', result.title || `#${result.companyId ?? ''}`);
    this.companyDeepReportLaunch.handleCompanyCreated(result);
    this.loadBoard();
  }

  actionLabel(action: OperatorAction): string {
    return {
      send: 'отправил',
      toWork: 'передать'
    }[action];
  }

  actionIcon(action: OperatorAction): string {
    return {
      send: 'send',
      toWork: 'swap_horiz'
    }[action];
  }

  actionsForLead(lead: LeadItem): OperatorAction[] {
    return this.isSentLead(lead) ? ['toWork'] : ['send', 'toWork'];
  }

  runAction(lead: LeadItem, action: OperatorAction): void {
    const key = `${lead.id}:${action}`;
    this.mutationKey.set(key);
    this.error.set(null);

    this.requestForAction(lead, action).subscribe({
      next: () => {
        this.mutationKey.set(null);
        this.toastService.success('Статус лида обновлен', `Лид #${lead.id}: ${this.actionLabel(action)}`);
        this.loadBoard();
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось изменить статус');
        this.error.set(message);
        this.mutationKey.set(null);
        this.toastService.error('Статус не изменился', message);
      }
    });
  }

  isMutating(lead: LeadItem, action: OperatorAction): boolean {
    return this.mutationKey() === `${lead.id}:${action}`;
  }

  async copyPhone(lead: LeadItem): Promise<void> {
    await this.copyText(lead.telephoneLead, 'телефон', `phone:${lead.id}`);
  }

  async copyPromo(item: OperatorPromoItem): Promise<void> {
    await this.copyText(item.text, item.label, `promo:${item.label}`);
  }

  trackLead(_index: number, lead: LeadItem): number {
    return lead.id;
  }

  trackPromo(_index: number, item: OperatorPromoItem): string {
    return item.label;
  }

  trackMetric(_index: number, metric: OperatorMetric): string {
    return metric.label;
  }

  trackSection(_index: number, tab: OperatorSectionTab): string {
    return tab.key;
  }

  private requestForAction(lead: LeadItem, action: OperatorAction): Observable<void> {
    switch (action) {
      case 'send':
        return this.operatorApi.markSend(lead.id);
      case 'toWork':
        return this.operatorApi.markToWork(lead.id, this.commentFor(lead));
    }
  }

  private openPhones(): void {
    const telephoneId = this.board()?.telephoneId;
    void this.router.navigate([this.phonesRoute], {
      queryParams: telephoneId ? { phoneId: telephoneId } : undefined
    });
  }

  private timerState(): string {
    const board = this.board();

    if (!board) {
      return '-';
    }

    if (board.requireDeviceId) {
      return 'нужен ID';
    }

    return board.timerExpired ? 'готов' : 'пауза';
  }

  private isSentLead(lead: LeadItem): boolean {
    return lead.lidStatus === 'Отправленный' || lead.lidStatus === 'К рассылке';
  }

  private mergeCommentDrafts(board: OperatorBoard): void {
    this.commentDrafts.update((drafts) => {
      const next = { ...drafts };
      board.leads.content.forEach((lead) => {
        if (next[lead.id] === undefined) {
          next[lead.id] = lead.commentsLead ?? '';
        }
      });
      return next;
    });
  }

  private async copyText(value: string, label: string, key: string): Promise<void> {
    if (!value) {
      this.toastService.info('Нечего копировать', `Поле "${label}" пустое`);
      return;
    }

    if (!await copyTextToClipboard(value)) {
      this.toastService.error('Не скопировано', 'Браузер не дал доступ к буферу обмена');
      return;
    }

    this.copied.set(key);
    this.toastService.success('Скопировано', label);
    window.setTimeout(() => this.copied.set(null), 1400);
  }

  private errorMessage(err: unknown, fallback: string): string {
    return apiErrorMessage(err, fallback);
  }

  private deviceTokenErrorTitle(message: string): string {
    return message.toLowerCase().includes('токен') ? 'Токен уже есть' : 'Телефон не привязан';
  }
}
