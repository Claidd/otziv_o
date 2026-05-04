import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { appEnvironment } from './app-environment';

export interface LeadPerson {
  id: number;
  userId?: number;
  username?: string;
  fio?: string;
}

export interface LeadItem {
  id: number;
  telephoneLead: string;
  cityLead: string;
  commentsLead?: string;
  lidStatus: string;
  createDate: string;
  updateStatus?: string;
  dateNewTry?: string;
  offer: boolean;
  operatorId?: number;
  operator?: LeadPerson;
  manager?: LeadPerson;
  marketolog?: LeadPerson;
}

export interface LeadPage {
  content: LeadItem[];
  pageNumber: number;
  pageSize: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

export interface LeadBoard {
  toWork: LeadPage;
  newLeads: LeadPage;
  send: LeadPage;
  archive?: LeadPage;
  inWork: LeadPage;
  all: LeadPage;
  statuses: string[];
  promoTexts: string[];
}

export type LeadBucketKey = 'toWork' | 'newLeads' | 'send' | 'archive' | 'inWork' | 'all';

export interface LeadBoardQuery {
  keyword?: string;
  pageNumber?: number;
  pageSize?: number;
  sortDirection?: 'desc' | 'asc';
  section?: LeadBucketKey;
}

export interface LeadPersonOption {
  id: number;
  userId?: number;
  username?: string;
  fio?: string;
  email?: string;
}

export interface LeadEditOptions {
  operators: LeadPersonOption[];
  managers: LeadPersonOption[];
  marketologs: LeadPersonOption[];
  statuses: string[];
}

export interface LeadCreateRequest {
  telephoneLead: string;
  cityLead: string;
  commentsLead?: string;
  managerId?: number | null;
}

export interface LeadUpdateRequest {
  telephoneLead: string;
  cityLead: string;
  commentsLead?: string;
  lidStatus: string;
  operatorId?: number | null;
  managerId?: number | null;
  marketologId?: number | null;
}

export interface LeadImportResponse {
  totalRows: number;
  added: number;
  skippedDuplicates: number;
  skippedInvalid: number;
  errors: string[];
}

@Injectable({ providedIn: 'root' })
export class LeadsApi {
  constructor(private readonly http: HttpClient) {}

  getBoard(query: LeadBoardQuery = {}): Observable<LeadBoard> {
    const params = new HttpParams()
      .set('keyword', query.keyword?.trim() ?? '')
      .set('pageNumber', String(query.pageNumber ?? 0))
      .set('pageSize', String(query.pageSize ?? 10))
      .set('sortDirection', query.sortDirection ?? 'desc')
      .set('section', query.section ?? 'inWork');

    return this.http.get<LeadBoard>(`${appEnvironment.apiBaseUrl}/api/leads/board`, { params });
  }

  getEditOptions(): Observable<LeadEditOptions> {
    return this.http.get<LeadEditOptions>(`${appEnvironment.apiBaseUrl}/api/leads/edit-options`);
  }

  createLead(request: LeadCreateRequest): Observable<LeadItem> {
    return this.http.post<LeadItem>(`${appEnvironment.apiBaseUrl}/api/leads`, request);
  }

  updateLead(id: number, request: LeadUpdateRequest): Observable<LeadItem> {
    return this.http.put<LeadItem>(`${appEnvironment.apiBaseUrl}/api/leads/${id}`, request);
  }

  deleteLead(id: number): Observable<void> {
    return this.http.delete<void>(`${appEnvironment.apiBaseUrl}/api/leads/${id}`);
  }

  importLeads(file: File): Observable<LeadImportResponse> {
    const formData = new FormData();
    formData.append('file', file);
    return this.http.post<LeadImportResponse>(`${appEnvironment.apiBaseUrl}/api/leads/file-import`, formData);
  }

  markSend(id: number): Observable<void> {
    return this.http.post<void>(`${appEnvironment.apiBaseUrl}/api/leads/${id}/status/send`, {});
  }

  markResend(id: number): Observable<void> {
    return this.http.post<void>(`${appEnvironment.apiBaseUrl}/api/leads/${id}/status/resend`, {});
  }

  markArchive(id: number): Observable<void> {
    return this.http.post<void>(`${appEnvironment.apiBaseUrl}/api/leads/${id}/status/archive`, {});
  }

  markNew(id: number): Observable<void> {
    return this.http.post<void>(`${appEnvironment.apiBaseUrl}/api/leads/${id}/status/new`, {});
  }

  markToWork(id: number, commentsLead?: string): Observable<void> {
    return this.http.post<void>(
      `${appEnvironment.apiBaseUrl}/api/leads/${id}/status/to-work`,
      { commentsLead }
    );
  }
}
