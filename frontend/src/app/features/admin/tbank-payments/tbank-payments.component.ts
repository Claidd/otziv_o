import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { PaymentsApi } from '../../../core/payments.api';
import type {
  AdminPaymentLinkResponse,
  ManagerPaymentProfileResponse,
  PaymentProfileResponse,
  TbankPaymentStatus
} from '../../../core/payments.api';
import { AdminLayoutComponent } from '../../../shared/admin-layout.component';
import { apiErrorDetail } from '../../../shared/api-error-message';
import { LoadErrorCardComponent } from '../../../shared/load-error-card.component';
import { ToastService } from '../../../shared/toast.service';

type PaymentMetric = {
  label: string;
  value: string | number;
  icon: string;
  tone: 'blue' | 'green' | 'yellow' | 'red' | 'gray';
};

type PaymentStatusFilter = 'all' | 'active' | 'paid' | 'refunded' | 'failed' | 'created';

type StatusFilterOption = {
  key: PaymentStatusFilter;
  label: string;
  icon: string;
};

@Component({
  selector: 'app-tbank-payments',
  imports: [AdminLayoutComponent, DatePipe, DecimalPipe, FormsModule, LoadErrorCardComponent, RouterLink],
  templateUrl: './tbank-payments.component.html',
  styleUrl: './tbank-payments.component.scss'
})
export class TbankPaymentsComponent {
  private readonly paymentsApi = inject(PaymentsApi);
  private readonly toastService = inject(ToastService);

  readonly links = signal<AdminPaymentLinkResponse[]>([]);
  readonly status = signal<TbankPaymentStatus | null>(null);
  readonly profiles = signal<PaymentProfileResponse[]>([]);
  readonly managerProfiles = signal<ManagerPaymentProfileResponse[]>([]);
  readonly profileAssignments = signal<Record<number, number | null>>({});
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly mutatingId = signal<number | null>(null);
  readonly savingProfiles = signal(false);
  readonly copied = signal<string | null>(null);
  readonly search = signal('');
  readonly statusFilter = signal<PaymentStatusFilter>('all');
  readonly dateFrom = signal('');
  readonly dateTo = signal('');

  readonly statusOptions: StatusFilterOption[] = [
    { key: 'all', label: 'Все', icon: 'apps' },
    { key: 'active', label: 'Активные', icon: 'bolt' },
    { key: 'paid', label: 'Оплачены', icon: 'check_circle' },
    { key: 'refunded', label: 'Возвраты', icon: 'assignment_return' },
    { key: 'failed', label: 'Ошибки', icon: 'error' },
    { key: 'created', label: 'Созданы', icon: 'schedule' }
  ];

  readonly filteredLinks = computed(() => {
    const search = this.search().trim().toLowerCase();
    const from = this.dateFrom();
    const to = this.dateTo();
    const filter = this.statusFilter();
    return this.links().filter((link) => {
      return this.matchesSearch(link, search)
        && this.matchesStatusFilter(link, filter)
        && this.matchesDateRange(link, from, to);
    });
  });

  readonly hasFilters = computed(() => {
    return Boolean(this.search().trim())
      || this.statusFilter() !== 'all'
      || Boolean(this.dateFrom())
      || Boolean(this.dateTo());
  });

  readonly metrics = computed<PaymentMetric[]>(() => {
    const links = this.links();
    const filtered = this.filteredLinks();
    const status = this.status();
    const paid = links.filter((link) => this.isPaid(link.status)).length;
    const refunded = links.filter((link) => this.isRefunded(link.status) || link.status === 'CANCELED').length;
    const rejected = links.filter((link) => link.status === 'REJECTED' || link.status === 'FAILED').length;
    return [
      { label: 'Показано', value: `${filtered.length}/${links.length}`, icon: 'filter_list', tone: 'blue' },
      { label: 'Оплачено', value: paid, icon: 'check_circle', tone: 'green' },
      { label: 'Можно вернуть', value: links.filter((link) => link.refundable).length, icon: 'undo', tone: 'yellow' },
      { label: 'Возвращено', value: refunded, icon: 'assignment_return', tone: 'green' },
      { label: 'Ошибки', value: rejected, icon: 'priority_high', tone: rejected ? 'red' : 'gray' },
      { label: 'Режим', value: status?.testMode ? 'Тестовый' : 'Боевой', icon: status?.testMode ? 'science' : 'verified', tone: status?.testMode ? 'yellow' : 'green' }
    ];
  });

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    forkJoin({
      status: this.paymentsApi.getTbankStatus(),
      links: this.paymentsApi.getAdminTbankPaymentLinks(),
      profiles: this.paymentsApi.getAdminTbankPaymentProfiles()
    }).subscribe({
      next: ({ status, links, profiles }) => {
        this.status.set(status);
        this.links.set(links);
        this.applyProfilesState(profiles.profiles, profiles.managers);
        this.loading.set(false);
      },
      error: (err) => {
        const message = apiErrorDetail(err, 'Не удалось загрузить T-Bank платежи');
        this.error.set(message);
        this.loading.set(false);
        this.toastService.error('T-Bank платежи не загрузились', message);
      }
    });
  }

  setSearch(value: string): void {
    this.search.set(value ?? '');
  }

  setStatusFilter(filter: PaymentStatusFilter): void {
    this.statusFilter.set(filter);
  }

  setDateFrom(value: string): void {
    this.dateFrom.set(value ?? '');
  }

  setDateTo(value: string): void {
    this.dateTo.set(value ?? '');
  }

  resetFilters(): void {
    this.search.set('');
    this.statusFilter.set('all');
    this.dateFrom.set('');
    this.dateTo.set('');
  }

  setManagerProfile(managerId: number, value: string | number | null): void {
    const profileId = Number(value);
    this.profileAssignments.update((assignments) => ({
      ...assignments,
      [managerId]: Number.isFinite(profileId) && profileId > 0 ? profileId : null
    }));
  }

  selectedProfileId(manager: ManagerPaymentProfileResponse): number | null {
    return this.profileAssignments()[manager.managerId] ?? manager.paymentProfileId ?? null;
  }

  saveProfileAssignments(): void {
    if (this.savingProfiles()) {
      return;
    }
    const assignments = this.managerProfiles().map((manager) => ({
      managerId: manager.managerId,
      paymentProfileId: this.selectedProfileId(manager)
    }));
    this.savingProfiles.set(true);
    this.paymentsApi.updateAdminTbankPaymentProfileAssignments(assignments).subscribe({
      next: (state) => {
        this.applyProfilesState(state.profiles, state.managers);
        this.savingProfiles.set(false);
        this.toastService.success('Платежные профили сохранены');
      },
      error: (err) => {
        const message = apiErrorDetail(err, 'Не удалось сохранить платежные профили');
        this.savingProfiles.set(false);
        this.toastService.error('Профили не сохранены', message);
      }
    });
  }

  cancel(link: AdminPaymentLinkResponse): void {
    if (!link.refundable || this.mutatingId()) {
      return;
    }

    const confirmed = window.confirm(`Вернуть платеж T-Bank ${link.tbankPaymentId} на сумму ${link.amount} руб.?`);
    if (!confirmed) {
      return;
    }

    this.mutatingId.set(link.id);
    this.paymentsApi.cancelAdminTbankPaymentLink(link.id).subscribe({
      next: (updated) => {
        this.links.update((links) => links.map((item) => item.id === updated.id ? updated : item));
        this.mutatingId.set(null);
        this.toastService.success('Возврат отправлен', `Статус: ${this.statusLabel(updated.status)}`);
      },
      error: (err) => {
        const message = apiErrorDetail(err, 'Не удалось выполнить возврат');
        this.mutatingId.set(null);
        this.toastService.error('Возврат не выполнен', message);
      }
    });
  }

  copy(value: string | null | undefined, key: string): void {
    const text = value?.trim();
    if (!text) {
      return;
    }

    void navigator.clipboard.writeText(text).then(() => {
      this.copied.set(key);
      window.setTimeout(() => {
        if (this.copied() === key) {
          this.copied.set(null);
        }
      }, 1600);
      this.toastService.success('Скопировано');
    });
  }

  statusLabel(status: string): string {
    const labels: Record<string, string> = {
      CREATED: 'Создана',
      INITIATED: 'Открыта форма',
      AUTHORIZED: 'Авторизован',
      TEST_CONFIRMED: 'Тест оплачен',
      CONFIRMED: 'Оплачен',
      REJECTED: 'Отклонен',
      CANCELED: 'Отменен',
      REVERSED: 'Отменен полностью',
      PARTIAL_REVERSED: 'Отменен частично',
      REFUNDED: 'Возвращен полностью',
      PARTIAL_REFUNDED: 'Возвращен частично',
      EXPIRED: 'Истек',
      FAILED: 'Ошибка'
    };
    return labels[status] ?? status;
  }

  statusClass(status: string): string {
    if (status === 'TEST_CONFIRMED' || status === 'CONFIRMED' || status === 'AUTHORIZED') {
      return 'status-pill paid';
    }
    if (this.isRefunded(status) || status === 'CANCELED') {
      return 'status-pill refunded';
    }
    if (status === 'REJECTED' || status === 'FAILED' || status === 'EXPIRED') {
      return 'status-pill failed';
    }
    return 'status-pill neutral';
  }

  paymentTitle(link: AdminPaymentLinkResponse): string {
    return link.companyTitle || link.filialTitle || `Заказ ${link.orderId ?? '-'}`;
  }

  paymentSubtitle(link: AdminPaymentLinkResponse): string {
    const parts = [link.filialTitle, link.description].filter(Boolean);
    return parts.join(' - ') || 'T-Bank';
  }

  isMutating(link: AdminPaymentLinkResponse): boolean {
    return this.mutatingId() === link.id;
  }

  trackLink(_index: number, link: AdminPaymentLinkResponse): number {
    return link.id;
  }

  trackMetric(_index: number, metric: PaymentMetric): string {
    return metric.label;
  }

  trackStatusOption(_index: number, option: StatusFilterOption): string {
    return option.key;
  }

  trackProfile(_index: number, profile: PaymentProfileResponse): number {
    return profile.id;
  }

  trackManagerProfile(_index: number, manager: ManagerPaymentProfileResponse): number {
    return manager.managerId;
  }

  totalAmount(links: AdminPaymentLinkResponse[]): number {
    return links.reduce((sum, link) => sum + Number(link.amount || 0), 0);
  }

  rowMode(link: AdminPaymentLinkResponse): string {
    if (this.isPaid(link.status)) {
      return 'paid';
    }
    if (this.isRefunded(link.status) || link.status === 'CANCELED') {
      return 'refunded';
    }
    if (link.status === 'REJECTED' || link.status === 'FAILED' || link.status === 'EXPIRED') {
      return 'failed';
    }
    return 'neutral';
  }

  private matchesSearch(link: AdminPaymentLinkResponse, search: string): boolean {
    if (!search) {
      return true;
    }
    const haystack = [
      link.companyTitle,
      link.filialTitle,
      link.description,
      link.orderId,
      link.tbankPaymentId,
      link.tbankOrderId,
      link.paymentProfileName,
      link.tbankTerminalKey,
      link.payerEmail,
      link.lastError,
      this.statusLabel(link.status)
    ].join(' ').toLowerCase();
    return haystack.includes(search);
  }

  private matchesStatusFilter(link: AdminPaymentLinkResponse, filter: PaymentStatusFilter): boolean {
    switch (filter) {
      case 'active':
        return link.status === 'CREATED' || link.status === 'INITIATED' || link.status === 'AUTHORIZED';
      case 'paid':
        return this.isPaid(link.status);
      case 'refunded':
        return this.isRefunded(link.status) || link.status === 'CANCELED';
      case 'failed':
        return link.status === 'REJECTED' || link.status === 'FAILED' || link.status === 'EXPIRED';
      case 'created':
        return link.status === 'CREATED';
      default:
        return true;
    }
  }

  private matchesDateRange(link: AdminPaymentLinkResponse, from: string, to: string): boolean {
    const createdAt = new Date(link.createdAt).getTime();
    if (Number.isNaN(createdAt)) {
      return true;
    }
    if (from) {
      const fromTime = new Date(`${from}T00:00:00`).getTime();
      if (!Number.isNaN(fromTime) && createdAt < fromTime) {
        return false;
      }
    }
    if (to) {
      const toTime = new Date(`${to}T23:59:59.999`).getTime();
      if (!Number.isNaN(toTime) && createdAt > toTime) {
        return false;
      }
    }
    return true;
  }

  private applyProfilesState(
    profiles: PaymentProfileResponse[],
    managers: ManagerPaymentProfileResponse[]
  ): void {
    this.profiles.set(profiles ?? []);
    this.managerProfiles.set(managers ?? []);
    this.profileAssignments.set(Object.fromEntries(
      (managers ?? []).map((manager) => [manager.managerId, manager.paymentProfileId ?? null])
    ));
  }

  private isPaid(status: string): boolean {
    return status === 'CONFIRMED' || status === 'TEST_CONFIRMED' || status === 'AUTHORIZED';
  }

  private isRefunded(status: string): boolean {
    return status === 'REFUNDED'
      || status === 'PARTIAL_REFUNDED'
      || status === 'REVERSED'
      || status === 'PARTIAL_REVERSED';
  }
}
