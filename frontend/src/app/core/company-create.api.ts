import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { appEnvironment } from './app-environment';

export type CompanyCreateSource = 'manager' | 'operator' | 'manual';

export interface CompanyCreateOption {
  id: number;
  label: string;
}

export interface CompanyCreatePayload {
  source: CompanyCreateSource;
  leadId?: number | null;
  title: string;
  urlChat: string;
  telephone: string;
  city: string;
  email: string;
  commentsCompany: string;
  operator: string;
  manager?: CompanyCreateOption | null;
  worker?: CompanyCreateOption | null;
  status?: CompanyCreateOption | null;
  category?: CompanyCreateOption | null;
  subCategory?: CompanyCreateOption | null;
  filialCity?: CompanyCreateOption | null;
  filialTitle: string;
  filialUrl: string;
  managers: CompanyCreateOption[];
  workers: CompanyCreateOption[];
  categories: CompanyCreateOption[];
  subCategories: CompanyCreateOption[];
  cities: CompanyCreateOption[];
  canChangeManager: boolean;
}

export interface CompanyCreateRequest {
  source: CompanyCreateSource;
  leadId?: number | null;
  managerId?: number | null;
  title: string;
  urlChat: string;
  telephone: string;
  city: string;
  email: string;
  commentsCompany: string;
  categoryId: number | null;
  subCategoryId: number | null;
  workerId: number | null;
  filialCityId: number | null;
  filialTitle: string;
  filialUrl: string;
}

export interface CompanyCreateResult {
  companyId?: number | null;
  title: string;
  leadId?: number | null;
  source: CompanyCreateSource;
}

@Injectable({ providedIn: 'root' })
export class CompanyCreateApi {
  constructor(private readonly http: HttpClient) {}

  getPayload(source: CompanyCreateSource, leadId?: number | null, managerId?: number | null): Observable<CompanyCreatePayload> {
    let params = new HttpParams().set('source', source);

    if (leadId != null) {
      params = params.set('leadId', String(leadId));
    }

    if (managerId != null) {
      params = params.set('managerId', String(managerId));
    }

    return this.http.get<CompanyCreatePayload>(`${appEnvironment.apiBaseUrl}/api/companies/create-payload`, { params });
  }

  getSubcategories(categoryId: number): Observable<CompanyCreateOption[]> {
    return this.http.get<CompanyCreateOption[]>(
      `${appEnvironment.apiBaseUrl}/api/companies/categories/${categoryId}/subcategories`
    );
  }

  createCompany(request: CompanyCreateRequest): Observable<CompanyCreateResult> {
    return this.http.post<CompanyCreateResult>(`${appEnvironment.apiBaseUrl}/api/companies`, request);
  }
}
