import { Component, computed, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { CabinetApi, CabinetUserInfo, UserStat } from '../../core/cabinet.api';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';

type ChartPoint = {
  label: string;
  value: number;
  height: number;
};

@Component({
  selector: 'app-user-info',
  imports: [AdminLayoutComponent, FormsModule, RouterLink],
  templateUrl: './user-info.component.html',
  styleUrl: './user-info.component.scss'
})
export class UserInfoComponent {
  readonly selectedDate = signal(this.todayIso());
  readonly payload = signal<CabinetUserInfo | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);

  readonly userId: number;

  readonly metrics = computed(() => {
    const stat = this.payload()?.workerZp;
    if (!stat) {
      return [];
    }

    return [
      { label: 'За вчера', value: this.money(stat.sum1Day), percent: stat.percent1Day },
      { label: 'За неделю', value: this.money(stat.sum1Week), percent: stat.percent1Week },
      { label: 'За месяц', value: this.money(stat.sum1Month), percent: stat.percent1Month },
      { label: 'За год', value: this.money(stat.sum1Year), percent: stat.percent1Year },
      { label: 'Заказов за месяц', value: this.count(stat.sumOrders1Month), percent: stat.percent1MonthOrders },
      { label: 'За прошлый месяц', value: this.count(stat.sumOrders2Month), percent: stat.percent2MonthOrders }
    ];
  });

  constructor(
    private readonly cabinetApi: CabinetApi,
    private readonly route: ActivatedRoute
  ) {
    this.userId = Number(this.route.snapshot.paramMap.get('userId'));
    this.load();
  }

  load(forceRefresh = false): void {
    if (!Number.isFinite(this.userId)) {
      this.error.set('Некорректный пользователь');
      return;
    }

    this.loading.set(true);
    this.error.set(null);

    this.cabinetApi.getUserInfo(this.userId, this.selectedDate(), { forceRefresh }).subscribe({
      next: (response) => {
        this.payload.set(response);
        this.loading.set(false);
      },
      error: (error) => {
        this.error.set(error?.error?.message ?? error?.message ?? 'Не удалось загрузить пользователя');
        this.loading.set(false);
      }
    });
  }

  refresh(): void {
    this.load(true);
  }

  selectDate(date: string): void {
    this.selectedDate.set(date);
    this.load(true);
  }

  imageUrl(stat?: UserStat | null): string {
    return this.cabinetApi.imageUrl(stat?.imageId);
  }

  chartFrom(map?: string | null): ChartPoint[] {
    if (!map) {
      return [];
    }

    try {
      const parsed = JSON.parse(map) as Record<string, number | string>;
      const points = Object.entries(parsed).map(([label, rawValue]) => ({
        label,
        value: Number(rawValue) || 0
      }));
      const max = Math.max(...points.map((point) => point.value), 1);

      return points.map((point) => ({
        ...point,
        height: Math.max(4, Math.round((point.value / max) * 100))
      }));
    } catch {
      return [];
    }
  }

  tone(percent: number): string {
    if (percent > 25) {
      return 'green';
    }
    if (percent >= 0) {
      return 'blue';
    }
    if (percent > -25) {
      return 'yellow';
    }
    return 'red';
  }

  private money(value?: number | null): string {
    return `${new Intl.NumberFormat('ru-RU').format(value || 0)} руб.`;
  }

  private count(value?: number | null): string {
    return `${new Intl.NumberFormat('ru-RU').format(value || 0)} шт.`;
  }

  private todayIso(): string {
    return new Date().toISOString().slice(0, 10);
  }
}
