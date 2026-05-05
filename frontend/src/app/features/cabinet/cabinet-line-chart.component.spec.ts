import { TestBed } from '@angular/core/testing';
import { CabinetLineChartComponent } from './cabinet-line-chart.component';

describe('CabinetLineChartComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CabinetLineChartComponent]
    }).compileComponents();
  });

  it('renders heading, legend, svg lines and month axis', () => {
    const fixture = TestBed.createComponent(CabinetLineChartComponent);
    fixture.componentInstance.heading = 'Оборот по месяцам';
    fixture.componentInstance.subtitle = 'все годы';
    fixture.componentInstance.ariaLabel = 'Оборот';
    fixture.componentInstance.chart = {
      series: [
        {
          label: 'Год: 2026',
          color: '#ea3362',
          points: '10,90 50,50',
          pointsData: [
            { label: 'Янв', value: 100, x: 10, y: 90 },
            { label: 'Фев', value: 200, x: 50, y: 50 }
          ]
        }
      ],
      ticks: ['200', '100', '0'],
      months: ['Янв', 'Фев'],
      gridLines: [10, 50],
      plotStart: 0,
      plotEnd: 100,
      viewBox: '0 0 100 100'
    };

    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('h3')?.textContent?.trim()).toBe('Оборот по месяцам');
    expect(element.querySelector('.line-legend')?.textContent).toContain('Год: 2026');
    expect(element.querySelector('svg')?.getAttribute('aria-label')).toBe('Оборот');
    expect(element.querySelectorAll('.grid-line')).toHaveLength(2);
    expect(element.querySelector('.year-line')?.getAttribute('points')).toBe('10,90 50,50');
    expect(element.querySelectorAll('.chart-dot')).toHaveLength(2);
    expect(Array.from(element.querySelectorAll('.x-axis span')).map((item) => item.textContent?.trim())).toEqual(['Янв', 'Фев']);
  });
});
