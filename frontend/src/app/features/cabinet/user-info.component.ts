import { Component, computed, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { CabinetApi, CabinetUserInfo, UserStat } from '../../core/cabinet.api';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';
import { apiErrorDetail } from '../../shared/api-error-message';
import { LoadErrorCardComponent } from '../../shared/load-error-card.component';
import { CabinetBarChartComponent } from './cabinet-bar-chart.component';
import {
  cabinetDailyBarChartFrom,
  cabinetYearlyLineChartFrom,
  type CabinetBarChart,
  type CabinetLineChart
} from './cabinet-chart.helpers';
import { CabinetLineChartComponent } from './cabinet-line-chart.component';

const MONTH_NAMES = [
  'Январь',
  'Февраль',
  'Март',
  'Апрель',
  'Май',
  'Июнь',
  'Июль',
  'Август',
  'Сентябрь',
  'Октябрь',
  'Ноябрь',
  'Декабрь'
];

@Component({
  selector: 'app-user-info',
  imports: [
    AdminLayoutComponent,
    FormsModule,
    LoadErrorCardComponent,
    RouterLink,
    CabinetBarChartComponent,
    CabinetLineChartComponent
  ],
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

  imageUrl(stat?: UserStat | null): string {
    return this.cabinetApi.imageUrl(stat?.imageId);
  }

  dailyChartFrom(map?: string | null): CabinetBarChart {
    return cabinetDailyBarChartFrom(map, this.selectedDate());
  }

  yearlyLineChartFrom(map?: string | null): CabinetLineChart {
    return cabinetYearlyLineChartFrom(map, { fallbackYear: new Date(this.selectedDate()).getFullYear() });
  }

  selectedMonthLabel(): string {
    const date = new Date(this.selectedDate());
    return `Месяц: ${MONTH_NAMES[date.getMonth()] ?? MONTH_NAMES[0]}`;
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
