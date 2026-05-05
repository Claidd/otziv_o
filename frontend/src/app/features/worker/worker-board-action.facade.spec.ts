import { signal } from '@angular/core';
import { of } from 'rxjs';
import type { OrderCardItem } from '../../core/manager.api';
import type { WorkerBotItem, WorkerReviewItem, WorkerSection } from '../../core/worker.api';
import type { StatusAction } from './worker-board.config';
import { WorkerBoardActionFacade, type WorkerBoardActionFacadeDeps } from './worker-board-action.facade';

function order(overrides: Partial<OrderCardItem> = {}): OrderCardItem {
  return {
    id: 20,
    companyId: 10,
    companyTitle: 'Company',
    status: 'Новые',
    ...overrides
  };
}

function review(overrides: Partial<WorkerReviewItem> = {}): WorkerReviewItem {
  return {
    id: 7,
    companyId: 10,
    orderId: 20,
    text: 'text',
    answer: 'answer',
    category: '',
    subCategory: '',
    botId: 11,
    botFio: 'Bot',
    botLogin: 'login',
    botPassword: 'pass',
    botCounter: 2,
    companyTitle: 'Company',
    commentCompany: 'company note',
    orderComments: 'order note',
    filialCity: '',
    filialTitle: '',
    filialUrl: '',
    productTitle: '',
    productPhoto: false,
    workerFio: 'Worker',
    created: '',
    changed: '',
    publishedDate: '',
    publish: false,
    vigul: false,
    comment: '',
    url: '',
    urlPhoto: '',
    ...overrides
  };
}

function bot(overrides: Partial<WorkerBotItem> = {}): WorkerBotItem {
  return {
    id: 5,
    login: 'bot-login',
    password: 'bot-pass',
    fio: 'Bot Name',
    city: 'City',
    counter: 3,
    workerFio: 'Worker',
    status: 'ACTIVE',
    active: true,
    ...overrides
  };
}

function createFacade(section: WorkerSection = 'publish') {
  const activeSection = signal<WorkerSection>(section);
  const mutationKey = signal<string | null>(null);
  const calls: string[] = [];
  const toastMessages: string[] = [];
  const deps: WorkerBoardActionFacadeDeps = {
    workerApi: {
      updateOrderStatus: (orderId: number, status: string) => {
        calls.push(`status:${orderId}:${status}`);
        return of(void 0);
      },
      changeReviewBot: (reviewId: number) => {
        calls.push(`change:${reviewId}`);
        return of({ oldBotId: 11, newBotId: 44 });
      },
      deactivateReviewBot: (reviewId: number, botId: number) => {
        calls.push(`deactivate:${reviewId}:${botId}`);
        return of(void 0);
      },
      publishReview: (reviewId: number) => {
        calls.push(`publish:${reviewId}`);
        return of(void 0);
      },
      completeBadReviewTask: (taskId: number) => {
        calls.push(`bad-done:${taskId}`);
        return of(void 0);
      },
      changeBadReviewTaskBot: (taskId: number) => {
        calls.push(`bad-change:${taskId}`);
        return of({ oldBotId: 11, newBotId: 45 });
      },
      deactivateBadReviewTaskBot: (taskId: number, botId: number) => {
        calls.push(`bad-deactivate:${taskId}:${botId}`);
        return of(void 0);
      },
      nagulReview: (reviewId: number) => {
        calls.push(`nagul:${reviewId}`);
        return of({ success: true, message: 'Нагул готов' });
      },
      deleteBot: (botId: number) => {
        calls.push(`delete-bot:${botId}`);
        return of(void 0);
      }
    },
    toastService: {
      success: (title: string, message?: string) => {
        toastMessages.push(`success:${title}:${message ?? ''}`);
        return toastMessages.length;
      },
      error: (title: string, message?: string) => {
        toastMessages.push(`error:${title}:${message ?? ''}`);
        return toastMessages.length;
      }
    },
    activeSection,
    mutationKey,
    loadBoard: () => {
      calls.push('load-board');
    },
    errorMessage: (_err, fallback) => fallback
  };

  return {
    facade: new WorkerBoardActionFacade(deps),
    activeSection,
    mutationKey,
    calls,
    toastMessages
  };
}

describe('WorkerBoardActionFacade', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('updates order status and reloads the board', () => {
    const { facade, calls, mutationKey, toastMessages } = createFacade();
    const action: StatusAction = { label: 'archive', status: 'Архив', icon: 'archive' };

    facade.updateOrderStatus(order({ id: 30, companyTitle: 'Acme' }), action);

    expect(calls).toEqual(['status:30:Архив', 'load-board']);
    expect(mutationKey()).toBeNull();
    expect(toastMessages).toContain('success:Статус изменен:Acme: Архив');
  });

  it('changes a regular review bot or bad-task bot', () => {
    const { facade, calls, toastMessages } = createFacade();

    facade.changeReviewBot(review({ id: 7, botId: 11 }));
    facade.changeReviewBot(review({ id: 8, botId: 12, badTask: true, badTaskId: 90 }));

    expect(calls).toContain('change:7');
    expect(calls).toContain('bad-change:90');
    expect(toastMessages.filter((message) => message.startsWith('success:Аккаунт изменен'))).toHaveLength(2);
  });

  it('deactivates a bot only after confirmation', () => {
    const { facade, calls } = createFacade();
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValueOnce(true);

    facade.deactivateReviewBot(review({ id: 7, botId: 11 }));
    facade.deactivateReviewBot(review({ id: 8, botId: 12, badTask: true, badTaskId: 90 }));

    expect(confirmSpy).toHaveBeenCalledTimes(2);
    expect(calls).not.toContain('deactivate:7:11');
    expect(calls).toContain('bad-deactivate:90:12');
    expect(calls).toContain('load-board');
  });

  it('routes done action by review type and active section', () => {
    const { facade, activeSection, calls, toastMessages } = createFacade('publish');

    facade.markReviewDone(review({ id: 1 }));
    activeSection.set('nagul');
    facade.markReviewDone(review({ id: 2 }));
    facade.markReviewDone(review({ id: 3, badTask: true, badTaskId: 80 }));

    expect(calls).toContain('publish:1');
    expect(calls).toContain('nagul:2');
    expect(calls).toContain('bad-done:80');
    expect(toastMessages).toContain('success:Выгул выполнен:Нагул готов');
    expect(toastMessages).toContain('success:Оценка изменена:Плохая задача #80');
  });

  it('deletes worker bot accounts after confirmation', () => {
    const { facade, calls, toastMessages } = createFacade();
    vi.spyOn(window, 'confirm').mockReturnValue(true);

    facade.deleteBot(bot({ id: 6, fio: 'Old Bot' }));

    expect(calls).toEqual(['delete-bot:6', 'load-board']);
    expect(toastMessages).toContain('success:Аккаунт удален:Old Bot');
  });
});
