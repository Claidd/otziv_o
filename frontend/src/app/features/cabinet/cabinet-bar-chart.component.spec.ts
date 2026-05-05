import { TestBed } from '@angular/core/testing';
import { CabinetBarChartComponent } from './cabinet-bar-chart.component';

describe('CabinetBarChartComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CabinetBarChartComponent]
    }).compileComponents();
  });

  it('renders heading, legend, ticks and bars', () => {
    const fixture = TestBed.createComponent(CabinetBarChartComponent);
    fixture.componentInstance.heading = 'Зарплаты по дням';
    fixture.componentInstance.headingLevel = 2;
    fixture.componentInstance.subtitle = '2026-05-05';
    fixture.componentInstance.legendLabel = 'Месяц: Май';
    fixture.componentInstance.salary = true;
    fixture.componentInstance.chart = {
      ticks: ['100', '50', '0'],
      points: [
        { label: '1', value: 100, height: 100 },
        { label: '2', value: 50, height: 50 }
      ]
    };

    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('h2')?.textContent?.trim()).toBe('Зарплаты по дням');
    expect(element.querySelector('.chart-head small')?.textContent?.trim()).toBe('2026-05-05');
    expect(element.querySelector('.daily-legend')?.textContent).toContain('Месяц: Май');
    expect(Array.from(element.querySelectorAll('.y-axis span')).map((item) => item.textContent?.trim())).toEqual(['100', '50', '0']);
    expect(element.querySelectorAll('.bar-item')).toHaveLength(2);
    expect(element.querySelector('.bar-chart.salary')).not.toBeNull();
    expect(element.querySelector<HTMLElement>('.bar')?.style.height).toBe('100%');
  });
});
