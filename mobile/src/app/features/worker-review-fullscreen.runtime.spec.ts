import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { of } from 'rxjs';
import type { WorkerBoard, WorkerBoardSection, WorkerReviewItem } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { OrderReviewsApi } from '../core/order-reviews.api';
import { WorkerApi } from '../core/worker.api';
import { MobileConfirmService } from '../shared/mobile-confirm.service';
import { MobileManualCardPaymentFlowService } from '../shared/mobile-manual-card-payment-flow.service';
import { MobileMediaService } from '../shared/mobile-media.service';
import { DEFAULT_WORKER_PERMISSIONS, MOBILE_WORKER_ACTIVITY_SOURCE_PAGE } from './worker/worker-board.helpers';
import { WorkerPage } from './worker.page';

const originalText = 'Исходный текст отзыва';
const fullText = `Полный текст отзыва. ${'Подробности визита и впечатления клиента. '.repeat(12)}\nЗаключение.`;

const makeReview = (): WorkerReviewItem => ({
  id: 30925,
  orderId: 703,
  companyTitle: 'Domito',
  productTitle: 'Товар',
  botFio: 'Аккаунт',
  botLoginPresent: false,
  botPasswordPresent: false,
  text: originalText,
  answer: ''
});

describe('worker full review editor', () => {
  let page: WorkerPage;
  let updateText: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    updateText = vi.fn().mockReturnValue(of(null));
    TestBed.configureTestingModule({ providers: [
      WorkerPage,
      { provide: WorkerApi, useValue: { updateWorkerReviewText: updateText } },
      { provide: OrderReviewsApi, useValue: {} },
      { provide: AuthService, useValue: {} },
      { provide: ActivatedRoute, useValue: {} },
      { provide: Router, useValue: {} },
      { provide: MobileConfirmService, useValue: {} },
      { provide: MobileManualCardPaymentFlowService, useValue: {} },
      { provide: MobileMediaService, useValue: {} }
    ] });
    TestBed.overrideComponent(WorkerPage, { set: { template: '', imports: [] } });
    page = TestBed.createComponent(WorkerPage).componentInstance;
  });

  afterEach(() => page.ngOnDestroy());

  it.each(['nagul', 'publish'] as WorkerBoardSection[])(
    'opens, saves and closes the same full text editor in %s', async (section) => {
      const review = makeReview();
      page.activeSection.set(section);
      page.board.set({
        title: 'Специалист',
        metrics: [],
        orders: { content: [], totalElements: 0, totalPages: 0 },
        reviews: { content: [review], totalElements: 1, totalPages: 1 },
        permissions: { ...DEFAULT_WORKER_PERMISSIONS, canWorkReviews: true }
      } satisfies WorkerBoard);

      expect(page.isReviewSection()).toBe(true);
      page.startReviewFieldEdit(review, 'text');
      expect(page.reviewFieldEditor()).toEqual({ review, field: 'text' });
      expect(page.reviewFieldValue(review, 'text')).toBe(originalText);

      page.setReviewFieldDraft(review, 'text', fullText);
      expect(page.reviewFieldValue(review, 'text')).toBe(fullText);
      await page.saveReviewField(review, 'text');

      expect(updateText).toHaveBeenCalledExactlyOnceWith(review.id, review.orderId, fullText, {
        sourcePage: MOBILE_WORKER_ACTIVITY_SOURCE_PAGE,
        sourceSection: section
      });
      expect(page.board()?.reviews.content[0].text).toBe(fullText);
      expect(page.reviewFieldEditor()).toBeNull();
      expect(page.editingReviewFieldKey()).toBeNull();
      page.dismissReviewFieldEditor();
      expect(page.reviewFieldEditorClosing()).toBe(false);
    }
  );

  it.each(['nagul', 'publish'] as WorkerBoardSection[])(
    'discards an unsaved long draft in %s', (section) => {
      const review = makeReview();
      page.activeSection.set(section);
      page.board.set({
        title: 'Специалист',
        metrics: [],
        orders: { content: [], totalElements: 0, totalPages: 0 },
        reviews: { content: [review], totalElements: 1, totalPages: 1 },
        permissions: { ...DEFAULT_WORKER_PERMISSIONS, canWorkReviews: true }
      } satisfies WorkerBoard);

      page.startReviewFieldEdit(review, 'text');
      page.setReviewFieldDraft(review, 'text', fullText);
      page.cancelReviewFieldEdit(review, 'text');

      expect(page.reviewFieldEditor()).toBeNull();
      expect(page.reviewFieldValue(review, 'text')).toBe(originalText);
      expect(updateText).not.toHaveBeenCalled();
    }
  );
});
