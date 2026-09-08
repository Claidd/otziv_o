import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import type { ManagerBoard, ManagerBoardQuery } from './api.service';
import { mobileEnvironment } from './mobile-environment';

/** Stateless feature transport; authentication and error handling stay in Angular interceptors. */
@Injectable({ providedIn: 'root' })
export class ManagerBoardApi {
  private readonly http = inject(HttpClient);
  private apiUrl(path: string): string { return mobileEnvironment.apiBaseUrl + path; }

  getManagerBoard(query: ManagerBoardQuery = {}): Observable<ManagerBoard> {
    let params = new HttpParams()
      .set('section', query.section ?? 'companies')
      .set('status', query.status?.trim() || 'Все')
      .set('keyword', query.keyword?.trim() ?? '')
      .set('pageNumber', String(query.pageNumber ?? 0))
      .set('pageSize', String(query.pageSize ?? 10))
      .set('sortDirection', query.sortDirection ?? 'desc');

    if (query.companyId != null) {
      params = params.set('companyId', String(query.companyId));
    }

    return this.http.get<ManagerBoard>(this.apiUrl('/api/manager/board'), { params });
  }
}
