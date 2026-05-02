import { Component, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { CabinetApi, ScoreResponse, ScoreUser } from '../../core/cabinet.api';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';

type ScoreGroupKey = 'managers' | 'workers' | 'operators' | 'marketologs';

type ScoreSection = {
  key: ScoreGroupKey;
  title: string;
  icon: string;
};

@Component({
  selector: 'app-score',
  imports: [AdminLayoutComponent, FormsModule, RouterLink],
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

  load(): void {
    this.loading.set(true);
    this.error.set(null);

    this.cabinetApi.getScore(this.selectedDate()).subscribe({
      next: (response) => {
        this.score.set(response);
        this.loading.set(false);
      },
      error: (error) => {
        this.error.set(error?.error?.message ?? error?.message ?? 'Не удалось загрузить рейтинг');
        this.loading.set(false);
      }
    });
  }

  users(section: ScoreSection): ScoreUser[] {
    return this.score()?.groups[section.key] ?? [];
  }

  rows(section: ScoreSection, user: ScoreUser): Array<{ label: string; value: string }> {
    if (section.key === 'managers') {
      return [
        this.financeRow('ЗП', user.salary),
        this.financeRow('Новые компании', user.newCompanies, ' шт.'),
        { label: 'Заказы', value: this.count(user.order1Month) },
        { label: 'Отзывы', value: this.count(user.review1Month) },
        this.financeRow('Оборот', user.totalSum)
      ].filter(Boolean) as Array<{ label: string; value: string }>;
    }

    if (section.key === 'workers') {
      return [
        this.financeRow('ЗП', user.salary),
        { label: 'Заказы', value: this.count(user.order1Month) },
        { label: 'Отзывы', value: this.count(user.review1Month) },
        { label: 'Выгул', value: this.count(user.inVigul) },
        { label: 'Публикация', value: this.count(user.inPublish) }
      ].filter(Boolean) as Array<{ label: string; value: string }>;
    }

    return [
      this.financeRow('ЗП', user.salary),
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
    return `${new Intl.NumberFormat('ru-RU').format(value || 0)} шт.`;
  }

  private todayIso(): string {
    return new Date().toISOString().slice(0, 10);
  }
}
