import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { appEnvironment } from './app-environment';

export interface MobileUpdateRelease {
  enabled: boolean;
  versionCode: number | null;
  versionName: string | null;
  minSupportedVersionCode: number | null;
  required: boolean | null;
  notes: string | null;
  downloadUrl: string | null;
  fileSize: number | null;
  sha256: string | null;
  publishedAt: string | null;
}

export interface PublishMobileUpdateRequest {
  apk: File;
  versionCode: number;
  versionName: string;
  minSupportedVersionCode: number;
  required: boolean;
  notes: string;
}

@Injectable({ providedIn: 'root' })
export class MobileUpdateApi {
  constructor(private readonly http: HttpClient) {}

  current(): Observable<MobileUpdateRelease> {
    return this.http.get<MobileUpdateRelease>(`${appEnvironment.apiBaseUrl}/api/mobile-update`);
  }

  publish(request: PublishMobileUpdateRequest): Observable<MobileUpdateRelease> {
    const body = new FormData();
    body.append('apk', request.apk);
    body.append('versionCode', String(request.versionCode));
    body.append('versionName', request.versionName);
    body.append('minSupportedVersionCode', String(request.minSupportedVersionCode));
    body.append('required', String(request.required));
    body.append('notes', request.notes);
    return this.http.post<MobileUpdateRelease>(`${appEnvironment.apiBaseUrl}/api/admin/mobile-update`, body);
  }
}
