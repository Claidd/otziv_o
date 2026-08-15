import { Component, Input } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject, Observable, Subject, of } from 'rxjs';
import { AuthService } from '../../core/auth.service';
import { ReviewCheckApi, ReviewCheckPayload, ReviewCheckReview } from '../../core/review-check.api';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';
import { ToastService } from '../../shared/toast.service';
import { ReviewCheckComponent } from './review-check.component';

@Component({
  selector: 'app-admin-layout',
  standalone: true,
  template: '<ng-content></ng-content><ng-content select="[admin-right-content]"></ng-content>'
})
class AdminLayoutStubComponent {
  @Input() title = '';
  @Input() active = '';
  @Input() hideSidebarBeforeLogin = true;
  @Input() rightPanelMode: 'default' | 'custom' = 'default';
  @Input() profileImageUrl: string | null = null;
  @Input() profileImageAlt = '';
}

function review(overrides: Partial<ReviewCheckReview> = {}): ReviewCheckReview {
  return {
    id: 17,
    text: 'Текст отзыва',
    answer: '',
    botName: '',
    comment: '',
    orderComments: '',
    commentCompany: '',
    filialTitle: 'Филиал',
    productTitle: '',
    productPhoto: false,
    url: '',
    publishedDate: '',
    publish: false,
    ...overrides
  };
}

function details(overrides: Partial<ReviewCheckPayload> = {}): ReviewCheckPayload {
  return {
    orderDetailId: 'detail-1',
    archived: false,
    orderId: 11,
    companyId: 3,
    companyTitle: 'Компания',
    filialTitle: 'Филиал',
    status: 'Публикация',
    workerFio: 'Специалист',
    orderComments: '',
    companyComments: '',
    comment: '',
    amount: 2,
    counter: 0,
    approved: true,
    reviews: [review()],
    permissions: {
      authenticated: true,
      canSeeInternalInfo: false,
      canSeeBot: false,
      canApprovePublication: false,
      canSave: false,
      canSendCorrection: false,
      canSendToCheck: false,
      canMarkPaid: false,
      canOpenManagerLinks: false,
      canEditNotes: false
    },
    ...overrides
  };
}

describe('ReviewCheckComponent', () => {
  let routeParams: Subject<ReturnType<typeof convertToParamMap>>;
  let routeFragment: BehaviorSubject<string | null>;
  let routeSnapshot: { routeConfig: { path: string } };
  let reviewCheckApi: {
    getReviewCheck: ReturnType<typeof vi.fn>;
    saveReviews: ReturnType<typeof vi.fn>;
    sendToCorrection: ReturnType<typeof vi.fn>;
  };

  beforeEach(async () => {
    routeParams = new Subject();
    routeFragment = new BehaviorSubject<string | null>(null);
    routeSnapshot = { routeConfig: { path: 'review/:orderDetailId' } };
    reviewCheckApi = {
      getReviewCheck: vi.fn(),
      saveReviews: vi.fn(),
      sendToCorrection: vi.fn()
    };
    await TestBed.configureTestingModule({
      imports: [ReviewCheckComponent],
      providers: [
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { paramMap: routeParams, fragment: routeFragment, snapshot: routeSnapshot }
        },
        { provide: ReviewCheckApi, useValue: reviewCheckApi },
        { provide: AuthService, useValue: { login: vi.fn(), logout: vi.fn() } },
        { provide: ToastService, useValue: { success: vi.fn(), error: vi.fn() } }
      ]
    })
      .overrideComponent(ReviewCheckComponent, {
        remove: { imports: [AdminLayoutComponent] },
        add: { imports: [AdminLayoutStubComponent] }
      })
      .compileComponents();
  });

  afterEach(() => {
    window.history.replaceState({}, '', '/');
    window.sessionStorage.clear();
    vi.restoreAllMocks();
  });

  it('cancels the previous anonymous route GET and applies only the newest response', () => {
    const first = new Subject<ReviewCheckPayload>();
    const second = new Subject<ReviewCheckPayload>();
    const firstTeardown = vi.fn();
    const secondTeardown = vi.fn();
    reviewCheckApi.getReviewCheck
      .mockReturnValueOnce(withTeardown(first, firstTeardown))
      .mockReturnValueOnce(withTeardown(second, secondTeardown));
    const fixture = TestBed.createComponent(ReviewCheckComponent);
    const component = fixture.componentInstance;

    routeParams.next(convertToParamMap({ orderDetailId: 'detail-1' }));
    routeParams.next(convertToParamMap({ orderDetailId: 'detail-2' }));

    expect(firstTeardown).toHaveBeenCalledTimes(1);
    expect(secondTeardown).not.toHaveBeenCalled();
    expect(reviewCheckApi.getReviewCheck.mock.calls).toEqual([
      ['detail-1'],
      ['detail-2']
    ]);

    first.next(details({ orderDetailId: 'detail-1', companyTitle: 'Устаревшая' }));
    second.next(details({ orderDetailId: 'detail-2', companyTitle: 'Актуальная' }));

    expect(component.details()?.orderDetailId).toBe('detail-2');
    expect(component.details()?.companyTitle).toBe('Актуальная');

    fixture.destroy();
    expect(secondTeardown).toHaveBeenCalledTimes(1);
  });

  it('keeps the opaque capability token on a cancellable route GET', () => {
    const token = `rc1_${'A'.repeat(43)}`;
    const response = new Subject<ReviewCheckPayload>();
    routeSnapshot.routeConfig.path = 'review/c';
    window.history.replaceState({}, '', `/review/c#${token}`);
    reviewCheckApi.getReviewCheck.mockReturnValue(response);
    const fixture = TestBed.createComponent(ReviewCheckComponent);

    routeParams.next(convertToParamMap({}));

    expect(reviewCheckApi.getReviewCheck).toHaveBeenCalledWith('secure-capability', token);
    expect(fixture.componentInstance.capabilityToken()).toBe(token);

    fixture.destroy();
  });

  it('clears the previous review route state before the next route response arrives', () => {
    const nextRoute = new Subject<ReviewCheckPayload>();
    reviewCheckApi.getReviewCheck
      .mockReturnValueOnce(of(details({ orderDetailId: 'detail-1', companyTitle: 'Первая' })))
      .mockReturnValueOnce(nextRoute);
    const fixture = TestBed.createComponent(ReviewCheckComponent);
    const component = fixture.componentInstance;

    routeParams.next(convertToParamMap({ orderDetailId: 'detail-1' }));
    component.actionKey.set('save');
    component.editingReviewFieldKey.set('text-17');
    expect(component.details()?.companyTitle).toBe('Первая');

    routeParams.next(convertToParamMap({ orderDetailId: 'detail-2' }));

    expect(component.orderDetailId()).toBe('detail-2');
    expect(component.details()).toBeNull();
    expect(component.draft()).toBeNull();
    expect(component.actionKey()).toBeNull();
    expect(component.editingReviewFieldKey()).toBeNull();
    expect(component.loading()).toBe(true);

    nextRoute.next(details({ orderDetailId: 'detail-2', companyTitle: 'Вторая' }));
    expect(component.details()?.companyTitle).toBe('Вторая');
  });

  it('does not cancel a mutation but ignores its late payload after the route key changes', () => {
    const mutation = new Subject<ReviewCheckPayload>();
    const mutationTeardown = vi.fn();
    reviewCheckApi.getReviewCheck
      .mockReturnValueOnce(of(details({ orderDetailId: 'detail-1', companyTitle: 'Первая' })))
      .mockReturnValueOnce(of(details({ orderDetailId: 'detail-2', companyTitle: 'Вторая' })));
    reviewCheckApi.saveReviews.mockReturnValue(withTeardown(mutation, mutationTeardown));
    const fixture = TestBed.createComponent(ReviewCheckComponent);
    const component = fixture.componentInstance;

    routeParams.next(convertToParamMap({ orderDetailId: 'detail-1' }));
    component.saveReviews();
    expect(component.actionKey()).toBe('save');

    routeParams.next(convertToParamMap({ orderDetailId: 'detail-2' }));

    expect(mutationTeardown).not.toHaveBeenCalled();
    expect(component.actionKey()).toBeNull();
    expect(component.details()?.orderDetailId).toBe('detail-2');

    mutation.next(details({ orderDetailId: 'detail-1', companyTitle: 'Устаревшая мутация' }));

    expect(component.details()?.orderDetailId).toBe('detail-2');
    expect(component.details()?.companyTitle).toBe('Вторая');
    expect(component.actionKey()).toBeNull();

    mutation.complete();
    expect(mutationTeardown).toHaveBeenCalledTimes(1);
  });

  it('ignores an old mutation response after navigating A to B and back to A', () => {
    const abandonedMutation = new Subject<ReviewCheckPayload>();
    reviewCheckApi.getReviewCheck
      .mockReturnValueOnce(of(details({ orderDetailId: 'detail-a', companyTitle: 'A, первое посещение' })))
      .mockReturnValueOnce(of(details({ orderDetailId: 'detail-b', companyTitle: 'B' })))
      .mockReturnValueOnce(of(details({ orderDetailId: 'detail-a', companyTitle: 'A, новое посещение' })));
    reviewCheckApi.saveReviews.mockReturnValue(abandonedMutation);
    const fixture = TestBed.createComponent(ReviewCheckComponent);
    const component = fixture.componentInstance;

    routeParams.next(convertToParamMap({ orderDetailId: 'detail-a' }));
    component.saveReviews();
    routeParams.next(convertToParamMap({ orderDetailId: 'detail-b' }));
    routeParams.next(convertToParamMap({ orderDetailId: 'detail-a' }));

    expect(component.details()?.companyTitle).toBe('A, новое посещение');
    expect(component.actionKey()).toBeNull();

    abandonedMutation.next(details({
      orderDetailId: 'detail-a',
      companyTitle: 'A, устаревший ответ мутации'
    }));

    expect(component.details()?.companyTitle).toBe('A, новое посещение');
    expect(component.actionKey()).toBeNull();
  });

  it('reloads a reused capability route when its fragment token changes', () => {
    const firstToken = `rc1_${'A'.repeat(43)}`;
    const secondToken = `rc1_${'B'.repeat(43)}`;
    const first = new Subject<ReviewCheckPayload>();
    const second = new Subject<ReviewCheckPayload>();
    const firstTeardown = vi.fn();
    routeSnapshot.routeConfig.path = 'review/c';
    window.history.replaceState({}, '', `/review/c#${firstToken}`);
    reviewCheckApi.getReviewCheck
      .mockReturnValueOnce(withTeardown(first, firstTeardown))
      .mockReturnValueOnce(second);
    const fixture = TestBed.createComponent(ReviewCheckComponent);
    const component = fixture.componentInstance;

    routeParams.next(convertToParamMap({}));
    first.next(details({ orderDetailId: 'capability-a', companyTitle: 'Первая ссылка' }));
    expect(component.capabilityToken()).toBe(firstToken);
    expect(component.details()?.companyTitle).toBe('Первая ссылка');

    window.history.replaceState({}, '', `/review/c#${secondToken}`);
    routeFragment.next(secondToken);

    expect(firstTeardown).toHaveBeenCalledTimes(1);
    expect(component.capabilityToken()).toBe(secondToken);
    expect(component.details()).toBeNull();
    expect(component.loading()).toBe(true);
    expect(reviewCheckApi.getReviewCheck.mock.calls).toEqual([
      ['secure-capability', firstToken],
      ['secure-capability', secondToken]
    ]);

    first.next(details({ orderDetailId: 'capability-a', companyTitle: 'Устаревшая ссылка' }));
    second.next(details({ orderDetailId: 'capability-b', companyTitle: 'Вторая ссылка' }));

    expect(component.details()?.companyTitle).toBe('Вторая ссылка');
  });

  it('keeps scheduled approved reviews separate from published reviews', () => {
    const fixture = TestBed.createComponent(ReviewCheckComponent);
    const component = fixture.componentInstance;
    const payload = details({
      reviews: [
        review({ id: 1, publish: false, publishedDate: '2026-05-30' }),
        review({ id: 2, publish: true, publishedDate: '2026-05-18' })
      ]
    });

    expect(component.reviewFooterStateLabel(payload, payload.reviews[0])).toBe('одобрен');
    expect(component.isReviewFooterStatePublished(payload, payload.reviews[0])).toBe(false);
    expect(component.reviewFooterStateLabel(payload, payload.reviews[1])).toBe('опубликован');
    expect(component.isReviewFooterStatePublished(payload, payload.reviews[1])).toBe(true);
  });

  it('opens stored orders in the archive while keeping live order links unchanged', () => {
    const fixture = TestBed.createComponent(ReviewCheckComponent);
    const component = fixture.componentInstance;
    const archived = details({ archived: true, orderId: 22752, companyId: 202 });
    const live = details({ archived: false, orderId: 22753, companyId: 202 });

    expect(component.managerOrderRoute(archived)).toEqual(['/manager/archive']);
    expect(component.managerOrderQuery(archived)).toEqual({ archiveOrderId: 22752 });
    expect(component.managerOrderRoute(live)).toEqual(['/orders', 202, 22753]);
    expect(component.managerOrderQuery(live)).toEqual({});
  });

  it('shows manager navigation and payment actions for an accessible archived order', async () => {
    const fixture = TestBed.createComponent(ReviewCheckComponent);
    fixture.componentInstance.details.set(details({
      archived: true,
      orderId: 22752,
      companyId: 202,
      permissions: {
        ...details().permissions,
        canOpenManagerLinks: true,
        canMarkPaid: true
      }
    }));

    fixture.detectChanges();
    await fixture.whenStable();

    const rightActions = (fixture.nativeElement as HTMLElement)
      .querySelector<HTMLElement>('section[admin-right-content] .staff-actions-card');
    expect(rightActions?.textContent).toContain('Компания');
    expect(rightActions?.textContent).toContain('Заказ');
    expect(rightActions?.textContent).toContain('Оплатили');
    const orderLink = Array.from(rightActions?.querySelectorAll<HTMLAnchorElement>('a') ?? [])
      .find((link) => link.textContent?.includes('Заказ'));
    expect(orderLink?.getAttribute('href')).toBe('/manager/archive?archiveOrderId=22752');
  });

  it('counts every review as approved after publication is allowed', () => {
    const fixture = TestBed.createComponent(ReviewCheckComponent);
    const component = fixture.componentInstance;
    const payload = details({
      reviews: [
        review({ id: 1, publish: false, publishedDate: '2026-05-30' }),
        review({ id: 2, publish: true, publishedDate: '2026-05-18' })
      ]
    });

    expect(component.reviewedCount(payload)).toBe(2);
  });

  it('keeps the publication action as the final mobile carousel card', () => {
    vi.spyOn(window, 'requestAnimationFrame').mockImplementation(() => 0);
    const fixture = TestBed.createComponent(ReviewCheckComponent);
    const component = fixture.componentInstance;
    const payload = details({
      approved: false,
      reviews: [review({ id: 1 }), review({ id: 2 })],
      permissions: {
        ...details().permissions,
        canApprovePublication: true
      }
    });

    component.details.set(payload);
    component.mobileReviewLayout.set(true);
    fixture.detectChanges();

    expect(component.reviewCarouselItemCount(payload)).toBe(3);
    expect(component.showReviewNavigation()).toBe(true);
    expect((fixture.nativeElement as HTMLElement).querySelector('.review-approve-card')).not.toBeNull();

    component.goToReviewIndex(1);
    component.nextReview();
    fixture.detectChanges();

    expect(component.activeReviewSlide()).toBe(2);
    expect(component.reviewJumpValue()).toBe('3');
    expect((fixture.nativeElement as HTMLElement).querySelector('.review-navigation')?.textContent).toContain('3 из 3');
  });

  it('keeps company-only card titles when every review has the same filial', () => {
    const fixture = TestBed.createComponent(ReviewCheckComponent);
    const component = fixture.componentInstance;

    component.details.set(details({
      companyTitle: 'СТК',
      reviews: [
        review({ id: 1, filialTitle: 'Промышленная 2-я' }),
        review({ id: 2, filialTitle: '  промышленная   2-Я  ' })
      ]
    }));
    fixture.detectChanges();

    const titles = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>('.review-card-title')
    ).map((element) => element.textContent?.trim());
    expect(titles).toEqual(['СТК', 'СТК']);
  });

  it('adds each review filial to mixed-filial card titles without losing a long full title', () => {
    const fixture = TestBed.createComponent(ReviewCheckComponent);
    const component = fixture.componentInstance;
    const longFilialTitle = 'Проспект Маршала Жукова, дом 127, строение 4, помещение 18';

    component.details.set(details({
      companyTitle: 'СТК',
      reviews: [
        review({ id: 1, filialTitle: 'Промышленная 2-я' }),
        review({ id: 2, filialTitle: longFilialTitle })
      ]
    }));
    fixture.detectChanges();

    const titles = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>('.review-card-title')
    );
    expect(titles.map((element) => element.textContent?.trim())).toEqual([
      'СТК - Промышленная 2-я',
      `СТК - ${longFilialTitle}`
    ]);
    expect(titles[1]?.getAttribute('title')).toBe(`СТК - ${longFilialTitle}`);
    const longTitleStyle = window.getComputedStyle(titles[1]!);
    expect(longTitleStyle.overflow).toBe('hidden');
    expect(longTitleStyle.textOverflow).toBe('ellipsis');
    expect(longTitleStyle.whiteSpace).toBe('nowrap');
  });

  it('disables expansion when the rendered review text fits its box', () => {
    const fixture = TestBed.createComponent(ReviewCheckComponent);
    const component = fixture.componentInstance;
    const item = review({ text: 'Длинный по символам, но полностью видимый текст. '.repeat(4) });

    component.details.set(details({ reviews: [item] }));
    component.mobileReviewLayout.set(true);

    expect(component.shouldShowReviewTextToggle(item)).toBe(true);
    component.setReviewTextOverflow(item, false);
    expect(component.isReviewTextToggleEnabled(item)).toBe(false);

    component.setReviewTextOverflow(item, true);
    expect(component.isReviewTextToggleEnabled(item)).toBe(true);
  });

  it('sends review remarks and correction comment when returning to correction', () => {
    const api = TestBed.inject(ReviewCheckApi) as unknown as {
      sendToCorrection: ReturnType<typeof vi.fn>;
    };
    const payload = details({
      permissions: {
        ...details().permissions,
        canSendCorrection: true
      }
    });
    api.sendToCorrection = vi.fn(() => of(payload));

    const fixture = TestBed.createComponent(ReviewCheckComponent);
    const component = fixture.componentInstance;
    component.orderDetailId.set(payload.orderDetailId);
    component.details.set(payload);
    component.draft.set({
      comment: '',
      reviews: payload.reviews.map((item) => ({
        id: item.id,
        text: item.text,
        answer: item.answer
      }))
    });

    component.setReviewFieldDraft(payload.reviews[0], 'answer', 'Нужна корректировка');
    component.setComment('Общее замечание');
    component.sendToCorrection();

    expect(api.sendToCorrection).toHaveBeenCalledWith(payload.orderDetailId, {
      comment: 'Общее замечание',
      reviews: [
        {
          id: 17,
          text: 'Текст отзыва',
          answer: 'Нужна корректировка',
          publish: false,
          publishedDate: null,
          url: ''
        }
      ]
    });
  });

  it('expands and collapses long review text from the review card', () => {
    const fixture = TestBed.createComponent(ReviewCheckComponent);
    const component = fixture.componentInstance;
    component.details.set(details({
      reviews: [review({ id: 101, text: 'Очень длинный текст отзыва. '.repeat(12) })]
    }));

    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    const field = () => element.querySelector('.review-field-editor--text');
    const toggle = () => element.querySelector<HTMLElement>('.review-text-toggle');

    expect(toggle()?.textContent?.trim()).toBe('развернуть');
    expect(field()?.classList.contains('review-field-editor--text-expanded')).toBe(false);

    toggle()?.click();
    fixture.detectChanges();

    expect(toggle()?.textContent?.trim()).toBe('свернуть');
    expect(field()?.classList.contains('review-field-editor--text-expanded')).toBe(true);

    toggle()?.click();
    fixture.detectChanges();

    expect(toggle()?.textContent?.trim()).toBe('развернуть');
    expect(field()?.classList.contains('review-field-editor--text-expanded')).toBe(false);
  });

  it('opens the mobile text editor immediately when the client taps review text', () => {
    const fixture = TestBed.createComponent(ReviewCheckComponent);
    const component = fixture.componentInstance;
    const payload = details({
      status: 'На проверке',
      approved: false,
      permissions: {
        ...details().permissions,
        canSave: true
      },
      reviews: [review({ id: 101, text: 'Текст для проверки' })]
    });
    component.details.set(payload);
    component.draft.set({
      comment: '',
      reviews: payload.reviews.map((item) => ({
        id: item.id,
        text: item.text,
        answer: item.answer
      }))
    });
    component.mobileReviewLayout.set(true);

    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    element.querySelector<HTMLElement>('.review-display-field--text')?.click();
    fixture.detectChanges();

    expect(component.isReviewFieldEditing(payload.reviews[0], 'text')).toBe(true);
    expect(component.activeReviewFieldEdit()).toEqual({
      review: payload.reviews[0],
      field: 'text',
      title: 'Текст отзыва',
      mutationKey: 'save-text-101'
    });
  });

  it('does not describe a save error as a review-check loading failure', () => {
    const fixture = TestBed.createComponent(ReviewCheckComponent);
    const component = fixture.componentInstance;
    component.details.set(details());
    component.error.set('Замечание не сохранено');

    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    expect(element.textContent).not.toContain('Проверка отзывов не загрузилась');
    expect(element.querySelector('.review-check-form')).not.toBeNull();
  });
});

function withTeardown<T>(source: Observable<T>, teardown: () => void): Observable<T> {
  return new Observable<T>((subscriber) => {
    const subscription = source.subscribe(subscriber);
    return () => {
      subscription.unsubscribe();
      teardown();
    };
  });
}
