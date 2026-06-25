import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { appEnvironment } from './app-environment';

export interface SpecialistTransferRequest {
  fromWorkerId: number | null;
  toWorkerId: number | null;
  companyIds?: number[] | null;
  comment?: string;
  confirmationText?: string;
}

export interface SpecialistTransferWorker {
  id: number;
  userId?: number | null;
  username?: string | null;
  fio?: string | null;
  label: string;
  active: boolean;
}

export interface SpecialistTransferCompanySample {
  id: number;
  title: string;
  telephone?: string | null;
  city?: string | null;
  statusTitle?: string | null;
}

export interface SpecialistTransferWarning {
  code: string;
  message: string;
}

export interface SpecialistTransferPreview {
  fromWorker: SpecialistTransferWorker;
  toWorker: SpecialistTransferWorker;
  companyCount: number;
  activeOrderCount: number;
  unpublishedReviewCount: number;
  badReviewTaskCount: number;
  targetAlreadyAssignedCompanyCount: number;
  companiesWithoutActiveOrdersCount: number;
  targetWorkerMissingManagerLinksCount: number;
  sampleCompanies: SpecialistTransferCompanySample[];
  warnings: SpecialistTransferWarning[];
}

export interface SpecialistTransferResult {
  auditId?: number | null;
  createdAt: string;
  fromWorker: SpecialistTransferWorker;
  toWorker: SpecialistTransferWorker;
  companyCount: number;
  companyLinksAdded: number;
  companyLinksRemoved: number;
  activeOrderCount: number;
  unpublishedReviewCount: number;
  badReviewTaskCount: number;
}

export interface SpecialistTransferAudit {
  id: number;
  createdAt: string;
  actorUserId: number;
  actorName: string;
  fromWorkerId: number;
  fromWorkerName: string;
  toWorkerId: number;
  toWorkerName: string;
  companyCount: number;
  orderCount: number;
  reviewCount: number;
  badReviewTaskCount: number;
  comment?: string | null;
}

@Injectable({ providedIn: 'root' })
export class AdminSpecialistTransfersApi {
  private readonly baseUrl = `${appEnvironment.apiBaseUrl}/api/admin/specialist-transfers`;

  constructor(private readonly http: HttpClient) {}

  preview(request: SpecialistTransferRequest): Observable<SpecialistTransferPreview> {
    return this.http.post<SpecialistTransferPreview>(`${this.baseUrl}/preview`, request);
  }

  apply(request: SpecialistTransferRequest): Observable<SpecialistTransferResult> {
    return this.http.post<SpecialistTransferResult>(`${this.baseUrl}/apply`, request);
  }

  recent(): Observable<SpecialistTransferAudit[]> {
    return this.http.get<SpecialistTransferAudit[]>(`${this.baseUrl}/recent`);
  }
}
