import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import type { Observable } from 'rxjs';
import { mobileEnvironment } from './mobile-environment';

export type ContractorPaymentSystemMode =
  'LEGACY' | 'COMPLETION_ACTIVE' | 'ROUTING_LIVE' | 'ROUTING_PAUSED' | 'CONFIGURATION_ERROR';

export interface ContractorPaymentSystemStatus {
  mode: ContractorPaymentSystemMode;
  systemEnabled: boolean;
  legacyBehavior: boolean;
  irreversible: boolean;
  routingRequested: boolean;
  completionAccountingEffective: boolean;
  liveRoutingEffective: boolean;
  completionBacklogReady: boolean;
  activationAvailable: boolean;
  activationBlockedReasons: string[];
  runtimeWarnings: string[];
  attributionStartDate: string | null;
  revision: number;
  liveRoutingMasterEnabled: boolean;
  rewardAttributionMasterEnabled: boolean;
}

export interface ContractorSystemActivationRequest {
  attributionStartDate: string;
  confirmation: string;
  reason: string;
  expectedRevision: number;
}

export interface ContractorSystemRoutingRequest {
  enabled: boolean;
  confirmation: string;
  reason: string;
  expectedRevision: number;
}

@Injectable({ providedIn: 'root' })
export class ContractorSystemApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${mobileEnvironment.apiBaseUrl}/api/admin/contractor-payments/system`;

  status(): Observable<ContractorPaymentSystemStatus> {
    return this.http.get<ContractorPaymentSystemStatus>(this.baseUrl);
  }

  activate(request: ContractorSystemActivationRequest): Observable<ContractorPaymentSystemStatus> {
    return this.http.post<ContractorPaymentSystemStatus>(`${this.baseUrl}/activate`, request);
  }

  routing(request: ContractorSystemRoutingRequest): Observable<ContractorPaymentSystemStatus> {
    return this.http.post<ContractorPaymentSystemStatus>(`${this.baseUrl}/routing`, request);
  }
}
