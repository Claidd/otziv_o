import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AuthService } from '../core/auth.service';
import { ManagerControlApi } from '../core/manager-control.api';
import { ManagerReportsApi } from '../core/manager-reports.api';
import { ManagerWorkerRiskApi } from '../core/manager-worker-risk.api';
import { MobileExternalLinkService } from '../shared/mobile-external-link.service';
import type { ManagerControlConcreteItem, ManagerControlManagerDetail } from '../core/manager-control.models';
import { ManagerControlPage } from './manager-control.page';

describe('queued manager messages in the mobile card', () => {
  it.each(['send', 'reply'] as const)('%s preserves pending feedback across a detail reload and a failed receipt GET', async action => {
    const card: ManagerControlConcreteItem = { controlEntityId: 41, type: 'ORDER', title: 'Fixture', itemStatus: 'OPEN', contactText: 'Fixture reply' };
    const delivery = { operationId: '00000000-0000-0000-0000-000000000041', status: 'QUEUED', attempts: 0, errorCode: null };
    const accepted = { ...card, itemStatus: 'ACTION_TAKEN' as const, comment: 'client_message_delivery_prepared:' + delivery.operationId, delivery };
    const send = vi.fn().mockReturnValue(of(accepted));
    const detail = { managerId: 7, dailyControlId: 1, items: [{ itemId: 1, group: 'ACTION', itemType: 'CLIENT_CHAT_UNANSWERED', itemStatus: 'ACTION_TAKEN', examples: [accepted] }] } as ManagerControlManagerDetail;
    TestBed.configureTestingModule({ providers: [
      { provide: ManagerControlApi, useValue: { sendManagerControlClientMessage: send, replyManagerControlClientMessage: send,
        getManagerControlDetails: () => of(detail), getManagerControlToday: () => of({ managers: [] }),
        deliveryOperation: () => throwError(() => new Error('offline')) } },
      { provide: ManagerReportsApi, useValue: {} }, { provide: ManagerWorkerRiskApi, useValue: {} },
      { provide: AuthService, useValue: { hasAnyRealmRole: () => false } },
      { provide: MobileExternalLinkService, useValue: {} },
      { provide: ActivatedRoute, useValue: {} }, { provide: Router, useValue: {} }
    ] });
    TestBed.overrideComponent(ManagerControlPage, { set: { template: '', imports: [] } });
    const fixture = TestBed.createComponent(ManagerControlPage), page = fixture.componentInstance;
    page.selectedManagerId.set(7); page.detail.set(detail); page.replies.set({ 41: 'Fixture reply' });
    if (action === 'send') await page.sendClientMessage(card); else await page.replyClient(card);
    expect(send).toHaveBeenCalledTimes(1);
    expect(page.notice()).toContain('очереди');
    expect(page.deliveryBlocked(card)).toBe(true);
    expect(page.detail()?.items[0].examples).toHaveLength(1);
    if (action === 'send') await page.sendClientMessage(card); else { page.replies.set({ 41: 'Fixture reply' }); await page.replyClient(card); }
    expect(send).toHaveBeenCalledTimes(1);
    fixture.destroy();
  });
});
