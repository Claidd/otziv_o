import { DecimalPipe } from '@angular/common';
import { NO_ERRORS_SCHEMA, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, convertToParamMap, provideRouter, RouterLink } from '@angular/router';
import { of, Subject } from 'rxjs';
import { AuthService } from '../../core/auth.service';
import { ManagerApi, OrderDetailsPayload, OrderReviewItem } from '../../core/manager.api';
import { PaymentsApi } from '../../core/payments.api';
import { ReputationDeepReportMonitorService } from '../../core/reputation-deep-report-monitor.service';
import { WorkerAccountActionCooldownService } from '../../core/worker-account-action-cooldown.service';
import { PersonalRemindersService } from '../../shared/personal-reminders.service';
import { readSessionDraft } from '../../shared/session-draft-storage';
import { ToastService } from '../../shared/toast.service';
import { OrderDetailsComponent } from './order-details.component';

describe('OrderDetailsComponent published text access', () => {
  let roles: string[];
  let routeParams: Subject<ReturnType<typeof convertToParamMap>>;
  let save: Subject<OrderReviewItem>;
  let clipboardWrite: ReturnType<typeof vi.fn>;
  let originalClipboard: PropertyDescriptor | undefined;
  let originalSecureContext: PropertyDescriptor | undefined;
  let managerApi: { getOrderDetails: ReturnType<typeof vi.fn>; getOrderCompanyReport: ReturnType<typeof vi.fn>; updateOrderReviewText: ReturnType<typeof vi.fn> };
  const draftText = 'Исправленный текст, который нельзя потерять';

  beforeEach(async () => {
    vi.clearAllMocks();
    sessionStorage.clear();
    originalClipboard = Object.getOwnPropertyDescriptor(navigator, 'clipboard');
    originalSecureContext = Object.getOwnPropertyDescriptor(window, 'isSecureContext');
    clipboardWrite = vi.fn(() => Promise.resolve());
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: clipboardWrite } });
    Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
    roles = ['WORKER'];
    routeParams = new Subject();
    save = new Subject();
    managerApi = {
      getOrderDetails: vi.fn(() => of(details(false))),
      getOrderCompanyReport: vi.fn(() => of({
        companyId: 1, companyName: 'Компания', latestJob: null, activeJob: null,
        canStart: true, canRefresh: false, unavailableReason: ''
      })),
      updateOrderReviewText: vi.fn(() => save)
    };
    await TestBed.configureTestingModule({
      imports: [OrderDetailsComponent],
      providers: [
        provideRouter([]),
        { provide: ActivatedRoute, useValue: {
          paramMap: routeParams,
          queryParamMap: of(convertToParamMap({})),
          snapshot: { paramMap: convertToParamMap({}), queryParamMap: convertToParamMap({}) }
        } },
        { provide: AuthService, useValue: {
          authenticated: signal(true), tokenParsed: signal({}), status: signal('authenticated'),
          isAuthenticated: () => true,
          hasRealmRole: (role: string) => roles.includes(role),
          hasAnyRealmRole: (requested: readonly string[]) => requested.some(role => roles.includes(role))
        } },
        { provide: ManagerApi, useValue: managerApi },
        { provide: PaymentsApi, useValue: { getTbankStatus: vi.fn(() => of(null)) } },
        { provide: WorkerAccountActionCooldownService, useValue: { locked: signal(false), title: signal('') } },
        { provide: ReputationDeepReportMonitorService, useValue: { currentJob: signal(null) } },
        { provide: PersonalRemindersService, useValue: {} },
        { provide: ToastService, useValue: { success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn(), dismiss: vi.fn() } }
      ]
    }).overrideComponent(OrderDetailsComponent, {
      set: { imports: [DecimalPipe, FormsModule, RouterLink], schemas: [NO_ERRORS_SCHEMA] }
    }).compileComponents();
  });

  afterEach(() => {
    sessionStorage.clear();
    if (originalClipboard) Object.defineProperty(navigator, 'clipboard', originalClipboard);
    else Reflect.deleteProperty(navigator, 'clipboard');
    if (originalSecureContext) Object.defineProperty(window, 'isSecureContext', originalSecureContext);
    else Reflect.deleteProperty(window, 'isSecureContext');
    vi.restoreAllMocks();
  });

  function open(published: boolean) {
    managerApi.getOrderDetails.mockReturnValueOnce(of(details(published)));
    const fixture = TestBed.createComponent(OrderDetailsComponent);
    const component = fixture.componentInstance;
    routeParams.next(convertToParamMap({ orderId: '27396' }));
    component.mobileReviewLayout.set(true);
    fixture.detectChanges();
    return { fixture, component, review: component.reviews()[0] };
  }

  it('explains published text access and refuses to open or save it for a plain worker', () => {
    const { fixture, component, review } = open(true);
    const text = fixture.nativeElement.querySelector('.review-display-field--text') as HTMLElement;
    expect(text.getAttribute('aria-disabled')).toBe('true');
    text.click();
    fixture.detectChanges();
    expect(component.activeReviewFieldEdit()).toBeNull();
    expect(fixture.nativeElement.querySelector('.mobile-review-text-modal')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Отзыв уже опубликован');
    component.saveReviewField(review, 'text');
    expect(managerApi.updateOrderReviewText).not.toHaveBeenCalled();
  });

  it.each(['ADMIN', 'OWNER', 'MANAGER'])('preserves published text access for WORKER + %s', (role) => {
    roles.push(role);
    const { fixture, component, review } = open(true);
    component.startReviewFieldEdit(review, 'text');
    component.setReviewFieldDraft(review, 'text', draftText);
    fixture.detectChanges();
    expect(component.reviewFieldEditBlockReason(review, 'text')).toBeNull();
    expect(fixture.nativeElement.querySelector('.mobile-review-text-modal textarea').readOnly).toBe(false);
    component.saveReviewField(review, 'text');
    expect(managerApi.updateOrderReviewText).toHaveBeenCalledExactlyOnceWith(27396, 200226, draftText);
  });

  it('retains an open draft and disables saving when the current review becomes published', async () => {
    const { fixture, component, review } = open(false);
    component.startReviewFieldEdit(review, 'text');
    component.setReviewFieldDraft(review, 'text', draftText);
    component.details.set(details(true));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const modal = fixture.nativeElement.querySelector('.mobile-review-text-modal');
    expect(modal.querySelector('textarea').value).toBe(draftText);
    expect(modal.querySelector('textarea').readOnly).toBe(true);
    expect(modal.querySelector('button.save').disabled).toBe(true);
    expect(modal.textContent).toContain('черновик остался в браузере');
    // The caller still holds the old unpublished object; current state wins.
    component.saveReviewField(review, 'text');
    expect(managerApi.updateOrderReviewText).not.toHaveBeenCalled();
    component.cancelReviewFieldEdit(review, 'text');
    fixture.detectChanges();
    expect(component.reviewFieldValue(review, 'text')).toBe(draftText);
    expect(readSessionDraft<{ reviewFields: Record<string, string> }>('order-details:27396')?.reviewFields['200226-text']).toBe(draftText);
    fixture.nativeElement.querySelector('.review-edit-restriction button').click();
    expect(clipboardWrite).toHaveBeenCalledExactlyOnceWith(draftText);
  });

  it('refreshes a rejected save without retrying it and preserves the draft after publication', () => {
    const { fixture, component, review } = open(false);
    const refresh = new Subject<OrderDetailsPayload>();
    managerApi.getOrderDetails.mockReturnValueOnce(refresh);
    component.startReviewFieldEdit(review, 'text');
    component.setReviewFieldDraft(review, 'text', draftText);
    component.saveReviewField(review, 'text');
    expect(managerApi.updateOrderReviewText).toHaveBeenCalledExactlyOnceWith(27396, 200226, draftText);
    save.error({ status: 409, error: { message: 'Назначение или состояние объекта изменилось' } });
    expect(managerApi.getOrderDetails).toHaveBeenCalledTimes(2);
    refresh.next(details(true));
    fixture.detectChanges();
    expect(component.error()).toContain('Отзыв уже опубликован');
    expect(component.reviewFieldValue(component.reviews()[0], 'text')).toBe(draftText);
    expect(fixture.nativeElement.querySelector('.mobile-review-text-modal button.save').disabled).toBe(true);
    expect(managerApi.updateOrderReviewText).toHaveBeenCalledTimes(1);
    expect(readSessionDraft<{ reviewFields: Record<string, string> }>('order-details:27396')?.reviewFields['200226-text']).toBe(draftText);
  });

  it('keeps the original editor and persisted draft if the rejected review disappears', () => {
    const { fixture, component, review } = open(false);
    managerApi.getOrderDetails.mockReturnValueOnce(of({ ...details(false), reviews: [] }));
    component.startReviewFieldEdit(review, 'text');
    component.setReviewFieldDraft(review, 'text', draftText);
    component.saveReviewField(review, 'text');
    save.error({ status: 409 });
    fixture.detectChanges();
    expect(component.activeReviewFieldEdit()?.review.id).toBe(200226);
    expect(component.error()).toContain('Отзыв больше недоступен');
    expect(fixture.nativeElement.querySelector('.mobile-review-text-modal button.save').disabled).toBe(true);
    component.saveReviewField(review, 'text');
    expect(managerApi.updateOrderReviewText).toHaveBeenCalledTimes(1);
    component.cancelReviewFieldEdit(review, 'text');
    fixture.detectChanges();
    fixture.nativeElement.querySelector('.review-edit-restriction button').click();
    expect(clipboardWrite).toHaveBeenCalledExactlyOnceWith(draftText);
    expect(readSessionDraft<{ reviewFields: Record<string, string> }>('order-details:27396')?.reviewFields['200226-text']).toBe(draftText);
  });

  it('does not apply a late conflict refresh to a different order route', () => {
    const { component, review } = open(false);
    const refresh = new Subject<OrderDetailsPayload>();
    managerApi.getOrderDetails.mockReturnValueOnce(refresh);
    component.startReviewFieldEdit(review, 'text');
    component.setReviewFieldDraft(review, 'text', draftText);
    component.saveReviewField(review, 'text');
    save.error({ status: 409 });
    managerApi.getOrderDetails.mockReturnValueOnce(of({ ...details(false), orderId: 27397, reviews: [] }));
    routeParams.next(convertToParamMap({ orderId: '27397' }));
    refresh.next({ ...details(false), reviews: [] });
    expect(component.details()?.orderId).toBe(27397);
    expect(component.error()).toBeNull();
    expect(component.activeReviewFieldEdit()).toBeNull();
    expect(managerApi.updateOrderReviewText).toHaveBeenCalledTimes(1);
    expect(readSessionDraft<{ reviewFields: Record<string, string> }>('order-details:27396')?.reviewFields['200226-text']).toBe(draftText);
  });
});

function details(publish: boolean): OrderDetailsPayload {
  const review: OrderReviewItem = {
    id: 200226, orderId: 27396, companyId: 1, text: 'Исходный текст', answer: '',
    category: '', subCategory: '', botFio: '', botLoginPresent: false, botPasswordPresent: false,
    botCounter: 0, companyTitle: 'Компания', commentCompany: '', orderComments: '',
    filialCity: '', filialTitle: '', filialUrl: '', productTitle: '', productPhoto: false,
    workerFio: '', created: '', changed: '', publishedDate: '', publish, vigul: false,
    comment: '', url: '', urlPhoto: ''
  };
  return {
    orderId: 27396, companyId: 1, title: 'Заказ', companyTitle: 'Компания', productTitle: '',
    status: 'Ожидает общего счета', orderComments: '', companyComments: '', created: '', changed: '',
    reviews: [review], badReviewTasks: [], recoveryTasks: [], filials: [], products: [],
    canEditReviews: true, canSendToCheck: false, canEditReviewDates: false,
    canEditReviewPublish: true, canEditReviewVigul: true, canDeleteReviews: false
  };
}
