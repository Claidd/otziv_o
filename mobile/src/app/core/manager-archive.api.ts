import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import type { ArchiveOrderDetailsPayload, ArchiveOrderListItem, ArchiveRestoreResult, ManagerArchiveOrdersQuery, Page } from './api.service';
import { mobileEnvironment } from './mobile-environment';

/** Stateless feature transport; authentication and error handling stay in Angular interceptors. */
@Injectable({ providedIn: 'root' })
export class ManagerArchiveApi {
  private readonly http = inject(HttpClient);
  private apiUrl(path: string): string { return mobileEnvironment.apiBaseUrl + path; }

  getManagerArchiveOrders(query: ManagerArchiveOrdersQuery = {}): Observable<Page<ArchiveOrderListItem>> {
    const params = new HttpParams()
      .set('keyword', query.keyword?.trim() ?? '')
      .set('mode', query.mode ?? 'all')
      .set('pageNumber', String(query.pageNumber ?? 0))
      .set('pageSize', String(query.pageSize ?? 10))
      .set('sortDirection', query.sortDirection ?? 'desc');

    return this.http.get<Page<ArchiveOrderListItem>>(this.apiUrl('/api/manager/archive/orders'), { params });
  }

  getManagerArchiveOrder(orderId: number): Observable<ArchiveOrderDetailsPayload> {
    return this.http.get<ArchiveOrderDetailsPayload>(this.apiUrl(`/api/manager/archive/orders/${orderId}`));
  }

  /**
   * `specialistChoice`: when the archived specialist no longer works the server answers 409 SPECIALIST_REQUIRED with
   * the accepted specialists instead of returning the order to that specialist; repeat with `targetWorkerId`.
   */
  restoreManagerArchiveOrder(
    orderId: number,
    targetStatus = 'Архив',
    targetWorkerId: number | null = null
  ): Observable<ArchiveRestoreResult> {
    let params = new HttpParams()
      .set('targetStatus', targetStatus)
      .set('confirm', 'true')
      .set('specialistChoice', 'true');
    if (targetWorkerId != null) {
      params = params.set('targetWorkerId', String(targetWorkerId));
    }

    return this.http.post<ArchiveRestoreResult>(
      this.apiUrl(`/api/manager/archive/orders/${orderId}/restore`),
      {},
      { params }
    );
  }
}
