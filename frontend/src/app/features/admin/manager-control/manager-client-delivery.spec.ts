import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { of, Subject } from 'rxjs';
import { AuthService } from '../../../core/auth.service';
import { ManagerApi } from '../../../core/manager.api';
import { ManagerControlApi, type ManagerControlConcreteItem, type ManagerControlManagerDetail } from '../../../core/manager-control.api';
import { ToastService } from '../../../shared/toast.service';
import { ManagerControlComponent } from './manager-control.component';

describe('queued manager messages in the web card', () => {
  const card: ManagerControlConcreteItem = { controlEntityId: 41, type: 'ORDER', title: 'Fixture', itemStatus: 'OPEN', contactText: 'Fixture reply' };
  const delivery = { operationId: '00000000-0000-0000-0000-000000000041', status: 'QUEUED', attempts: 0, errorCode: null };
  const detail = (example = card) => ({ managerId: 7, dailyControlId: 1, items: [{ itemId: 1, group: 'ACTION', itemType: 'CLIENT_CHAT_UNANSWERED', count: 1, examples: [example] }] }) as ManagerControlManagerDetail;

  it.each(['send', 'reply'] as const)('%s retains the accepted card and prevents a second POST until the receipt arrives', async action => {
    const accepted = { ...card, itemStatus: 'ACTION_TAKEN' as const, delivery };
    const send = vi.fn().mockReturnValue(of(accepted));
    const receipts = new Subject<typeof delivery>();
    const toast = { info: vi.fn(), success: vi.fn(), error: vi.fn() };
    const detailsRead = vi.fn().mockReturnValue(of({ ...detail(), items: [] }));
    TestBed.configureTestingModule({ providers: [
      { provide: ManagerControlApi, useValue: { sendClientMessage: send, replyToClientMessage: send, deliveryOperation: () => receipts,
        managerDetails: detailsRead, today: () => of({ managers: [] }) } },
      { provide: ManagerApi, useValue: {} }, { provide: ToastService, useValue: toast },
      { provide: AuthService, useValue: { hasAnyRealmRole: () => false } },
      { provide: ActivatedRoute, useValue: { snapshot: { data: {} }, paramMap: new Subject() } },
      { provide: Router, useValue: {} }
    ] });
    TestBed.overrideComponent(ManagerControlComponent, { set: { template: '', imports: [] } });
    const fixture = TestBed.createComponent(ManagerControlComponent), page = fixture.componentInstance;
    page.detail.set(detail());
    page.unansweredReplyDrafts.set({ 41: 'Fixture reply' });
    const submit = () => action === 'send' ? page.sendClientMessage(card) : page.sendUnansweredReply(card);
    submit(); submit();
    expect(send).toHaveBeenCalledTimes(1);
    expect(page.detail()?.items[0].examples).toHaveLength(1);
    expect(page.deliveryMessage(card)).toContain('очереди');
    expect(page.deliveryBlocked(card)).toBe(true);
    expect(toast.success).not.toHaveBeenCalled();
    receipts.next({ ...delivery, status: 'SENT' });
    for (let n = 0; n < 8; n++) await Promise.resolve();
    expect(detailsRead).toHaveBeenCalledExactlyOnceWith(7);
    expect(send).toHaveBeenCalledTimes(1);
    fixture.destroy();
  });
});
