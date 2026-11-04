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

// Publishing through /api/admin/mobile-update is closed on the server (410)
// since the signing key change; releases go to the /android-new/ channel.
@Injectable({ providedIn: 'root' })
export class MobileUpdateApi {
  constructor(private readonly http: HttpClient) {}

  current(): Observable<MobileUpdateRelease> {
    return this.http.get<MobileUpdateRelease>(`${appEnvironment.apiBaseUrl}/api/mobile-update`);
  }
}
