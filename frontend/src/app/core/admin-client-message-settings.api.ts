import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { appEnvironment } from './app-environment';
import type {
  AdminClientMessageSettings,
  ClientMessageSettingsRequest,
} from './admin-dictionaries.api';

@Injectable({ providedIn: 'root' })
export class AdminClientMessageSettingsApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${appEnvironment.apiBaseUrl}/api/admin`;

  getClientMessageSettings(): Observable<AdminClientMessageSettings> {
    return this.http.get<AdminClientMessageSettings>(`${this.baseUrl}/settings/client-messages`);
  }

  updateClientMessageSettings(
    request: ClientMessageSettingsRequest,
  ): Observable<AdminClientMessageSettings> {
    return this.http.put<AdminClientMessageSettings>(
      `${this.baseUrl}/settings/client-messages`,
      request,
    );
  }
}
