import { Component, Input } from '@angular/core';
import type { CabinetBarChart } from './cabinet-chart.helpers';

const EMPTY_BAR_CHART: CabinetBarChart = {
  points: [],
  ticks: ['0', '0', '0', '0', '0']
};

@Component({
  selector: 'app-cabinet-bar-chart',
  templateUrl: './cabinet-bar-chart.component.html',
  styleUrl: './cabinet-bar-chart.component.scss'
})
export class CabinetBarChartComponent {
  @Input() heading = '';
  @Input() headingLevel: 2 | 3 = 3;
  @Input() subtitle = '';
  @Input() legendLabel: string | null = null;
  @Input() legendColor = 'var(--otziv-primary)';
  @Input() chart: CabinetBarChart = EMPTY_BAR_CHART;
  @Input() compact = false;
  @Input() salary = false;

  moneyLabel(value: number): string {
    return `${new Intl.NumberFormat('ru-RU').format(value || 0)} руб.`;
  }
}
