import {
  consumeWorkerCurrentSectionOpenRequest,
  requestWorkerCurrentSectionOpen
} from './worker-entry-navigation';

describe('worker entry navigation', () => {
  beforeEach(() => {
    window.sessionStorage.clear();
  });

  it('consumes the current-work request once', () => {
    requestWorkerCurrentSectionOpen();

    expect(consumeWorkerCurrentSectionOpenRequest()).toBe(true);
    expect(consumeWorkerCurrentSectionOpenRequest()).toBe(false);
  });
});
