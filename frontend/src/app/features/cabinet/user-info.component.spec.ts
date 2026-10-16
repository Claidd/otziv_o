import { convertToParamMap, type ActivatedRoute } from '@angular/router';
import { of } from 'rxjs';
import type { CabinetApi, CabinetUserInfo } from '../../core/cabinet.api';
import { UserInfoComponent } from './user-info.component';

describe('UserInfoComponent exact accruals', () => {
  it('shows precise day, month and year salary with old-ruble fallback for week', () => {
    const response = {
      workerZp: {
        sum1Day: 8,
        sum1Week: 16,
        sum1Month: 50,
        sum1Year: 500,
        sum1DayKopecks: 825,
        sum1WeekKopecks: null,
        sum1MonthKopecks: 0,
        sum1YearKopecks: 50_045,
        percent1Day: 0,
        percent1Week: 0,
        percent1Month: 0,
        percent1Year: 0
      }
    } as unknown as CabinetUserInfo;
    const api = { getUserInfo: () => of(response) } as unknown as CabinetApi;
    const route = { paramMap: of(convertToParamMap({ userId: '5' })) } as unknown as ActivatedRoute;
    const component = new UserInfoComponent(api, route);

    try {
      expect(component.metrics().slice(0, 4).map((metric) => metric.value))
        .toEqual(['8,25 руб.', '16,00 руб.', '0,00 руб.', '500,45 руб.']);
    } finally {
      component.ngOnDestroy();
    }
  });
});
