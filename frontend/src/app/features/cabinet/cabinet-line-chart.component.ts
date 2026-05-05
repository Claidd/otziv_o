import { Component, Input } from '@angular/core';
import type { CabinetLineChart } from './cabinet-chart.helpers';

const EMPTY_LINE_CHART: CabinetLineChart = {
  series: [],
  ticks: ['0', '0', '0', '0', '0'],
  months: [],
  gridLines: [],
  plotStart: 0,
  plotEnd: 100,
  viewBox: '0 0 100 100'
};

@Component({
  selector: 'app-cabinet-line-chart',
  templateUrl: './cabinet-line-chart.component.html',
  styleUrl: './cabinet-line-chart.component.scss'
})
export class CabinetLineChartComponent {
  @Input() heading = '';
  @Input() headingLevel: 2 | 3 = 3;
  @Input() subtitle = '';
  @Input() ariaLabel = '';
  @Input() chart: CabinetLineChart = EMPTY_LINE_CHART;
  @Input() compact = false;

  moneyLabel(value: number): string {
    return `${new Intl.NumberFormat('ru-RU').format(value || 0)} руб.`;
  }
}
