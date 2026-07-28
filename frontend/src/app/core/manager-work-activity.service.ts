import { HttpClient } from '@angular/common/http';
import { Injectable, NgZone } from '@angular/core';
import { NavigationEnd, Router } from '@angular/router';
import { filter } from 'rxjs';
import { AuthService } from './auth.service';
import { appEnvironment } from './app-environment';

@Injectable({ providedIn: 'root' })
export class ManagerWorkActivityService {
  private readonly heartbeatMs = 30_000;
  private readonly activeWindowMs = 45_000;
  private lastInteractionAt = 0;
  private lastSentAt = 0;
  private started = false;
  private timerId?: ReturnType<typeof setInterval>;
  private readonly sessionId = this.resolveSessionId();

  constructor(
    private readonly http: HttpClient,
    private readonly router: Router,
    private readonly auth: AuthService,
    private readonly zone: NgZone
  ) {}

  start(): void {
    if (this.started) return;
    this.started = true;

    const markInteraction = (): void => {
      if (!this.canTrackActivity()) return;
      this.lastInteractionAt = Date.now();
      this.send('INTERACTION', this.router.url, false);
    };
    window.addEventListener('pointerdown', markInteraction, { passive: true });
    window.addEventListener('keydown', markInteraction, { passive: true });
    window.addEventListener('scroll', markInteraction, { passive: true });

    this.router.events
      .pipe(filter((event): event is NavigationEnd => event instanceof NavigationEnd))
      .subscribe((event) => {
        if (!this.canTrackActivity()) return;
        this.lastInteractionAt = Date.now();
        this.send('NAVIGATION', event.urlAfterRedirects, true);
      });

    this.zone.runOutsideAngular(() => {
      this.timerId = setInterval(() => this.heartbeat(), this.heartbeatMs);
    });
  }

  private heartbeat(): void {
    if (!this.canTrackActivity()) return;
    if (Date.now() - this.lastInteractionAt > this.activeWindowMs) return;
    this.send('ACTIVE_HEARTBEAT', this.router.url, false);
  }

  private send(activityType: string, route: string, force: boolean): void {
    if (!this.canTrackActivity()) return;
    if (!this.auth.isAuthenticated()) return;
    if (!force && Date.now() - this.lastSentAt < this.heartbeatMs - 2_000) return;
    this.lastSentAt = Date.now();
    this.zone.run(() => {
      this.http.post<void>(`${appEnvironment.apiBaseUrl}/api/manager-activity`, {
        activityType,
        route,
        sessionId: this.sessionId
      }).subscribe({ error: () => undefined });
    });
  }

  private canTrackActivity(): boolean {
    return document.visibilityState === 'visible' && document.hasFocus();
  }

  private resolveSessionId(): string {
    const key = 'otziv-manager-activity-session';
    const existing = sessionStorage.getItem(key);
    if (existing) return existing;
    const value = typeof crypto !== 'undefined' && 'randomUUID' in crypto
      ? crypto.randomUUID()
      : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
    sessionStorage.setItem(key, value);
    return value;
  }
}
