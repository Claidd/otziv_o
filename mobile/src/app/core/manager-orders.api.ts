import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import type { OrderDetailsPayload } from './api.service';
import { mobileEnvironment } from './mobile-environment';

/** Stateless feature transport; authentication and error handling stay in Angular interceptors. */
@Injectable({ providedIn: 'root' })
export class ManagerOrdersApi {
  private readonly http = inject(HttpClient);
  private apiUrl(path: string): string { return mobileEnvironment.apiBaseUrl + path; }

  updateManagerOrderStatus(orderId: number, status: string): Observable<void> {
    return this.http.post<void>(this.apiUrl(`/api/manager/orders/${orderId}/status`), { status });
  }

  getManagerOrderDetails(orderId: number): Observable<OrderDetailsPayload> {
    return this.http.get<OrderDetailsPayload>(this.apiUrl(`/api/manager/orders/${orderId}/details`));
  }
}
