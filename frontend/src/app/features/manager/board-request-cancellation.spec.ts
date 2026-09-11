import { signal } from '@angular/core';
import { Observable, Subject } from 'rxjs';
import { ManagerBoardComponent } from './manager-board.component';
import { WorkerBoardComponent } from '../worker/worker-board.component';

describe('Board request cancellation', () => {
  for (const type of [ManagerBoardComponent, WorkerBoardComponent]) {
    it(`${type.name} aborts superseded HTTP subscriptions and cleans up on leaving`, () => {
      let active = 0, cancelled = 0;
      const api = { getBoard: () => new Observable(() => {
        active++;
        return () => { active--; cancelled++; };
      }) };
      const component = Object.assign(Object.create(type.prototype), {
        cancelBoardLoad: new Subject<void>(), boardLoadEpoch: 0,
        loading: signal(false), error: signal(null), keyword: () => '',
        activeSection: () => 'orders', activeStatus: () => 'Все',
        pageNumber: () => 0, pageSize: () => 10, sortDirection: () => 'desc',
        selectedCompany: () => null, selectedManagerId: () => null,
        selectedControl: () => null, selectedWorkerId: () => null,
        boardSectionForLoad: () => 'new', managerApi: api, workerApi: api,
        storeBoardState: () => {}, hideBoardNotice: () => {}, clearSearchTimer: () => {},
        clearBoardNoticeTimer: () => {}, clearPublishCredentialWaitTimer: () => {},
        clearProgressRefreshTimer: () => {}, chatBotLinkPollTimers: new Map(), chatBotLinkPolls: new Map()
      });
      component.loadBoard();
      expect(active).toBe(1);
      component.loadBoard();
      expect(active).toBe(1);
      expect(cancelled).toBe(1);
      component.ngOnDestroy();
      expect(active).toBe(0);
      expect(cancelled).toBe(2);
    });
  }
});
