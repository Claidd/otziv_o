import { TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';
import { ManualPaymentReviewComponent } from './manual-payment-review.component';
import { ManualPaymentReviewApi } from '../core/manual-payment-review.api';
import { AuthService } from '../core/auth.service';
import {
  type ManualPaymentReviewView, manualPaymentReviewPresentation,
  manualPaymentReviewDisputeRequest, manualPaymentResultNotice
} from '@otziv/client-common/billing-payments';

const review = (patch: Partial<ManualPaymentReviewView> = {}): ManualPaymentReviewView => ({
  id: 4, orderId: 27127, status: 'WAITING', deadlineAt: '2026-10-24T14:25:00',
  creditedAt: '2026-10-23T14:25:00', confirmationBasis: 'MANAGER_REPORTED',
  disputeAllowed: true, ...patch
});

describe('manual payment review presentation', () => {
  it('separates credited payment from a notification still awaiting delivery', () => {
    const result = manualPaymentResultNotice({
      status: 'COMPLETED', orderId: 27127,
      manualPaymentReview: review({ status: 'PENDING_DELIVERY' })
    });
    expect(result.title).toBe('Оплата отмечена');
    expect(result.tone).toBe('success');
    expect(result.detail).toContain('24.10.2026 в 14:25');
    expect(result.detail).toContain('сообщив менеджеру');
  });

  it('never infers confirmation or permission from an elapsed deadline', () => {
    const old = review({ deadlineAt: '2000-01-01T00:00:00', disputeAllowed: false });
    expect(manualPaymentReviewPresentation(old).title).toBe('Оплата отмечена');
    expect(manualPaymentReviewDisputeRequest(old, true, 'Получатель сообщил о непоступлении')).toBeNull();
  });

  it('distinguishes no objection, verified statement, legacy confirmation and dispute', () => {
    expect(manualPaymentReviewPresentation(review({ status: 'CONFIRMED', confirmationBasis: 'AUTO_NO_OBJECTION' })).title)
      .toBe('Подтверждено без возражений');
    expect(manualPaymentReviewPresentation(review({ status: 'CONFIRMED', confirmationBasis: 'RECIPIENT_STATEMENT' })).title)
      .toBe('Поступление подтверждено по выписке');
    expect(manualPaymentReviewPresentation(review({ status: 'CONFIRMED', confirmationBasis: null })).title)
      .toBe('Оплата подтверждена');
    expect(manualPaymentReviewPresentation(review({ status: 'DISPUTED' })).detail).toContain('не отменяет');
  });

  it('does not treat legacy uncredited review or owner approval as completed', () => {
    expect(manualPaymentReviewPresentation(review({ creditedAt: null })).title).toContain('не завершена');
    expect(manualPaymentResultNotice({ status: 'OWNER_APPROVAL_PENDING', orderId: 27127 }).tone).toBe('warning');
    expect(manualPaymentResultNotice({ status: 'SPECIALIST_REVIEW_PENDING', orderId: 27127 }).tone).toBe('warning');
  });

  it('requires explicit recipient report, server permission and a bounded meaningful note', () => {
    expect(manualPaymentReviewDisputeRequest(review(), false, 'Получатель сообщил о непоступлении')).toBeNull();
    expect(manualPaymentReviewDisputeRequest(review(), true, 'коротко')).toBeNull();
    expect(manualPaymentReviewDisputeRequest(review(), true, 'x'.repeat(501))).toBeNull();
    expect(manualPaymentReviewDisputeRequest(review(), true, '  Получатель сообщил о непоступлении  '))
      .toEqual({ recipientReportedMissing: true, note: 'Получатель сообщил о непоступлении' });
  });
});

describe('ManualPaymentReviewComponent', () => {
  let api: { get: ReturnType<typeof vi.fn>; dispute: ReturnType<typeof vi.fn> };
  let auth: { hasAnyRealmRole: ReturnType<typeof vi.fn> };
  let component: ManualPaymentReviewComponent;

  beforeEach(() => {
    api = { get: vi.fn().mockReturnValue(of(review())), dispute: vi.fn() };
    auth = { hasAnyRealmRole: vi.fn().mockReturnValue(true) };
    TestBed.configureTestingModule({ providers: [
      { provide: ManualPaymentReviewApi, useValue: api }, { provide: AuthService, useValue: auth }
    ] });
    component = TestBed.runInInjectionContext(() => new ManualPaymentReviewComponent());
    component.orderId = 27127;
  });
  afterEach(() => component.ngOnDestroy());

  it('ignores stale reads after changing orders and does not display another order response', () => {
    const first = new Subject<ManualPaymentReviewView | null>();
    const second = new Subject<ManualPaymentReviewView | null>();
    api.get.mockReturnValueOnce(first).mockReturnValueOnce(second);
    component.ngOnChanges();
    component.orderId = 27128;
    component.ngOnChanges();
    first.next(review());
    expect(component.review()).toBeNull();
    second.next(review({ orderId: 27128 }));
    expect(component.review()?.orderId).toBe(27128);
    api.get.mockReturnValue(of(review()));
    component.load();
    expect(component.review()).toBeNull();
    expect(component.error()).toContain('другого заказа');
  });

  it('posts a dispute only once, with the displayed review ID, then uses the server outcome', () => {
    const write = new Subject<ManualPaymentReviewView>();
    api.dispute.mockReturnValue(write);
    component.ngOnChanges();
    component.openDispute();
    component.note.set('Получатель сообщил о непоступлении');
    component.checked.set(true);
    component.submitDispute();
    component.submitDispute();
    expect(api.dispute).toHaveBeenCalledTimes(1);
    expect(api.dispute).toHaveBeenCalledWith(27127, {
      expectedReviewId: 4, recipientReportedMissing: true, note: 'Получатель сообщил о непоступлении'
    });
    expect(component.review()?.status).toBe('WAITING');
    write.next(review({ status: 'DISPUTED', disputeAllowed: false }));
    expect(component.presentation()?.title).toBe('Оплата оспорена');
    expect(component.canDispute()).toBe(false);
    expect(component.editing()).toBe(false);
  });

  it('preserves the draft and the single in-flight dispute during a same-order background refresh', () => {
    const write = new Subject<ManualPaymentReviewView>();
    api.dispute.mockReturnValue(write);
    component.ngOnChanges();
    component.openDispute();
    component.note.set('Получатель сообщил о непоступлении');
    component.checked.set(true);
    component.ngOnChanges();
    expect(component.note()).toBe('Получатель сообщил о непоступлении');
    component.submitDispute();
    component.ngOnChanges();
    component.submitDispute();
    expect(component.saving()).toBe(true);
    expect(api.get).toHaveBeenCalledTimes(2);
    expect(api.dispute).toHaveBeenCalledTimes(1);
    write.next(review({ status: 'DISPUTED', disputeAllowed: false }));
    expect(component.presentation()?.title).toBe('Оплата оспорена');
    expect(component.saving()).toBe(false);
  });

  it('clears stale success and disables mutation after an uncertain write response', () => {
    api.dispute.mockReturnValue(throwError(() => new Error('connection lost')));
    component.ngOnChanges();
    component.checked.set(true);
    component.note.set('Получатель сообщил о непоступлении');
    component.submitDispute();
    expect(component.review()).toBeNull();
    expect(component.canDispute()).toBe(false);
    expect(component.error()).toContain('Обновите состояние перед повтором');
  });

  it('does not request manager-only metadata for a specialist or infer a review from null', () => {
    auth.hasAnyRealmRole.mockReturnValue(false);
    component.ngOnChanges();
    expect(api.get).not.toHaveBeenCalled();
    auth.hasAnyRealmRole.mockReturnValue(true);
    api.get.mockReturnValue(of(null));
    component.ngOnChanges();
    expect(component.presentation()).toBeNull();
  });
});
