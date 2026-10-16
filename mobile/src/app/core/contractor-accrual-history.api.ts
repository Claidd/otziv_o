import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { mobileEnvironment } from './mobile-environment';

/** Employee-facing history intentionally omits internal document and audit fields. */
export interface ContractorSelfAccrualAdjustment {
  id: number;
  profileId: number;
  kind: 'BONUS' | 'DEDUCTION' | 'REVERSAL';
  signedAmountKopecks: number;
  effectiveOn: string;
  referenceMonth: string;
  reason: string;
  reversalOfId?: number | null;
}

@Injectable({ providedIn: 'root' })
export class ContractorAccrualHistoryApi {
  private readonly http = inject(HttpClient);

  getMyAdjustments(): Observable<ContractorSelfAccrualAdjustment[]> {
    return this.http.get<ContractorSelfAccrualAdjustment[]>(
      `${mobileEnvironment.apiBaseUrl}/api/contractor-payments/me/accrual-adjustments`
    );
  }
}
