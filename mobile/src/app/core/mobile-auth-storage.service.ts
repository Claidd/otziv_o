import { Injectable } from '@angular/core';
import { Capacitor } from '@capacitor/core';
import { Preferences } from '@capacitor/preferences';
import { SecureStorage, type DataType } from '@aparajita/capacitor-secure-storage';
import type { PendingLogin, StoredTokens } from './auth.models';
import { isPendingLogin, isStoredTokens } from './auth-storage-validation';
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

  async readTokens(): Promise<StoredTokens | null> {
    if (!this.isNative) {
      await this.removeWebValue(TOKENS_KEY);
      return null;
    }
    const revocation = await Preferences.get({ key: TOKENS_REVOCATION_KEY });
    if (isTokenRevocationMarked(revocation.value)) {
      await this.clearTokens();
      return null;
    }
    const value = await this.readJson<Record<string, unknown>>(TOKENS_KEY);
    if (!value) {
      return null;
    }
    if (isStoredTokens(value)) {
      return value;
    }
    await this.clearTokens();
    return null;
  }

  async writeTokens(tokens: StoredTokens): Promise<void> {
    if (!this.isNative) {
      await this.removeWebValue(TOKENS_KEY);
      return;
    }
    await this.writeJson(TOKENS_KEY, tokens);
    await Preferences.remove({ key: TOKENS_REVOCATION_KEY });
  }

  async clearTokens(): Promise<void> {
    if (!this.isNative) {
      await this.removeWebValue(TOKENS_KEY);
      return;
    }

    this.requireNativeSecureStorage();
    await revokeStoredTokens({
      persistMarker: () => Preferences.set({
        key: TOKENS_REVOCATION_KEY,
        value: TOKEN_REVOCATION_MARKER
      }),
      overwriteSecureToken: () => SecureStorage.set(TOKENS_KEY, {
        revoked: true
      } as DataType),
      removeLegacyToken: () => Preferences.remove({ key: TOKENS_KEY }),
      removeSecureToken: async () => {
        await SecureStorage.remove(TOKENS_KEY);
      },
      clearMarker: () => Preferences.remove({ key: TOKENS_REVOCATION_KEY })
    });
  }

  async writePendingLogin(login: PendingLogin): Promise<void> {
    await this.writeJson(PENDING_LOGIN_KEY, login);
  }

  async readPendingLogin(): Promise<PendingLogin | null> {
    const value = await this.readJson<Record<string, unknown>>(PENDING_LOGIN_KEY);
    if (!value) {
      return null;
    }
    if (isPendingLogin(value)) {
      return value;
    }
    await this.clearPendingLogin();
    return null;
  }

  async clearPendingLogin(): Promise<void> {
    await this.removeJson(PENDING_LOGIN_KEY);
  }

  private async readJson<T extends object>(key: string): Promise<T | null> {
    if (this.isNative) {
      this.requireNativeSecureStorage();
      const secureValue = await SecureStorage.get(key);
      if (secureValue !== null) {
        try {
          const normalized = this.normalizeStoredValue<T>(secureValue);
          if (normalized) {
            // Finish an interrupted legacy migration. SecureStorage.set() and
            // Preferences.remove() cannot be atomic across the two plugins,
            // so a crash between them may otherwise leave a plaintext copy.
            await Preferences.remove({ key });
            return normalized;
          }
        } catch {
          // Invalid encrypted data is removed below and never falls back to a
          // potentially stale legacy Preferences value.
        }
        await this.removeJson(key);
        return null;
      }

      const legacyValue = await this.readPreference<T>(key);
      if (legacyValue) {
        await SecureStorage.set(key, legacyValue as DataType);
        await Preferences.remove({ key });
      }
      return legacyValue;
    }

    return this.readSessionValue<T>(key);
  }

  private async writeJson<T extends object>(key: string, value: T): Promise<void> {
    if (this.isNative) {
      this.requireNativeSecureStorage();
      await SecureStorage.set(key, value as DataType);
      await Preferences.remove({ key });
      return;
    }

    this.sessionStorage().setItem(key, JSON.stringify(value));
    await Preferences.remove({ key });
  }

  private async removeJson(key: string): Promise<void> {
    if (this.isNative) {
      this.requireNativeSecureStorage();
      await SecureStorage.remove(key);
    }
    if (!this.isNative) {
      this.sessionStorage().removeItem(key);
    }
    await Preferences.remove({ key });
  }

  private async readPreference<T extends object>(key: string): Promise<T | null> {
    const result = await Preferences.get({ key });
    if (!result.value) {
      return null;
    }

    try {
      return JSON.parse(result.value) as T;
    } catch {
      await Preferences.remove({ key });
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
