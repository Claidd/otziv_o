import { Component, computed, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';
import { AnalyticsResponse, CabinetApi, StatDto } from '../../core/cabinet.api';

type MetricTone = 'green' | 'blue' | 'yellow' | 'red';

type Metric = {
  label: string;
  value: string;
  percent: number;
};

type ChartPoint = {
  label: string;
  value: number;
  height: number;
};

type BarChart = {
  points: ChartPoint[];
  ticks: string[];
};

type LineChartPoint = {
  label: string;
  value: number;
  x: number;
  y: number;
};

type LineChartSeries = {
  label: string;
  color: string;
  points: string;
  pointsData: LineChartPoint[];
};

type LineChart = {
  series: LineChartSeries[];
  ticks: string[];
  months: string[];
  gridLines: number[];
  plotStart: number;
  plotEnd: number;
  viewBox: string;
};

const MONTH_LABELS = ['Янв', 'Фев', 'Мар', 'Апр', 'Май', 'Июн', 'Июл', 'Авг', 'Сен', 'Окт', 'Ноя', 'Дек'];
const YEAR_COLORS = ['#ea3362', '#4a9a86', '#f7a35c', '#6c9bcf', '#9a7bd9', '#1b9c85', '#b28405'];
const LINE_VIEWBOX_WIDTH = 100;
const LINE_VIEWBOX_HEIGHT = 100;
const LINE_CHART_TOP = 7;
const LINE_CHART_BOTTOM = 12;

@Component({
  selector: 'app-admin-analytics',
  imports: [AdminLayoutComponent, FormsModule],
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

  load(): void {
    this.loading.set(true);
    this.error.set(null);

    this.cabinetApi.getAnalytics(this.selectedDate()).subscribe({
      next: (response) => {
        this.analytics.set(response);
        this.loading.set(false);
      },
      error: (error) => {
        this.error.set(error?.error?.message ?? error?.message ?? 'Не удалось загрузить аналитику');
        this.loading.set(false);
      }
    });
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

  dailyBarChartFrom(map?: string | null): BarChart {
    if (!map) {
      return this.emptyBarChart();
    }

    try {
      const parsed = JSON.parse(map) as Record<string, number | string>;
      const date = new Date(this.selectedDate());
      const daysInMonth = new Date(date.getFullYear(), date.getMonth() + 1, 0).getDate();
      const points = Array.from({ length: daysInMonth }, (_, index) => {
        const day = index + 1;
        return {
          label: String(day),
          value: this.numberValue(parsed[String(day)])
        };
      });

      return this.barChart(points);
    } catch {
      return this.emptyBarChart();
    }
  }

  yearlyLineChartFrom(map?: string | null): LineChart {
    if (!map) {
      return this.emptyLineChart();
    }

    try {
      const parsed = JSON.parse(map) as Record<string, Record<string, number | string> | number | string>;
      const years = Object.keys(parsed)
        .filter((key) => this.isMonthlyRecord(parsed[key]))
        .sort();
      const allValues = years.flatMap((year) => {
        const monthlyData = this.monthlyDataForYear(parsed, year);
        return MONTH_LABELS.map((_, index) => this.numberValue(monthlyData[String(index + 1)]));
      });
      const scale = this.buildScale(allValues);
      const plotHeight = LINE_VIEWBOX_HEIGHT - LINE_CHART_TOP - LINE_CHART_BOTTOM;
      const yFor = (value: number) => LINE_CHART_TOP + plotHeight - (value / scale.max) * plotHeight;
      const xFor = (index: number) => ((index + 0.5) * LINE_VIEWBOX_WIDTH) / MONTH_LABELS.length;

      const series = years.map((year, index) => {
        const monthlyData = this.monthlyDataForYear(parsed, year);
        const pointsData = MONTH_LABELS.map((label, monthIndex) => {
          const value = this.numberValue(monthlyData[String(monthIndex + 1)]);
          return {
            label,
            value,
            x: xFor(monthIndex),
            y: yFor(value)
          };
        });

        return {
          label: `Год: ${year}`,
          color: YEAR_COLORS[index % YEAR_COLORS.length],
          points: pointsData.map((point) => `${point.x},${point.y}`).join(' '),
          pointsData
        };
      });

      return {
        series,
        ticks: scale.ticks,
        months: MONTH_LABELS,
        gridLines: [0, 1, 2, 3, 4].map((index) => LINE_CHART_TOP + (plotHeight / 4) * index),
        plotStart: 0,
        plotEnd: LINE_VIEWBOX_WIDTH,
        viewBox: `0 0 ${LINE_VIEWBOX_WIDTH} ${LINE_VIEWBOX_HEIGHT}`
      };
    } catch {
      return this.emptyLineChart();
    }
  }

  metricId(index: number, metric: Metric): string {
    return `${index}-${metric.label}`;
  }

  stats(): StatDto | null {
    return this.analytics()?.stats ?? null;
  }

  moneyLabel(value: number): string {
    return this.formatMoney(value);
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

  private numberValue(value: unknown): number {
    return typeof value === 'number' || typeof value === 'string' ? Number(value) || 0 : 0;
  }

  private barChart(points: Array<Omit<ChartPoint, 'height'>>): BarChart {
    const scale = this.buildScale(points.map((point) => point.value));
    return {
      points: points.map((point) => ({
        ...point,
        height: point.value > 0 ? Math.max(4, Math.round((point.value / scale.max) * 100)) : 0
      })),
      ticks: scale.ticks
    };
  }

  private monthlyDataForYear(
    parsed: Record<string, Record<string, number | string> | number | string>,
    year: string
  ): Record<string, number | string> {
    const data = parsed[year];
    return this.isMonthlyRecord(data) ? data : {};
  }

  private isMonthlyRecord(value: unknown): value is Record<string, number | string> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
  }

  private buildScale(values: number[]): { max: number; ticks: string[] } {
    const maxValue = Math.max(...values, 0);
    if (maxValue <= 0) {
      return {
        max: 1,
        ticks: ['0', '0', '0', '0', '0']
      };
    }

    const max = this.niceMax(maxValue);
    return {
      max,
      ticks: [max, max * 0.75, max * 0.5, max * 0.25, 0].map((value) => this.formatAxisValue(value))
    };
  }

  private niceMax(value: number): number {
    const rawStep = value / 4;
    const magnitude = 10 ** Math.floor(Math.log10(rawStep));
    const normalized = rawStep / magnitude;
    let niceStep = 10;

    if (normalized <= 1) {
      niceStep = 1;
    } else if (normalized <= 2) {
      niceStep = 2;
    } else if (normalized <= 2.5) {
      niceStep = 2.5;
    } else if (normalized <= 5) {
      niceStep = 5;
    }

    return niceStep * magnitude * 4;
  }

  private formatAxisValue(value: number): string {
    if (Math.abs(value) >= 1000) {
      return `${new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 0 }).format(value / 1000)}к`;
    }

    return new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 0 }).format(value);
  }

  private emptyBarChart(): BarChart {
    return {
      points: [],
      ticks: ['0', '0', '0', '0', '0']
    };
  }

  private emptyLineChart(): LineChart {
    return {
      series: [],
      ticks: ['0', '0', '0', '0', '0'],
      months: MONTH_LABELS,
      gridLines: [],
      plotStart: 0,
      plotEnd: LINE_VIEWBOX_WIDTH,
      viewBox: `0 0 ${LINE_VIEWBOX_WIDTH} ${LINE_VIEWBOX_HEIGHT}`
    };
  }

  private todayIso(): string {
    return new Date().toISOString().slice(0, 10);
  }
}
