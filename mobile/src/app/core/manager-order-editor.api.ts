import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { decodeOrderEditPayload, managerOrderEditPath } from '@otziv/client-common/order-editor';
import type { OrderEditPayload, OrderUpdateRequest } from './api.service';
import { mobileEnvironment } from './mobile-environment';

/** The order editor's transport boundary. Authentication stays in HttpClient interceptors. */
@Injectable({ providedIn: 'root' })
export class ManagerOrderEditorApi {
  private readonly http = inject(HttpClient);

  getEdit(orderId: number): Observable<OrderEditPayload> {
    return this.http.get<unknown>(`${mobileEnvironment.apiBaseUrl}${managerOrderEditPath(orderId)}`).pipe(map(decodeOrderEditPayload));
  }

  update(orderId: number, request: OrderUpdateRequest): Observable<OrderEditPayload> {
    return this.http.put<OrderEditPayload>(this.orderUrl(orderId), request);
  }

  delete(orderId: number): Observable<void> {
    return this.http.delete<void>(this.orderUrl(orderId));
  }

  private orderUrl(orderId: number): string {
    return `${mobileEnvironment.apiBaseUrl}/api/manager/orders/${orderId}`;
  }
}
