import { Injectable } from '@angular/core';
import { Capacitor } from '@capacitor/core';
import { Preferences } from '@capacitor/preferences';
import { SecureStorage, type DataType } from '@aparajita/capacitor-secure-storage';
import type { PendingLogin, StoredTokens } from './auth.models';
import { isPendingLogin, isStoredTokens } from './auth-storage-validation';
import { flushAndroidAuthStorage, type AuthStorageStore } from './auth-storage-durability.plugin';
import {
  isTokenRevocationMarked,
  revokeStoredTokens,
  TOKEN_REVOCATION_MARKER
} from './auth-token-revocation';

const TOKENS_KEY = 'otziv.mobile.tokens';
const TOKENS_REVOCATION_KEY = 'otziv.mobile.tokens.revoked';
const PENDING_LOGIN_KEY = 'otziv.mobile.pendingLogin';

@Injectable({ providedIn: 'root' })
export class MobileAuthStorageService {
  private readonly isNative = Capacitor.isNativePlatform();
  private readonly secureStorageAvailable = this.isNative && Capacitor.isPluginAvailable('SecureStorage');
  private storageWork: Promise<unknown> = Promise.resolve();
  private tokenMutationEpoch = 0;
  private tokenReadsBlocked = false;

  async readTokens(): Promise<StoredTokens | null> {
    return this.serialize(() => this.readTokensNow());
  }

  private async readTokensNow(): Promise<StoredTokens | null> {
    if (!this.isNative) {
      await this.removeWebValue(TOKENS_KEY);
      return null;
    }
    if (this.tokenReadsBlocked) return null;
    const revocation = await Preferences.get({ key: TOKENS_REVOCATION_KEY });
    if (isTokenRevocationMarked(revocation.value)) {
      await this.clearTokensNow();
      return null;
    }
    const value = await this.readJson<Record<string, unknown>>(TOKENS_KEY);
    if (!value) {
      return null;
    }
    if (this.tokenReadsBlocked) return null;
    if (isStoredTokens(value)) {
      return value;
    }
    await this.clearTokensNow();
    return null;
  }

  async writeTokens(tokens: StoredTokens): Promise<void> {
    const epoch = ++this.tokenMutationEpoch;
    this.tokenReadsBlocked = true;
    return this.serialize(async () => {
      await this.writeTokensNow(tokens);
      if (epoch === this.tokenMutationEpoch) this.tokenReadsBlocked = false;
    });
  }

  private async writeTokensNow(tokens: StoredTokens): Promise<void> {
    if (!this.isNative) {
      await this.removeWebValue(TOKENS_KEY);
      return;
    }
    // An interrupted Android token replacement must not revive the previous
    // refresh token. Keep this non-secret barrier until both stores are durable.
    if (Capacitor.getPlatform() === 'android') await this.persistRevocationMarker();
    await this.writeJson(TOKENS_KEY, tokens);
    await this.clearRevocationMarker();
  }

  async clearTokens(): Promise<void> {
    ++this.tokenMutationEpoch;
    this.tokenReadsBlocked = true;
    return this.serialize(() => this.clearTokensNow());
  }

  private async clearTokensNow(): Promise<void> {
    this.tokenReadsBlocked = true;
    if (!this.isNative) {
      await this.removeWebValue(TOKENS_KEY);
      return;
    }

    this.requireNativeSecureStorage();
    let durabilityFailure: unknown;
    const checkFlush = async (store: AuthStorageStore) => {
      try { await flushAndroidAuthStorage(store); }
      catch (error) { durabilityFailure ??= error; throw error; }
    };
    await revokeStoredTokens({
      persistMarker: async () => {
        await Preferences.set({ key: TOKENS_REVOCATION_KEY, value: TOKEN_REVOCATION_MARKER });
        await checkFlush('preferences');
      },
      overwriteSecureToken: async () => {
        await SecureStorage.set(TOKENS_KEY, { revoked: true } as DataType);
        await checkFlush('secure');
      },
      removeLegacyToken: async () => {
        await Preferences.remove({ key: TOKENS_KEY });
        await checkFlush('preferences');
      },
      removeSecureToken: async () => {
        await SecureStorage.remove(TOKENS_KEY);
        await checkFlush('secure');
      },
      clearMarker: async () => {
        if (durabilityFailure) throw durabilityFailure;
        try { await this.clearRevocationMarker(); }
        catch (error) { durabilityFailure ??= error; throw error; }
      }
    });
    if (durabilityFailure) throw durabilityFailure;
  }

  async writePendingLogin(login: PendingLogin): Promise<void> {
    return this.serialize(() => this.writeJson(PENDING_LOGIN_KEY, login));
  }

  async readPendingLogin(): Promise<PendingLogin | null> {
    return this.serialize(() => this.readPendingLoginNow());
  }

  private async readPendingLoginNow(): Promise<PendingLogin | null> {
    const value = await this.readJson<Record<string, unknown>>(PENDING_LOGIN_KEY);
    if (!value) {
      return null;
    }
    if (isPendingLogin(value)) {
      return value;
    }
    await this.removeJson(PENDING_LOGIN_KEY);
    return null;
  }

  async clearPendingLogin(): Promise<void> {
    return this.serialize(() => this.removeJson(PENDING_LOGIN_KEY));
  }

  private async readJson<T extends object>(key: string): Promise<T | null> {
    if (this.isNative) {
      this.requireNativeSecureStorage();
      const secureValue = await SecureStorage.get(key);
      if (secureValue !== null) {
        let normalized: T | null = null;
        try {
          normalized = this.normalizeStoredValue<T>(secureValue);
        } catch {
          // Invalid encrypted data is removed below and never falls back to a
          // potentially stale legacy Preferences value.
        }
        if (normalized) {
          // Complete interrupted migration only after the secure store is durable.
          // A flush/cleanup failure is not malformed data and must reach the caller.
          await flushAndroidAuthStorage('secure');
          await this.removePreference(key);
          return normalized;
        }
        await this.removeJson(key);
        return null;
      }

      const legacyValue = await this.readPreference<T>(key);
      if (legacyValue) {
        await SecureStorage.set(key, legacyValue as DataType);
        await flushAndroidAuthStorage('secure');
        await this.removePreference(key);
      }
      return legacyValue;
    }

    return this.readSessionValue<T>(key);
  }

  private async writeJson<T extends object>(key: string, value: T): Promise<void> {
    if (this.isNative) {
      this.requireNativeSecureStorage();
      await SecureStorage.set(key, value as DataType);
      await flushAndroidAuthStorage('secure');
      await this.removePreference(key);
      return;
    }

    this.sessionStorage().setItem(key, JSON.stringify(value));
    await Preferences.remove({ key });
  }

  private async removeJson(key: string): Promise<void> {
    if (this.isNative) {
      this.requireNativeSecureStorage();
      await SecureStorage.remove(key);
      await flushAndroidAuthStorage('secure');
    }
    if (!this.isNative) {
      this.sessionStorage().removeItem(key);
    }
    await this.removePreference(key);
  }

  private async readPreference<T extends object>(key: string): Promise<T | null> {
    const result = await Preferences.get({ key });
    if (!result.value) {
      return null;
    }

    try {
      return JSON.parse(result.value) as T;
    } catch {
      await this.removePreference(key);
      return null;
    }
  }

  private normalizeStoredValue<T extends object>(value: DataType): T | null {
    if (typeof value === 'string') {
      return JSON.parse(value) as T;
    }
    if (value && typeof value === 'object' && !(value instanceof Date) && !Array.isArray(value)) {
      return value as T;
    }
    return null;
  }

  private requireNativeSecureStorage(): void {
    if (!this.secureStorageAvailable) {
      throw new Error('SecureStorage недоступен: хранение сессии на устройстве отключено.');
    }
  }

  private serialize<T>(operation: () => Promise<T>): Promise<T> {
    const work = this.storageWork.then(operation);
    this.storageWork = work.catch(() => undefined);
    return work;
  }

  private async removePreference(key: string): Promise<void> {
    await Preferences.remove({ key });
    await flushAndroidAuthStorage('preferences');
  }

  private async persistRevocationMarker(): Promise<void> {
    await Preferences.set({ key: TOKENS_REVOCATION_KEY, value: TOKEN_REVOCATION_MARKER });
    await flushAndroidAuthStorage('preferences');
  }

  private async clearRevocationMarker(): Promise<void> {
    try {
      await this.removePreference(TOKENS_REVOCATION_KEY);
    } catch (error) {
      // A failed marker-removal commit is uncertain. Restore the fail-closed
      // marker before propagating the original failure; do not claim login/logout.
      if (Capacitor.getPlatform() === 'android') {
        await this.persistRevocationMarker().catch(() => undefined);
      }
      throw error;
    }
  }

  private async removeWebValue(key: string): Promise<void> {
    this.sessionStorage().removeItem(key);
    await Preferences.remove({ key });
  }

  private readSessionValue<T extends object>(key: string): T | null {
    const value = this.sessionStorage().getItem(key);
    if (!value) {
      return null;
    }
    try {
      return JSON.parse(value) as T;
    } catch {
      this.sessionStorage().removeItem(key);
      return null;
    }
  }

  private sessionStorage(): Storage {
    if (typeof window === 'undefined' || !window.sessionStorage) {
      throw new Error('SessionStorage недоступен.');
    }
    return window.sessionStorage;
  }
}
