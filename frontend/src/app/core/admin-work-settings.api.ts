import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { appEnvironment } from './app-environment';
import type {
  AdminNagulSettings,
  AdminWorkerAccountActionSettings,
  NagulSettingsRequest,
  AdminWorkerCellularAccessSettings,
  WorkerCellularAccessSettingsRequest,
  AdminTelegramReportScheduleSettings,
  TelegramReportScheduleSettingsRequest,
  AdminWhatsAppGroupSyncSettings,
  WhatsAppGroupSyncSettingsRequest,
  AdminClientPublicationProgressReportSettings,
  ClientPublicationProgressReportSettingsRequest,
  AdminSharedChatLinkSyncResponse
} from './admin-dictionaries.api';

@Injectable({ providedIn: 'root' })
export class AdminWorkSettingsApi {
  private readonly baseUrl = `${appEnvironment.apiBaseUrl}/api/admin`;
  constructor(private readonly http: HttpClient) {}

  getNagulSettings(): Observable<AdminNagulSettings> {
    return this.http.get<AdminNagulSettings>(`${this.baseUrl}/settings/nagul`);
  }

  getWorkerAccountActionSettings(): Observable<AdminWorkerAccountActionSettings> {
    return this.http.get<AdminWorkerAccountActionSettings>(
      `${this.baseUrl}/dictionaries/worker-account-action-settings`
    );
  }

  updateWorkerAccountActionSettings(
    request: AdminWorkerAccountActionSettings
  ): Observable<AdminWorkerAccountActionSettings> {
    return this.http.put<AdminWorkerAccountActionSettings>(
      `${this.baseUrl}/dictionaries/worker-account-action-settings`,
      request
    );
  }

  updateNagulSettings(request: NagulSettingsRequest): Observable<AdminNagulSettings> {
    return this.http.put<AdminNagulSettings>(`${this.baseUrl}/settings/nagul`, request);
  }

  getWorkerCellularAccessSettings(): Observable<AdminWorkerCellularAccessSettings> {
    return this.http.get<AdminWorkerCellularAccessSettings>(
      `${this.baseUrl}/settings/worker-cellular-access`
    );
  }

  updateWorkerCellularAccessSettings(
    request: WorkerCellularAccessSettingsRequest
  ): Observable<AdminWorkerCellularAccessSettings> {
    return this.http.put<AdminWorkerCellularAccessSettings>(
      `${this.baseUrl}/settings/worker-cellular-access`,
      request
    );
  }

  getTelegramReportSettings(): Observable<AdminTelegramReportScheduleSettings> {
    return this.http.get<AdminTelegramReportScheduleSettings>(
      `${this.baseUrl}/settings/telegram-reports`
    );
  }

  updateTelegramReportSettings(
    request: TelegramReportScheduleSettingsRequest
  ): Observable<AdminTelegramReportScheduleSettings> {
    return this.http.put<AdminTelegramReportScheduleSettings>(
      `${this.baseUrl}/settings/telegram-reports`,
      request
    );
  }

  getWhatsAppGroupSyncSettings(): Observable<AdminWhatsAppGroupSyncSettings> {
    return this.http.get<AdminWhatsAppGroupSyncSettings>(
      `${this.baseUrl}/settings/whatsapp-group-sync`
    );
  }

  updateWhatsAppGroupSyncSettings(
    request: WhatsAppGroupSyncSettingsRequest
  ): Observable<AdminWhatsAppGroupSyncSettings> {
    return this.http.put<AdminWhatsAppGroupSyncSettings>(
      `${this.baseUrl}/settings/whatsapp-group-sync`,
      request
    );
  }

  runWhatsAppGroupSync(): Observable<AdminWhatsAppGroupSyncSettings> {
    return this.http.post<AdminWhatsAppGroupSyncSettings>(
      `${this.baseUrl}/settings/whatsapp-group-sync/run`,
      {}
    );
  }

  getClientPublicationProgressReportSettings(): Observable<AdminClientPublicationProgressReportSettings> {
    return this.http.get<AdminClientPublicationProgressReportSettings>(
      `${this.baseUrl}/settings/client-publication-progress-reports`
    );
  }

  updateClientPublicationProgressReportSettings(
    request: ClientPublicationProgressReportSettingsRequest
  ): Observable<AdminClientPublicationProgressReportSettings> {
    return this.http.put<AdminClientPublicationProgressReportSettings>(
      `${this.baseUrl}/settings/client-publication-progress-reports`,
      request
    );
  }

  runSharedChatLinkSync(): Observable<AdminSharedChatLinkSyncResponse> {
    return this.http.post<AdminSharedChatLinkSyncResponse>(
      `${this.baseUrl}/settings/shared-chat-links/sync`,
      {}
    );
  }
}
