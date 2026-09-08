import { ManagerOrdersApi } from '../core/manager-orders.api';
import { ManagerReviewActionsApi } from '../core/manager-review-actions.api';
import { ManagerReviewTasksApi } from '../core/manager-review-tasks.api';
import { WorkerApi } from '../core/worker.api';
import { OrderReviewsApi } from '../core/order-reviews.api';
import { OrderPaymentApi } from '../core/order-payment.api';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { ToastController } from '@ionic/angular/standalone';
import { BehaviorSubject, Subject, of } from 'rxjs';
import { OrderDetailsPage } from './order-details.page';
import { ApiService, type OrderDetailsPayload, type CompanyDeepReportState } from '../core/api.service';
import { OrderCompanyReportApi } from '../core/order-company-report.api';
import { AuthService } from '../core/auth.service';
import { MobileConfirmService } from '../shared/mobile-confirm.service';
import { MobileMediaService } from '../shared/mobile-media.service';

const details = (id: number) => ({ id, orderId: id, companyTitle: `Company ${id}`, reviews: [] }) as unknown as OrderDetailsPayload;

describe('order details Ionic lifecycle', () => {
  it('cancels details/report reads on cached-page leave and reloads on the next visit', () => {
    const params = new BehaviorSubject(convertToParamMap({ companyId: '91', orderId: '1' }));
    const first = new Subject<OrderDetailsPayload>(); const second = new Subject<OrderDetailsPayload>();
    const report = new Subject<CompanyDeepReportState>();
    const api = { getManagerOrderDetails: vi.fn().mockReturnValueOnce(first).mockReturnValueOnce(second) };
    TestBed.configureTestingModule({ providers: [
      { provide: ApiService, useValue: api },
      { provide: ManagerOrdersApi, useValue: api },
      { provide: ManagerReviewActionsApi, useValue: api },
      { provide: ManagerReviewTasksApi, useValue: api },
      { provide: WorkerApi, useValue: api },
      { provide: OrderPaymentApi, useValue: {} },
      { provide: OrderReviewsApi, useValue: {} },
      { provide: OrderCompanyReportApi, useValue: { getManagerOrderCompanyReport: vi.fn(() => report) } },
      { provide: AuthService, useValue: { hasAnyRealmRole: () => false, hasRealmRole: () => false } },
      { provide: ActivatedRoute, useValue: { paramMap: params, queryParamMap: of(convertToParamMap({})), snapshot: { paramMap: params.value, queryParamMap: convertToParamMap({}) } } },
      { provide: Router, useValue: {} }, { provide: MobileConfirmService, useValue: {} },
      { provide: MobileMediaService, useValue: {} }, { provide: ToastController, useValue: {} }
    ] });
    TestBed.overrideComponent(OrderDetailsPage, { set: { template: '', imports: [] } });
    const fixture = TestBed.createComponent(OrderDetailsPage); fixture.detectChanges(); const page = fixture.componentInstance;
    page.openCompanyReport(); expect(first.observed).toBe(true); expect(report.observed).toBe(true);
    page.ionViewWillLeave(); expect(first.observed).toBe(false); expect(report.observed).toBe(false);
    page.ionViewWillEnter(); first.next(details(99)); second.next(details(1));
    expect(page.details()?.companyTitle).toBe('Company 1'); expect(api.getManagerOrderDetails).toHaveBeenCalledTimes(2);
    fixture.destroy();
  });
});
