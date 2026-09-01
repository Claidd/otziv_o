import { Component, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { CabinetApi, ScoreContractorPaymentSummary, ScoreResponse, ScoreUser } from '../../core/cabinet.api';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';
import { apiErrorDetail } from '../../shared/api-error-message';
import { LoadErrorCardComponent } from '../../shared/load-error-card.component';
import { businessDateIso } from '../../shared/business-date';
import {
  scoreMonthLabel,
  scoreOrphanPayments,
  scoreOutstandingDebtKopecks,
  scoreOutstandingReservedKopecks,
  scorePaymentsForUser
} from './score-finance.helpers';

type ScoreGroupKey = 'managers' | 'workers' | 'operators' | 'marketologs' | 'savedBalances';

type ScoreSection = {
  key: ScoreGroupKey;
  title: string;
  icon: string;
  financialOnly?: boolean;
};

@Component({
  selector: 'app-score',
  imports: [AdminLayoutComponent, FormsModule, LoadErrorCardComponent, RouterLink],
  templateUrl: './score.component.html',
  styleUrl: './score.component.scss'
})
export class ScoreComponent {
  readonly selectedDate = signal(this.todayIso());
  readonly score = signal<ScoreResponse | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);

  readonly sections: ScoreSection[] = [
    { key: 'managers', title: 'Менеджеры', icon: 'groups' },
    { key: 'workers', title: 'Работники', icon: 'engineering' },
    { key: 'operators', title: 'Операторы', icon: 'support_agent' },
    { key: 'marketologs', title: 'Маркетологи', icon: 'campaign' }
  ];
  private readonly savedBalancesSection: ScoreSection = {
    key: 'savedBalances',
    title: 'Сохранённые остатки',
    icon: 'account_balance_wallet',
    financialOnly: true
  };

  constructor(private readonly cabinetApi: CabinetApi) {
    this.load();
  }

  load(forceRefresh = false): void {
    this.loading.set(true);
    this.error.set(null);

    this.cabinetApi.getScore(this.selectedDate(), { forceRefresh }).subscribe({
      next: (response) => {
        this.score.set(response);
        this.loading.set(false);
      },
      error: (error) => {
        this.error.set(apiErrorDetail(error, 'Обновите данные через пару минут или обратитесь к администратору.'));
        this.loading.set(false);
      }
    });
  }

  refresh(): void {
    this.load(true);
  }

  selectDate(date: string): void {
    this.selectedDate.set(date);
    this.load();
  }

  users(section: ScoreSection): ScoreUser[] {
    if (section.key === 'savedBalances') {
      return this.savedContractorUsers();
    }
    return this.score()?.groups[section.key] ?? [];
  }

  visibleSections(): ScoreSection[] {
    return this.savedContractorPayments().length
      ? [...this.sections, this.savedBalancesSection]
      : this.sections;
  }

  savedContractorPayments(): ScoreContractorPaymentSummary[] {
    const response = this.score();
    if (!response?.financeVisible) {
      return [];
    }
    const groups = response.groups;
    const visibleUserIds = [
      ...groups.managers,
      ...groups.workers,
      ...groups.operators,
      ...groups.marketologs
    ]
      .map((user) => user.userId)
      .filter((userId): userId is number => userId != null);
    return scoreOrphanPayments(response.contractorPayments ?? [], visibleUserIds);
  }

  private savedContractorUsers(): ScoreUser[] {
    const users = new Map<number, ScoreUser>();
    for (const payment of this.savedContractorPayments()) {
      if (!users.has(payment.userId)) {
        users.set(payment.userId, {
          fio: payment.fio || `Профиль #${payment.profileId}`,
          role: 'Сохранённый финансовый профиль',
          userId: payment.userId
        });
      }
    }
    return [...users.values()];
  }

  contractorPaymentsFor(user: ScoreUser): ScoreContractorPaymentSummary[] {
    if (!this.score()?.financeVisible || !user.userId) {
      return [];
    }
    const rows = this.score()?.contractorPayments ?? [];
    return scorePaymentsForUser(rows, user.userId, this.contractorRoleForUser(user));
  }

  rows(section: ScoreSection, user: ScoreUser): Array<{ label: string; value: string }> {
    if (section.key === 'managers') {
      return [
        { label: 'Заказы', value: this.count(user.order1Month) },
        { label: 'Отзывы', value: this.count(user.review1Month) },
        user.newCompanies != null ? { label: 'Новые компании', value: this.count(user.newCompanies) } : null,
        this.financeRow('Оборот', user.totalSum)
      ].filter(Boolean) as Array<{ label: string; value: string }>;
    }

    if (section.key === 'workers') {
      return [
        { label: 'Заказы', value: this.count(user.order1Month) },
        { label: 'Отзывы', value: this.count(user.review1Month) },
        { label: 'Выгул', value: this.count(user.inVigul) },
        { label: 'Публикация', value: this.count(user.inPublish) }
      ].filter(Boolean) as Array<{ label: string; value: string }>;
    }

    return [
      this.financeRow('Начислено', user.salary),
      { label: 'Новые', value: this.count(user.leadsNew) },
      { label: 'В работе', value: this.count(user.leadsInWork) },
      { label: 'Конверсия', value: `${user.percentInWork || 0}%` }
    ].filter(Boolean) as Array<{ label: string; value: string }>;
  }

  imageUrl(imageId?: number | null): string {
    return this.cabinetApi.imageUrl(imageId);
  }

  userTrack(user: ScoreUser): string {
    return `${user.role}-${user.userId || user.fio}`;
  }


  private contractorRoleForUser(user: ScoreUser): string | null {
    if (user.role === 'ROLE_MANAGER') {
      return 'MANAGER';
    }
    if (user.role === 'ROLE_WORKER') {
      return 'SPECIALIST';
    }
    return null;
  }

  contractorStatusLabel(row: ScoreContractorPaymentSummary): string {
    if (!row.profileEnabled) {
      return 'выключен · остаток сохранён';
    }
    if (!row.liveEnabled) {
      return 'реквизиты выключены · учёт сохранён';
    }
    return row.reportingLive ? 'LIVE' : 'тестовый расчёт';
  }

  contractorRoleLabel(row: ScoreContractorPaymentSummary): string {
    return row.role === 'MANAGER' ? 'менеджер' : 'специалист';
  }

  selectedMonthLabel(): string {
    return scoreMonthLabel(this.score()?.date || this.selectedDate());
  }

  outstandingDebtKopecks(row: ScoreContractorPaymentSummary): number {
    return scoreOutstandingDebtKopecks(row);
  }

  outstandingReservedKopecks(row: ScoreContractorPaymentSummary): number {
    return scoreOutstandingReservedKopecks(row);
  }

  moneyKopecks(value?: number | null): string {
    return `${new Intl.NumberFormat('ru-RU', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2
    }).format((value || 0) / 100)} ₽`;
  }

  private financeRow(label: string, value?: number | null, suffix = ' руб.'): { label: string; value: string } | null {
    if (!this.score()?.financeVisible || value == null) {
      return null;
    }

    return {
      label,
      value: `${new Intl.NumberFormat('ru-RU').format(value || 0)}${suffix}`
    };
  }

  private count(value?: number | null): string {
    return `${this.amount(value)} шт.`;
  }

  private amount(value?: number | null): string {
    return new Intl.NumberFormat('ru-RU').format(value || 0);
  }

  private todayIso(): string {
    return businessDateIso();
  }
}
