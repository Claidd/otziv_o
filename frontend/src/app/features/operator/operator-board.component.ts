import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Observable } from 'rxjs';
import { appEnvironment } from '../../core/app-environment';
import { LeadItem, LeadPage, LeadPerson } from '../../core/leads.api';
import { OperatorApi, OperatorBoard } from '../../core/operator.api';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';
import { ToastService } from '../../shared/toast.service';

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
};

type MobileNavLink = {
  label: string;
  routerLink?: string;
  href?: string;
};

@Component({
  selector: 'app-operator-board',
  imports: [AdminLayoutComponent, DatePipe, FormsModule, RouterLink],
  templateUrl: './operator-board.component.html',
  styleUrl: '../leads/leads-board.component.scss'
})
export class OperatorBoardComponent {
  private readonly operatorApi = inject(OperatorApi);
  private readonly toastService = inject(ToastService);
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
  readonly legacyCategoriesUrl = this.legacyUrl('/categories');
  readonly legacyCompaniesUrl = this.legacyUrl('/companies/company');
  readonly mobileNavLinks: MobileNavLink[] = [
    { label: 'Главная', routerLink: '/' },
    { label: 'Лиды', routerLink: '/leads' },
    { label: 'Оператор', routerLink: '/operator' },
    { label: 'Маркетолог', href: this.legacyUrl('/admin/analyse') },
    { label: 'Менеджер', routerLink: '/manager' },
    { label: 'Специалист', routerLink: '/worker' },
    { label: 'Личный кабинет', routerLink: '/' }
  ];

  readonly board = signal<OperatorBoard | null>(null);
  readonly keyword = signal('');
  readonly pageNumber = signal(0);
  readonly pageSize = signal(10);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly copied = signal<string | null>(null);
  readonly mutationKey = signal<string | null>(null);
  readonly commentDrafts = signal<Record<number, string>>({});
  readonly mobileMenuOpen = signal(false);
  readonly bindModalOpen = signal(false);
  readonly telephoneIdDraft = signal('');
  readonly bindSaving = signal(false);
  readonly bindError = signal<string | null>(null);

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
    return [
      {
        label: 'К выдаче',
        value: String(board?.leads.totalElements ?? 0),
        icon: 'support_agent',
        tone: 'green'
      },
      {
        label: 'Телефон',
        value: board?.telephoneId ? `#${board.telephoneId}` : '-',
        icon: 'phone_iphone',
        tone: 'blue'
      },
      {
        label: 'Оператор',
        value: board?.operatorId ? `#${board.operatorId}` : '-',
        icon: 'badge',
        tone: 'pink'
      },
      {
        label: 'Таймер',
        value: this.timerState(),
        icon: 'timer',
        tone: 'yellow'
      }
    ];
  });

  constructor() {
    this.loadBoard();
  }

  loadBoard(): void {
    this.loading.set(true);
    this.error.set(null);

    this.operatorApi.getBoard({
      keyword: this.keyword(),
      pageNumber: this.pageNumber(),
      pageSize: this.pageSize()
    }).subscribe({
      next: (board) => {
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

  toggleMobileMenu(): void {
    this.mobileMenuOpen.update((open) => !open);
  }

  openBindModal(): void {
    this.bindError.set(null);
    this.bindModalOpen.set(true);
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
        this.toastService.error('Телефон не привязан', message);
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

  companyUrl(lead: LeadItem): string {
    return this.legacyUrl(`/companies/new_company_to_operator/${lead.id}`);
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

  trackMobileLink(_index: number, link: MobileNavLink): string {
    return link.label;
  }

  private requestForAction(lead: LeadItem, action: OperatorAction): Observable<void> {
    switch (action) {
      case 'send':
        return this.operatorApi.markSend(lead.id);
      case 'toWork':
        return this.operatorApi.markToWork(lead.id, this.commentFor(lead));
    }
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

    try {
      await navigator.clipboard.writeText(value);
    } catch {
      const textarea = document.createElement('textarea');
      textarea.value = value;
      textarea.style.position = 'fixed';
      textarea.style.left = '-9999px';
      document.body.append(textarea);
      textarea.select();
      document.execCommand('copy');
      textarea.remove();
    }

    this.copied.set(key);
    this.toastService.success('Скопировано', label);
    window.setTimeout(() => this.copied.set(null), 1400);
  }

  private legacyUrl(path: string): string {
    return `${appEnvironment.legacyBaseUrl}${path}`;
  }

  private errorMessage(err: unknown, fallback: string): string {
    if (typeof err === 'object' && err !== null) {
      const response = err as { error?: unknown; message?: unknown; status?: unknown };
      if (typeof response.error === 'string') {
        return response.error;
      }

      if (typeof response.error === 'object' && response.error !== null) {
        const body = response.error as { detail?: unknown; message?: unknown; title?: unknown };
        if (typeof body.detail === 'string') {
          return body.detail;
        }

        if (typeof body.message === 'string') {
          return body.message;
        }

        if (typeof body.title === 'string') {
          return body.title;
        }
      }

      if (typeof response.message === 'string') {
        return response.message;
      }

      if (typeof response.status === 'number' && response.status === 400) {
        return 'Проверьте ID телефона';
      }
    }

    return fallback;
  }
}
