import { Component, computed, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';
import { AnalyticsResponse, CabinetApi, StatDto } from '../../core/cabinet.api';
import { apiErrorDetail } from '../../shared/api-error-message';
import { LoadErrorCardComponent } from '../../shared/load-error-card.component';
import { CabinetBarChartComponent } from './cabinet-bar-chart.component';
import {
  cabinetDailyBarChartFrom,
  cabinetPeriodTotalFrom,
  cabinetYearlyLineChartFrom,
  type CabinetBarChart,
  type CabinetLineChart,
  type YearlyLineChartOptions
} from './cabinet-chart.helpers';
import { CabinetLineChartComponent } from './cabinet-line-chart.component';

type MetricTone = 'green' | 'blue' | 'yellow' | 'red';
type AnalyticsPeriodMode = 'lastTwoYears' | 'allTime' | 'custom';

type Metric = {
  label: string;
  value: string;
  percent: number | null;
};

@Component({
  selector: 'app-admin-analytics',
  imports: [AdminLayoutComponent, FormsModule, LoadErrorCardComponent, CabinetBarChartComponent, CabinetLineChartComponent],
  templateUrl: './admin-analytics.component.html',
  styleUrl: './admin-analytics.component.scss'
})
export class AdminAnalyticsComponent {
  readonly selectedDate = signal(this.todayIso());
  readonly periodMode = signal<AnalyticsPeriodMode>('lastTwoYears');
  readonly periodFrom = signal(this.defaultPeriodFromIso(this.selectedDate()));
  readonly periodTo = signal(this.selectedDate());
  readonly analytics = signal<AnalyticsResponse | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);

  readonly payMetrics = computed(() => {
    const stats = this.analytics()?.stats;
    if (!stats) {
      return [];
    }

    return [
      this.moneyMetric('За период', this.periodMoneyTotal(stats.orderPayMapMonth), null),
      this.moneyMetric('За вчера', stats.sum1DayPay, stats.percent1DayPay),
      this.moneyMetric('За неделю', stats.sum1WeekPay, stats.percent1WeekPay),
      this.moneyMetric('За месяц', stats.sum1MonthPay, stats.percent1MonthPay)
    ];
  });

  readonly payOrderMetrics = computed(() => {
    const stats = this.analytics()?.stats;
    if (!stats) {
      return [];
    }

    return [
      this.moneyMetric('За год', stats.sum1YearPay, stats.percent1YearPay),
      { label: 'Отклики', value: this.formatCount(stats.newLeads), percent: stats.percent1NewLeadsPay },
      { label: 'Новые компании', value: this.formatCount(stats.leadsInWork), percent: stats.percent2InWorkLeadsPay }
    ];
  });

  readonly salaryMetrics = computed(() => {
    const stats = this.analytics()?.stats;
    if (!stats) {
      return [];
    }

    return [
      this.moneyMetric('За период', this.periodMoneyTotal(stats.zpPayMapMonth), null),
      this.moneyMetric('За вчера', stats.sum1Day, stats.percent1Day),
      this.moneyMetric('За неделю', stats.sum1Week, stats.percent1Week),
      this.moneyMetric('За месяц', stats.sum1Month, stats.percent1Month)
    ];
  });

  readonly salaryOrderMetrics = computed(() => {
    const stats = this.analytics()?.stats;
    if (!stats) {
      return [];
    }

    return [
      this.moneyMetric('За год', stats.sum1Year, stats.percent1Year),
      { label: 'Заказов за месяц', value: this.formatCount(stats.sumOrders1Month), percent: stats.percent1MonthOrders },
      { label: 'За прошлый месяц', value: this.formatCount(stats.sumOrders2Month), percent: stats.percent2MonthOrders }
    ];
  });

  constructor(private readonly cabinetApi: CabinetApi) {
    this.load();
  }

  load(forceRefresh = false): void {
    this.loading.set(true);
    this.error.set(null);

    this.cabinetApi.getAnalytics(this.selectedDate(), this.analyticsOptions(forceRefresh)).subscribe({
      next: (response) => {
        this.analytics.set(response);
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
    if (this.periodMode() === 'lastTwoYears') {
      this.periodFrom.set(this.defaultPeriodFromIso(date));
      this.periodTo.set(date);
    }
    this.load();
  }

  selectPeriodMode(mode: AnalyticsPeriodMode): void {
    this.periodMode.set(mode);
    if (mode === 'lastTwoYears') {
      this.periodFrom.set(this.defaultPeriodFromIso(this.selectedDate()));
      this.periodTo.set(this.selectedDate());
    }
    if (mode === 'custom' && (!this.periodFrom() || !this.periodTo())) {
      this.periodFrom.set(this.defaultPeriodFromIso(this.selectedDate()));
      this.periodTo.set(this.selectedDate());
    }
    this.load();
  }

  setPeriodFrom(date: string): void {
    this.periodFrom.set(date);
    this.periodMode.set('custom');
    this.load();
  }

  setPeriodTo(date: string): void {
    this.periodTo.set(date);
    this.periodMode.set('custom');
    this.load();
  }

  periodSubtitle(): string {
    const responsePeriod = this.analytics()?.period;
    if (responsePeriod?.allTime || this.periodMode() === 'allTime') {
      return 'все время';
    }

    const from = responsePeriod?.from ?? this.periodFrom();
    const to = responsePeriod?.to ?? this.periodTo();
    if (this.periodMode() === 'custom') {
      return `${this.formatDateLabel(from)} - ${this.formatDateLabel(to)}`;
    }

    return 'последние 2 года';
  }

  tone(percent: number): MetricTone {
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

  dailyBarChartFrom(map?: string | null): CabinetBarChart {
    return cabinetDailyBarChartFrom(map, this.selectedDate());
  }

  yearlyLineChartFrom(map?: string | null): CabinetLineChart {
    return cabinetYearlyLineChartFrom(map, this.chartPeriodOptions());
  }

  metricId(index: number, metric: Metric): string {
    return `${index}-${metric.label}`;
  }

  stats(): StatDto | null {
    return this.analytics()?.stats ?? null;
  }

  metricTone(metric: Metric): MetricTone {
    return metric.percent == null ? 'blue' : this.tone(metric.percent);
  }

  private moneyMetric(label: string, value: number, percent: number | null): Metric {
    return {
      label,
      value: this.formatMoney(value),
      percent
    };
  }

  private formatMoney(value: number): string {
    return `${new Intl.NumberFormat('ru-RU').format(value || 0)} руб.`;
  }

  private periodMoneyTotal(map?: string | null): number {
    return cabinetPeriodTotalFrom(map, this.chartPeriodOptions());
  }

  private formatCount(value: number): string {
    return `${new Intl.NumberFormat('ru-RU').format(value || 0)} шт.`;
  }

  private analyticsOptions(forceRefresh: boolean) {
    if (this.periodMode() === 'allTime') {
      return { forceRefresh, allTime: true };
    }
    if (this.periodMode() === 'custom') {
      return {
        forceRefresh,
        from: this.periodFrom(),
        to: this.periodTo()
      };
    }

    return { forceRefresh };
  }

  private chartPeriodOptions(): YearlyLineChartOptions {
    if (this.periodMode() === 'allTime') {
      return { allTime: true };
    }

    const responsePeriod = this.analytics()?.period;
    return {
      from: responsePeriod?.from ?? this.periodFrom(),
      to: responsePeriod?.to ?? this.periodTo()
    };
  }

  private defaultPeriodFromIso(dateIso: string): string {
    const year = Number(dateIso.slice(0, 4)) || new Date().getFullYear();
    return `${year - 1}-01-01`;
  }

  private formatDateLabel(dateIso: string): string {
    return dateIso.split('-').reverse().join('.');
  }

  private todayIso(): string {
    return new Date().toISOString().slice(0, 10);
  }
}
