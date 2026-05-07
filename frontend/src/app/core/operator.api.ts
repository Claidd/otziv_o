import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { appEnvironment } from './app-environment';
import { LeadPage } from './leads.api';

export type OperatorBoardSection = 'queue' | 'sent';

export interface OperatorText {
  beginText: string;
  offerText: string;
  offer2Text: string;
  startText: string;
}

export interface OperatorBoard {
  leads: LeadPage;
  promoTexts: string[];
  text: OperatorText;
  requireDeviceId: boolean;
  telephoneId?: number | null;
  operatorId?: number | null;
  timer?: string | null;
  timerExpired: boolean;
  section: OperatorBoardSection;
  queueTotal: number;
  sentTotal: number;
}

export interface OperatorBoardQuery {
  keyword?: string;
  pageNumber?: number;
  pageSize?: number;
  section?: OperatorBoardSection;
}

@Injectable({ providedIn: 'root' })
export class OperatorApi {
  constructor(private readonly http: HttpClient) {}

  getBoard(query: OperatorBoardQuery = {}): Observable<OperatorBoard> {
    const params = new HttpParams()
      .set('keyword', query.keyword?.trim() ?? '')
      .set('pageNumber', String(query.pageNumber ?? 0))
      .set('pageSize', String(query.pageSize ?? 10))
      .set('section', query.section ?? 'queue');

    return this.http.get<OperatorBoard>(`${appEnvironment.apiBaseUrl}/api/operator/board`, { params });
  }

  bindDevice(telephoneId: number): Observable<void> {
    return this.http.post<void>(`${appEnvironment.apiBaseUrl}/api/operator/device-token`, { telephoneId });
  }

  markSend(id: number): Observable<void> {
    return this.http.post<void>(`${appEnvironment.apiBaseUrl}/api/operator/leads/${id}/status/send`, {});
  }

  markToWork(id: number, commentsLead?: string): Observable<void> {
    return this.http.post<void>(
      `${appEnvironment.apiBaseUrl}/api/operator/leads/${id}/status/to-work`,
      { commentsLead }
    );
  }
}
