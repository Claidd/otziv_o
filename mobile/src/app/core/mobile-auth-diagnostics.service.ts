import { Injectable, effect, signal } from '@angular/core';
import { Capacitor, CapacitorHttp } from '@capacitor/core';
import { Preferences } from '@capacitor/preferences';
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

  constructor(private readonly native: MobileNativeService) {
    effect(() => {
      if (!this.enabled() || !this.native.ready()) {
        return;
      }
      const active = this.native.appActive();
      if (active === this.lastAppActive) {
        return;
      }
      this.lastAppActive = active;
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
    void this.record('app.cold_start');
  }

  record(type: string, details: Record<string, MobileAuthDiagnosticValue> = {}): Promise<void> {
    if (!this.isNative) {
      return Promise.resolve();
    }

    const telemetry = this.native.nativeTelemetry();
    const network = this.native.networkStatus();
    const event: MobileAuthDiagnosticEvent = {
      eventId: this.randomId(),
      occurredAt: new Date().toISOString(),
      type: this.sanitizeType(type),
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
