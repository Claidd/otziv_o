import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { of } from 'rxjs';
import type { WorkerReviewItem } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { OrderReviewsApi } from '../core/order-reviews.api';
import { WorkerApi } from '../core/worker.api';
import { MobileConfirmService } from '../shared/mobile-confirm.service';
import { MobileManualCardPaymentFlowService } from '../shared/mobile-manual-card-payment-flow.service';
import { MobileMediaService } from '../shared/mobile-media.service';
import { WorkerPage } from './worker.page';

describe('mobile archived recovery account preparation',()=>{
  let page:WorkerPage;
  const complete=vi.fn().mockReturnValue(of(null));
  const change=vi.fn().mockReturnValue(of(null));
  const review={id:125646,recoveryTask:true,recoveryTaskId:1160,botLoginPresent:false,botPasswordPresent:false} as WorkerReviewItem;
  beforeEach(()=>{
    vi.clearAllMocks();
    TestBed.configureTestingModule({providers:[WorkerPage,
      {provide:WorkerApi,useValue:{completeWorkerRecoveryTask:complete,changeWorkerRecoveryTaskBot:change}},
      {provide:OrderReviewsApi,useValue:{}},{provide:AuthService,useValue:{}},
      {provide:ActivatedRoute,useValue:{}},{provide:Router,useValue:{}},
      {provide:MobileConfirmService,useValue:{}},{provide:MobileManualCardPaymentFlowService,useValue:{}},
      {provide:MobileMediaService,useValue:{}}
    ]});
    TestBed.overrideComponent(WorkerPage,{set:{template:'',imports:[]}});
    page=TestBed.createComponent(WorkerPage).componentInstance;
    page.activeSection.set('recovery');
  });
  afterEach(()=>page.ngOnDestroy());
  it('explains missing credentials and never completes a recovery without them',async()=>{
    expect(page.needsRecoveryAccount(review)).toBe(true);
    expect(page.reviewCredentialCopyDisabled(review,'login')).toBe(true);
    expect(page.reviewCredentialCopyTitle(review,'login')).toContain('Получить аккаунт');
    expect(page.doneLabel(review)).toBe('СНАЧАЛА ПОЛУЧИТЕ АККАУНТ');
    expect(page.publishCredentialWaitTitle(review)).toContain('Получить аккаунт');
    await page.markReviewDone(review);
    expect(complete).not.toHaveBeenCalled();
    expect(page.error()).toContain('Получить аккаунт');
  });
  it('uses the recovery task endpoint for an archived review belonging to another worker',async()=>{
    vi.spyOn(page as any,'load').mockResolvedValue(undefined);
    await page.changeReviewBot(review);
    expect(change).toHaveBeenCalledExactlyOnceWith(1160);
  });
  it('allows credentials and the recovery action after account preparation, including snapshots',()=>{
    const prepared={...review,botLoginPresent:true,botPasswordPresent:true};
    expect(page.needsRecoveryAccount(prepared)).toBe(false);
    expect(page.reviewCredentialCopyDisabled(prepared,'login')).toBe(false);
    expect(page.reviewCredentialCopyDisabled(prepared,'password')).toBe(false);
    expect(page.doneLabel(prepared)).toBe('Восстановил');
  });
});
