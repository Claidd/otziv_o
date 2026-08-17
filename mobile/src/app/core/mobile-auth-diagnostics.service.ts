import { Injectable, effect, signal } from '@angular/core';
import { Capacitor, CapacitorHttp } from '@capacitor/core';
import { Preferences } from '@capacitor/preferences';
import { NavigationEnd, Router } from '@angular/router';
import { AppDiagnostics, type PreviousProcessExit } from './app-diagnostics.plugin';
import { mobileEnvironment } from './mobile-environment';
import { MobileNativeService } from './mobile-native.service';

const DIAGNOSTIC_BUFFER_KEY = 'otziv.mobile.auth-diagnostics.v1';
const MAX_BUFFERED_EVENTS = 160;
const MAX_BATCH_EVENTS = 80;
const MAX_EVENT_AGE_MS = 7 * 24 * 60 * 60 * 1000;

export type MobileAuthDiagnosticValue = string | number | boolean | null | undefined;

interface MobileAuthDiagnosticEvent {
  eventId: string;
  occurredAt: string;
  type: string;
  runId: string;
  appVersion: string;
  appBuild: string;
  networkType: string;
  connected: boolean;
  details: Record<string, string>;
}

interface MobileAuthDiagnosticBatch {
  batchId: string;
  installationId: string;
  events: MobileAuthDiagnosticEvent[];
}

@Injectable({ providedIn: 'root' })
export class MobileAuthDiagnosticsService {
  private readonly isNative = Capacitor.isNativePlatform();
  private readonly enabled = signal(false);
  private readonly runId = this.randomId();
  private writeChain: Promise<void> = Promise.resolve();
  private flushPromise: Promise<void> | null = null;
  private lastAppActive: boolean | null = null;
  private lastNetworkKey: string | null = null;
  private currentRoute = '/';
  private lifecycleState = 'starting';
  private pendingBreadcrumb = 'app.starting';
  private processStateTimer: ReturnType<typeof setTimeout> | undefined;

  constructor(
    private readonly native: MobileNativeService,
    private readonly router: Router
  ) {
    effect(() => {
      if (!this.enabled() || !this.native.ready()) {
        return;
      }
      const active = this.native.appActive();
      if (active === this.lastAppActive) {
        return;
      }
      this.lastAppActive = active;
      this.lifecycleState = active ? 'foreground' : 'background';
      void this.record(active ? 'app.foreground' : 'app.background');
    });

    effect(() => {
      if (!this.enabled() || !this.native.ready()) {
        return;
      }
      const network = this.native.networkStatus();
      const key = `${network.connected}:${network.connectionType}`;
      if (key === this.lastNetworkKey) {
        return;
      }
      this.lastNetworkKey = key;
      void this.record('network.changed', {
        connected: network.connected,
        connectionType: network.connectionType
      });
    });
  }

  initialize(): void {
    if (!this.isNative || this.enabled()) {
      return;
    }
    this.enabled.set(true);
    this.native.initialize();
    this.bindNavigationBreadcrumbs();
    this.bindRuntimeErrors();
    void this.capturePreviousProcessExits();
    void this.record('app.cold_start');
  }

  record(type: string, details: Record<string, MobileAuthDiagnosticValue> = {}): Promise<void> {
    return this.recordAt(type, new Date(), details);
  }

  breadcrumb(action: string, immediate = false): void {
    if (!this.isNative || Capacitor.getPlatform() !== 'android') {
      return;
    }
    this.pendingBreadcrumb = this.sanitizeBreadcrumb(action, 48);
    if (immediate) {
      if (this.processStateTimer) {
        clearTimeout(this.processStateTimer);
        this.processStateTimer = undefined;
      }
      void this.persistProcessState();
      return;
    }
    if (this.processStateTimer) {
      clearTimeout(this.processStateTimer);
    }
    this.processStateTimer = setTimeout(() => {
      this.processStateTimer = undefined;
      void this.persistProcessState();
    }, 500);
  }

  checkpoint(action: string): Promise<void> {
    if (!this.isNative || Capacitor.getPlatform() !== 'android') {
      return Promise.resolve();
    }
    this.pendingBreadcrumb = this.sanitizeBreadcrumb(action, 48);
    if (this.processStateTimer) {
      clearTimeout(this.processStateTimer);
      this.processStateTimer = undefined;
    }
    return this.persistProcessState();
  }

  private recordAt(
    type: string,
    occurredAt: Date,
    details: Record<string, MobileAuthDiagnosticValue> = {}
  ): Promise<void> {
    if (!this.isNative) {
      return Promise.resolve();
    }

    const safeType = this.sanitizeType(type);
    if (safeType === 'app.background' || safeType === 'app.foreground') {
      this.breadcrumb(this.pendingBreadcrumb, true);
    } else if (safeType !== 'network.changed' && safeType !== 'app.previous_exit') {
      this.breadcrumb(safeType, safeType.startsWith('runtime.'));
    }
    const telemetry = this.native.nativeTelemetry();
    const network = this.native.networkStatus();
    const event: MobileAuthDiagnosticEvent = {
      eventId: this.randomId(),
      occurredAt: Number.isFinite(occurredAt.getTime()) ? occurredAt.toISOString() : new Date().toISOString(),
      type: safeType,
      runId: this.runId,
      appVersion: this.sanitizeValue(telemetry?.appVersion ?? 'unknown', 32),
      appBuild: this.sanitizeValue(telemetry?.appBuild ?? 'unknown', 32),
      networkType: this.sanitizeValue(network.connectionType ?? 'unknown', 24),
      connected: Boolean(network.connected),
      details: this.sanitizeDetails(details)
    };

    return this.enqueue(async () => {
      const events = await this.readBuffer();
      events.push(event);
      await this.writeBuffer(this.trimBuffer(events));
    });
  }

  private async capturePreviousProcessExits(): Promise<void> {
    if (Capacitor.getPlatform() !== 'android' || !Capacitor.isPluginAvailable('AppDiagnostics')) {
      return;
    }

    try {
      await this.native.accessTelemetryHeaders().catch(() => ({}));
      const report = await AppDiagnostics.getPreviousExits();
      if (!report.supported) {
        await this.record('app.previous_exit_unavailable', {
          androidApi: report.androidApi,
          previousStateSummary: report.previousStateSummary,
          previousStateUpdatedAt: report.previousStateUpdatedAt
        });
        return;
      }

      const exits = report.exits
        .filter((exit) => Number.isFinite(exit.timestamp) && exit.timestamp > 0)
        .sort((left, right) => left.timestamp - right.timestamp);
      for (const exit of exits) {
        await this.recordPreviousExit(exit, report.androidApi, report.lowMemoryKillReportSupported);
      }
      const throughTimestamp = exits.at(-1)?.timestamp;
      if (throughTimestamp) {
        await AppDiagnostics.acknowledgePreviousExits({ throughTimestamp }).catch(() => undefined);
      }
    } catch (error: unknown) {
      await this.record('app.previous_exit_probe_failed', this.runtimeErrorDetails(error));
    }
  }

  private recordPreviousExit(
    exit: PreviousProcessExit,
    androidApi: number,
    lowMemoryKillReportSupported: boolean
  ): Promise<void> {
    return this.recordAt('app.previous_exit', new Date(exit.timestamp), {
      androidApi,
      lowMemoryKillReportSupported,
      reason: exit.reason,
      reasonCode: exit.reasonCode,
      status: exit.status,
      importance: exit.importance,
      importanceCode: exit.importanceCode,
      pssKb: exit.pssKb,
      rssKb: exit.rssKb,
      description: exit.description,
      stateSummary: exit.stateSummary,
      stateSource: exit.stateSource,
      androidStateSummaryRejected: exit.androidStateSummaryRejected
    });
  }

  private bindNavigationBreadcrumbs(): void {
    this.currentRoute = this.sanitizeRoute(this.router.url);
    this.router.events.subscribe((event) => {
      if (!(event instanceof NavigationEnd)) {
        return;
      }
      this.currentRoute = this.sanitizeRoute(event.urlAfterRedirects);
      this.breadcrumb('navigation.changed');
    });
  }

  private bindRuntimeErrors(): void {
    window.addEventListener('error', (event) => {
      void this.record('runtime.javascript_error', {
        route: this.currentRoute,
        errorClass: event.error instanceof Error ? event.error.constructor.name : 'ErrorEvent',
        message: this.sanitizeRuntimeMessage(event.message || 'unknown'),
        source: this.safeSourcePath(event.filename),
        line: event.lineno,
        column: event.colno
      });
    });

    window.addEventListener('unhandledrejection', (event) => {
      void this.record('runtime.unhandled_rejection', {
        route: this.currentRoute,
        ...this.runtimeErrorDetails(event.reason)
      });
    });
  }

  private persistProcessState(): Promise<void> {
    if (!Capacitor.isPluginAvailable('AppDiagnostics')) {
      return Promise.resolve();
    }
    const build = this.native.nativeTelemetry()?.appBuild ?? 'unknown';
    const summary = [
      'v1',
      `route=${this.currentRoute}`,
      `state=${this.sanitizeBreadcrumb(this.lifecycleState, 16)}`,
      `action=${this.pendingBreadcrumb}`,
      `build=${this.sanitizeBreadcrumb(build, 16)}`
    ].join(';');
    return AppDiagnostics.setProcessStateSummary({ summary }).catch(() => undefined);
  }

  private runtimeErrorDetails(error: unknown): Record<string, MobileAuthDiagnosticValue> {
    return {
      errorClass: error instanceof Error ? error.constructor.name : typeof error,
      message: this.sanitizeRuntimeMessage(error instanceof Error ? error.message : String(error ?? 'unknown'))
    };
  }

  private sanitizeRuntimeMessage(message: string): string {
    return message
      .replace(/bearer\s+[a-z0-9._~+/-]+=*/giu, 'Bearer [redacted]')
      .replace(/\beyj[a-z0-9_-]{20,}\.[a-z0-9_-]{20,}(?:\.[a-z0-9_-]+)?/giu, '[redacted-jwt]')
      .replace(/((?:access_token|refresh_token|id_token|authorization|authorization_code|code_verifier|pass(?:word)|code)=)[^&\s]+/giu, '$1[redacted]')
      .slice(0, 200);
  }

  private safeSourcePath(source: string): string {
    if (!source) {
      return 'unknown';
    }
    try {
      return this.sanitizeValue(new URL(source, window.location.origin).pathname, 120);
    } catch {
      return 'unknown';
    }
  }

  private sanitizeRoute(route: string): string {
    const path = route.split(/[?#]/u, 1)[0] || '/';
    return path
      .replace(/\/[0-9a-f]{8}-[0-9a-f-]{27,}(?=\/|$)/giu, '/:id')
      .replace(/\/\d+(?=\/|$)/gu, '/:id')
      .replace(/[^a-z0-9_./:-]+/giu, '_')
      .slice(0, 64) || '/';
  }

  private sanitizeBreadcrumb(value: string, maxLength: number): string {
    return value.trim().toLowerCase().replace(/[^a-z0-9_./:-]+/g, '_').slice(0, maxLength) || 'unknown';
  }

  flush(accessToken: string): Promise<void> {
    if (!this.isNative || !accessToken.trim()) {
      return Promise.resolve();
    }
    if (this.flushPromise) {
      return this.flushPromise;
    }

    this.flushPromise = this.doFlush(accessToken)
      .catch(() => undefined)
      .finally(() => {
        this.flushPromise = null;
      });
    return this.flushPromise;
  }

  private async doFlush(accessToken: string): Promise<void> {
    await this.writeChain.catch(() => undefined);
    const buffered = this.trimBuffer(await this.readBuffer());
    const events = buffered.slice(0, MAX_BATCH_EVENTS);
    if (events.length === 0) {
      return;
    }

    const telemetryHeaders = await this.native.accessTelemetryHeaders();
    const installationId = telemetryHeaders['X-Otziv-Installation-Id'] ?? 'unknown';
    const batch: MobileAuthDiagnosticBatch = {
      batchId: this.randomId(),
      installationId: this.sanitizeValue(installationId, 128),
      events
    };
    const response = await CapacitorHttp.post({
      url: `${mobileEnvironment.apiBaseUrl}/api/mobile/auth-diagnostics`,
      headers: {
        ...telemetryHeaders,
        Accept: 'application/json',
        'Content-Type': 'application/json',
        Authorization: `Bearer ${accessToken}`
      },
      data: batch,
      connectTimeout: 10_000,
      readTimeout: 15_000
    });
    if (response.status < 200 || response.status >= 300) {
      return;
    }

    const sentIds = new Set(events.map((event) => event.eventId));
    await this.enqueue(async () => {
      const latest = await this.readBuffer();
      await this.writeBuffer(latest.filter((event) => !sentIds.has(event.eventId)));
    });
  }

  private enqueue(operation: () => Promise<void>): Promise<void> {
    const next = this.writeChain
      .catch(() => undefined)
      .then(operation)
      .catch(() => undefined);
    this.writeChain = next;
    return next;
  }

  private async readBuffer(): Promise<MobileAuthDiagnosticEvent[]> {
    const stored = await Preferences.get({ key: DIAGNOSTIC_BUFFER_KEY });
    if (!stored.value) {
      return [];
    }
    try {
      const parsed = JSON.parse(stored.value) as unknown;
      return Array.isArray(parsed)
        ? parsed.filter((event): event is MobileAuthDiagnosticEvent => this.isStoredEvent(event))
        : [];
    } catch {
      await Preferences.remove({ key: DIAGNOSTIC_BUFFER_KEY });
      return [];
    }
  }

  private async writeBuffer(events: MobileAuthDiagnosticEvent[]): Promise<void> {
    if (events.length === 0) {
      await Preferences.remove({ key: DIAGNOSTIC_BUFFER_KEY });
      return;
    }
    await Preferences.set({
      key: DIAGNOSTIC_BUFFER_KEY,
      value: JSON.stringify(events)
    });
  }

  private trimBuffer(events: MobileAuthDiagnosticEvent[]): MobileAuthDiagnosticEvent[] {
    const cutoff = Date.now() - MAX_EVENT_AGE_MS;
    return events
      .filter((event) => Date.parse(event.occurredAt) >= cutoff)
      .slice(-MAX_BUFFERED_EVENTS);
  }

  private isStoredEvent(value: unknown): value is MobileAuthDiagnosticEvent {
    if (!value || typeof value !== 'object') {
      return false;
    }
    const event = value as Partial<MobileAuthDiagnosticEvent>;
    return typeof event.eventId === 'string'
      && typeof event.occurredAt === 'string'
      && Number.isFinite(Date.parse(event.occurredAt))
      && typeof event.type === 'string'
      && typeof event.runId === 'string'
      && typeof event.appVersion === 'string'
      && typeof event.appBuild === 'string'
      && typeof event.networkType === 'string'
      && typeof event.connected === 'boolean'
      && Boolean(event.details)
      && typeof event.details === 'object';
  }

  private sanitizeDetails(details: Record<string, MobileAuthDiagnosticValue>): Record<string, string> {
    return Object.fromEntries(Object.entries(details)
      .slice(0, 20)
      .map(([key, value]) => [
        this.sanitizeValue(key, 48),
        this.sanitizeValue(value === null || value === undefined ? 'unknown' : String(value), 200)
      ])
      .filter(([key]) => key.length > 0));
  }

  private sanitizeType(type: string): string {
    const normalized = type.trim().toLowerCase().replace(/[^a-z0-9_.-]+/g, '_');
    return normalized.slice(0, 64) || 'unknown';
  }

  private sanitizeValue(value: string, maxLength: number): string {
    return value.replace(/[\u0000-\u001f\u007f]+/g, ' ').trim().slice(0, maxLength) || 'unknown';
  }

  private randomId(): string {
    return typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
      ? crypto.randomUUID()
      : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
  }
}
