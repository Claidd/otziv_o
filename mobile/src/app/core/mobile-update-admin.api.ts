import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import type { Observable } from 'rxjs';
import { mobileEnvironment } from './mobile-environment';

export interface MobilePublishedRelease {
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

@Injectable({ providedIn: 'root' })
export class MobileUpdateAdminApi {
  private readonly http = inject(HttpClient);

  current(): Observable<MobilePublishedRelease> {
    return this.http.get<MobilePublishedRelease>(`${mobileEnvironment.apiBaseUrl}/api/mobile-update`);
  }
}
