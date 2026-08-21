import { Component, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { CabinetApi, ScoreContractorPaymentSummary, ScoreResponse, ScoreUser } from '../../core/cabinet.api';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';
import { apiErrorDetail } from '../../shared/api-error-message';
import { LoadErrorCardComponent } from '../../shared/load-error-card.component';
import { businessDateIso } from '../../shared/business-date';

type ScoreGroupKey = 'managers' | 'workers' | 'operators' | 'marketologs';

type ScoreSection = {
  key: ScoreGroupKey;
  title: string;
  icon: string;
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
    return this.score()?.groups[section.key] ?? [];
  }

  contractorPaymentFor(user: ScoreUser): ScoreContractorPaymentSummary | null {
    if (!this.score()?.financeVisible || !user.userId) {
      return null;
    }
    const rows = this.score()?.contractorPayments ?? [];
    const userRows = rows.filter(row => row.userId === user.userId);
    if (!userRows.length) {
      return null;
    }
    const expectedRole = this.contractorRoleForUser(user);
    return userRows.find(row => row.role === expectedRole) ?? userRows[0] ?? null;
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
      return 'профиль выключен';
    }
    if (!row.liveEnabled) {
      return 'реквизиты выключены';
    }
    return row.reportingLive ? 'LIVE' : 'тестовый расчёт';
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
