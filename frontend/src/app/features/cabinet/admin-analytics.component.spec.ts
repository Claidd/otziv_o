import { of } from 'rxjs';
import type { AnalyticsResponse, CabinetApi } from '../../core/cabinet.api';
import { AdminAnalyticsComponent } from './admin-analytics.component';

describe('AdminAnalyticsComponent exact accruals', () => {
  it('uses kopecks for salary metrics, falls back to rubles and leaves revenue unchanged', () => {
    const response = {
      stats: {
        sum1Day: 7,
        sum1Week: 12,
        sum1Month: 99,
        sum1Year: 101,
        sum1DayKopecks: 715,
        sum1WeekKopecks: null,
        sum1MonthKopecks: 0,
        sum1YearKopecks: 10_125,
        percent1Day: 2,
        percent1Week: 3,
        percent1Month: 4,
        percent1Year: 5,
        sum1DayPay: 10,
        percent1DayPay: 6
      }
    } as unknown as AnalyticsResponse;
    const api = { getAnalytics: () => of(response) } as unknown as CabinetApi;
    const component = new AdminAnalyticsComponent(api);

    expect(component.salaryMetrics().slice(1).map((metric) => metric.value))
      .toEqual(['7,15 руб.', '12,00 руб.', '0,00 руб.']);
    expect(component.salaryOrderMetrics()[0].value).toBe('101,25 руб.');
    expect(component.payMetrics()[1].value).toBe('10 руб.');
  });
});
