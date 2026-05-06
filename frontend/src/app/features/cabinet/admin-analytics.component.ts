import { Component, computed, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';
import { AnalyticsResponse, CabinetApi, StatDto } from '../../core/cabinet.api';
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

type MetricTone = 'green' | 'blue' | 'yellow' | 'red';

type Metric = {
  label: string;
  value: string;
  percent: number;
};

@Component({
  selector: 'app-admin-analytics',
  imports: [AdminLayoutComponent, FormsModule, LoadErrorCardComponent, CabinetBarChartComponent, CabinetLineChartComponent],
  templateUrl: './admin-analytics.component.html',
  styleUrl: './admin-analytics.component.scss'
})
export class AdminAnalyticsComponent {
  readonly selectedDate = signal(this.todayIso());
  readonly analytics = signal<AnalyticsResponse | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);

  readonly payMetrics = computed(() => {
    const stats = this.analytics()?.stats;
    if (!stats) {
      return [];
    }

    return [
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

    this.cabinetApi.getAnalytics(this.selectedDate(), { forceRefresh }).subscribe({
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
    this.load(true);
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
    return cabinetYearlyLineChartFrom(map);
  }

  metricId(index: number, metric: Metric): string {
    return `${index}-${metric.label}`;
  }

  stats(): StatDto | null {
    return this.analytics()?.stats ?? null;
  }

  private moneyMetric(label: string, value: number, percent: number): Metric {
    return {
      label,
      value: this.formatMoney(value),
      percent
    };
  }

  private formatMoney(value: number): string {
    return `${new Intl.NumberFormat('ru-RU').format(value || 0)} руб.`;
  }

  private formatCount(value: number): string {
    return `${new Intl.NumberFormat('ru-RU').format(value || 0)} шт.`;
  }

  private todayIso(): string {
    return new Date().toISOString().slice(0, 10);
  }
}
