import { Component, OnDestroy, computed, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { CabinetApi, CabinetUserInfo, UserStat } from '../../core/cabinet.api';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';
import { apiErrorDetail } from '../../shared/api-error-message';
import { LoadErrorCardComponent } from '../../shared/load-error-card.component';
import { businessDateIso } from '../../shared/business-date';
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
export class UserInfoComponent implements OnDestroy {
  readonly selectedDate = signal(this.todayIso());
  readonly payload = signal<CabinetUserInfo | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);

  private userId: number | null = null;
  private readonly routeSubscription: Subscription;
  private requestSubscription?: Subscription;
  private requestRun = 0;
  private destroyed = false;

  readonly metrics = computed(() => {
    const stat = this.payload()?.workerZp;
    if (!stat) {
      return [];
    }

    return [
      { label: 'За сегодня', value: this.money(stat.sum1Day), percent: stat.percent1Day },
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
    this.routeSubscription = this.route.paramMap.subscribe((params) => {
      const value = Number(params.get('userId'));
      this.userId = Number.isSafeInteger(value) && value > 0 ? value : null;
      this.requestRun += 1;
      this.requestSubscription?.unsubscribe();
      this.payload.set(null);
      this.error.set(null);
      this.loading.set(false);
      this.load();
    });
  }

  ngOnDestroy(): void {
    this.destroyed = true;
    this.requestRun += 1;
    this.routeSubscription.unsubscribe();
    this.requestSubscription?.unsubscribe();
  }

  load(forceRefresh = false): void {
    const userId = this.userId;
    if (userId == null) {
      this.error.set('Некорректный пользователь');
      return;
    }

    const selectedDate = this.selectedDate();
    const requestRun = ++this.requestRun;
    this.requestSubscription?.unsubscribe();
    this.loading.set(true);
    this.error.set(null);

    this.requestSubscription = this.cabinetApi.getUserInfo(userId, selectedDate, { forceRefresh }).subscribe({
      next: (response) => {
        if (!this.isCurrentRequest(requestRun, userId, selectedDate)) {
          return;
        }
        this.payload.set(response);
        this.loading.set(false);
      },
      error: (error) => {
        if (!this.isCurrentRequest(requestRun, userId, selectedDate)) {
          return;
        }
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
    return businessDateIso();
  }

  private isCurrentRequest(requestRun: number, userId: number, selectedDate: string): boolean {
    return !this.destroyed
      && requestRun === this.requestRun
      && userId === this.userId
      && selectedDate === this.selectedDate();
  }
}
