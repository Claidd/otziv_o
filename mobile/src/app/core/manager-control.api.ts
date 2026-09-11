import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import type { ManagerControlActionPayload, ManagerControlClientMessageReconciliation, ManagerControlClientReplyPayload, ManagerControlClosePayload, ManagerControlCloseResponse, ManagerControlConcreteItem, ManagerControlManagerDetail, ManagerControlStagePayload, ManagerControlSummary } from './manager-control.models';
import { mobileEnvironment } from './mobile-environment';

/** Stateless feature transport; authentication and error handling stay in Angular interceptors. */
@Injectable({ providedIn: 'root' })
export class ManagerControlApi {
  private readonly http = inject(HttpClient);
  private apiUrl(path: string): string { return mobileEnvironment.apiBaseUrl + path; }

  getManagerControlToday(): Observable<ManagerControlSummary> {
    return this.http.get<ManagerControlSummary>(this.apiUrl('/api/admin/manager-control/today'));
  }

  syncManagerControlToday(): Observable<ManagerControlSummary> {
    return this.http.post<ManagerControlSummary>(this.apiUrl('/api/admin/manager-control/today/sync'), {});
  }

  getManagerControlDetails(managerId: number): Observable<ManagerControlManagerDetail> {
    return this.http.get<ManagerControlManagerDetail>(
      this.apiUrl(`/api/admin/manager-control/managers/${managerId}/today`)
    );
  }

  syncManagerControlDetails(managerId: number): Observable<ManagerControlManagerDetail> {
    return this.http.post<ManagerControlManagerDetail>(
      this.apiUrl(`/api/admin/manager-control/managers/${managerId}/today/sync`),
      {}
    );
  }

  reconcileManagerControlClientMessages(managerId: number): Observable<ManagerControlClientMessageReconciliation> {
    return this.http.post<ManagerControlClientMessageReconciliation>(
      this.apiUrl(`/api/admin/manager-control/managers/${managerId}/today/reconcile-client-messages`),
      {}
    );
  }

  acceptManagerControl(controlId: number): Observable<ManagerControlManagerDetail> {
    return this.http.post<ManagerControlManagerDetail>(
      this.apiUrl(`/api/admin/manager-control/controls/${controlId}/accept`),
      {}
    );
  }

  markManagerControlStage(controlId: number, payload: ManagerControlStagePayload): Observable<ManagerControlManagerDetail> {
    return this.http.post<ManagerControlManagerDetail>(
      this.apiUrl(`/api/admin/manager-control/controls/${controlId}/stage`),
      payload
    );
  }

  closeManagerControlDay(controlId: number, payload: ManagerControlClosePayload): Observable<ManagerControlCloseResponse> {
    return this.http.post<ManagerControlCloseResponse>(
      this.apiUrl(`/api/admin/manager-control/controls/${controlId}/close`),
      payload
    );
  }

  actionManagerControlItem(itemId: number, payload: ManagerControlActionPayload): Observable<void> {
    return this.http.post<void>(this.apiUrl(`/api/admin/manager-control/items/${itemId}/action`), payload);
  }

  actionManagerControlConcreteItem(
    concreteItemId: number,
    payload: ManagerControlActionPayload
  ): Observable<ManagerControlConcreteItem> {
    return this.http.post<ManagerControlConcreteItem>(
      this.apiUrl(`/api/admin/manager-control/concrete-items/${concreteItemId}/action`),
      payload
    );
  }

  deliveryOperation(concreteItemId: number, operationId: string): Observable<import('@otziv/client-common/delivery-operations').DeliveryOperation> {
    return this.http.get<import('@otziv/client-common/delivery-operations').DeliveryOperation>(
      this.apiUrl(`/api/admin/manager-control/concrete-items/${concreteItemId}/delivery-operations/${encodeURIComponent(operationId)}`));
  }

  sendManagerControlClientMessage(concreteItemId: number): Observable<ManagerControlConcreteItem> {
    return this.http.post<ManagerControlConcreteItem>(
      this.apiUrl(`/api/admin/manager-control/concrete-items/${concreteItemId}/send-client-message`),
      {},
      { headers: { "X-Otziv-Delivery-Protocol": "queued-v1" } }
    );
  }

  replyManagerControlClientMessage(
    concreteItemId: number,
    payload: ManagerControlClientReplyPayload
  ): Observable<ManagerControlConcreteItem> {
    return this.http.post<ManagerControlConcreteItem>(
      this.apiUrl(`/api/admin/manager-control/concrete-items/${concreteItemId}/reply`),
      payload,
      { headers: { "X-Otziv-Delivery-Protocol": "queued-v1" } }
    );
  }

  repairManagerControlConcreteItem(concreteItemId: number): Observable<ManagerControlConcreteItem> {
    return this.http.post<ManagerControlConcreteItem>(
      this.apiUrl(`/api/admin/manager-control/concrete-items/${concreteItemId}/repair`),
      {}
    );
  }
}
