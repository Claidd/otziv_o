import { of } from 'rxjs';
import type { CabinetApi, TeamMember, TeamResponse } from '../../core/cabinet.api';
import { TeamComponent } from './team.component';

describe('TeamComponent monthly accrual display', () => {
  it('uses exact kopecks for every role and keeps old rubles as a fallback', () => {
    const response = {
      role: 'OWNER', managers: [], workers: [], operators: [], marketologs: []
    } as unknown as TeamResponse;
    const api = { getTeam: () => of(response) } as unknown as CabinetApi;
    const component = new TeamComponent(api);
    const member = {
      id: 1, userId: 1, login: 'user', fio: 'Сотрудник', imageId: 0,
      sum1Month: 0, sum1MonthKopecks: 45
    } as TeamMember;

    try {
      for (const role of ['manager', 'worker', 'marketolog', 'operator'] as const) {
        expect(component.statRows(role, member).find((row) => row.label === 'Начислено')?.value)
          .toBe('0,45 руб.');
      }
      expect(component.statRows('manager', { ...member, sum1Month: 9, sum1MonthKopecks: null })
        .find((row) => row.label === 'Начислено')?.value).toBe('9,00 руб.');
    } finally {
      component.ngOnDestroy();
    }
  });
});
