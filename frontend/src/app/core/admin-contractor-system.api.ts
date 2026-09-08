import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { appEnvironment } from './app-environment';
import type {
  ContractorPaymentSystemStatus,
  ContractorPaymentSystemActivationRequest,
  ContractorPaymentSystemRoutingRequest,
  ContractorLegacyRewardReconciliation,
  ContractorLegacyRewardReconciliationApplyRequest,
  ContractorLegacyRewardManualResolutionRequest
} from './contractor-payments.api';
@Injectable({ providedIn: 'root' })
export class AdminContractorSystemApi {
  constructor(private readonly http: HttpClient) {}
  getSystemStatus(): Observable<ContractorPaymentSystemStatus> {
    return this.http.get<ContractorPaymentSystemStatus>(
      `${appEnvironment.apiBaseUrl}/api/admin/contractor-payments/system`
    );
  }

  activateSystem(
    request: ContractorPaymentSystemActivationRequest
  ): Observable<ContractorPaymentSystemStatus> {
    return this.http.post<ContractorPaymentSystemStatus>(
      `${appEnvironment.apiBaseUrl}/api/admin/contractor-payments/system/activate`,
      request
    );
  }

  updateSystemRouting(
    request: ContractorPaymentSystemRoutingRequest
  ): Observable<ContractorPaymentSystemStatus> {
    return this.http.post<ContractorPaymentSystemStatus>(
      `${appEnvironment.apiBaseUrl}/api/admin/contractor-payments/system/routing`,
      request
    );
  }

  getLegacyRewardReconciliation(): Observable<ContractorLegacyRewardReconciliation> {
    return this.http.get<ContractorLegacyRewardReconciliation>(
      `${appEnvironment.apiBaseUrl}/api/admin/contractor-payments/system/legacy-reconciliation`
    );
  }

  prepareLegacyRewardReconciliation(): Observable<ContractorLegacyRewardReconciliation> {
    return this.http.post<ContractorLegacyRewardReconciliation>(
      `${appEnvironment.apiBaseUrl}/api/admin/contractor-payments/system/legacy-reconciliation/prepare`,
      {}
    );
  }

  applyLegacyRewardReconciliation(
    runId: number,
    request: ContractorLegacyRewardReconciliationApplyRequest
  ): Observable<ContractorLegacyRewardReconciliation> {
    return this.http.post<ContractorLegacyRewardReconciliation>(
      `${appEnvironment.apiBaseUrl}/api/admin/contractor-payments/system/legacy-reconciliation/${runId}/apply`,
      request
    );
  }

  resolveLegacyRewardManualGroup(
    runId: number,
    orderId: number,
    request: ContractorLegacyRewardManualResolutionRequest
  ): Observable<ContractorLegacyRewardReconciliation> {
    return this.http.post<ContractorLegacyRewardReconciliation>(
      `${appEnvironment.apiBaseUrl}/api/admin/contractor-payments/system/legacy-reconciliation/${runId}/orders/${orderId}/resolve`,
      request
    );
  }
}
