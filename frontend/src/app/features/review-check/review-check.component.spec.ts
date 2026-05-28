import { Component, Input } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
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
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ReviewCheckComponent],
      providers: [
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { paramMap: of(convertToParamMap({})) }
        },
        { provide: ReviewCheckApi, useValue: {} },
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
    vi.restoreAllMocks();
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
});
