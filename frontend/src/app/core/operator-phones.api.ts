import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { appEnvironment } from './app-environment';

export interface PhoneOperatorOption {
  id: number;
  title: string;
}

export interface OperatorPhone {
  id: number;
  number: string;
  fio?: string | null;
  birthday?: string | null;
  amountAllowed: number;
  amountSent: number;
  blockTime: number;
  timer?: string | null;
  googleLogin?: string | null;
  googlePassword?: string | null;
  avitoPassword?: string | null;
  mailLogin?: string | null;
  mailPassword?: string | null;
  fotoInstagram?: string | null;
  active: boolean;
  createDate?: string | null;
  updateStatus?: string | null;
  operator?: PhoneOperatorOption | null;
}

export interface OperatorPhonesResponse {
  phones: OperatorPhone[];
  operators: PhoneOperatorOption[];
}

export interface OperatorPhoneRequest {
  number: string;
  fio?: string | null;
  birthday?: string | null;
  amountAllowed: number;
  amountSent: number;
  blockTime: number;
  timer?: string | null;
  googleLogin?: string | null;
  googlePassword?: string | null;
  avitoPassword?: string | null;
  mailLogin?: string | null;
  mailPassword?: string | null;
  fotoInstagram?: string | null;
  active: boolean;
  createDate?: string | null;
  operatorId?: number | null;
}

@Injectable({ providedIn: 'root' })
export class OperatorPhonesApi {
  private readonly baseUrl = `${appEnvironment.apiBaseUrl}/api/admin/phones`;

  constructor(private readonly http: HttpClient) {}

  getPhones(keyword = ''): Observable<OperatorPhonesResponse> {
    const value = keyword.trim();
    const params = value ? new HttpParams().set('keyword', value) : new HttpParams();
    return this.http.get<OperatorPhonesResponse>(this.baseUrl, { params });
  }

  createPhone(request: OperatorPhoneRequest): Observable<OperatorPhone> {
    return this.http.post<OperatorPhone>(this.baseUrl, request);
  }

  updatePhone(id: number, request: OperatorPhoneRequest): Observable<OperatorPhone> {
    return this.http.put<OperatorPhone>(`${this.baseUrl}/${id}`, request);
  }

  deletePhone(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }
}
