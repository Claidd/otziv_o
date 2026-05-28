import { Injectable } from '@angular/core';
import { Capacitor } from '@capacitor/core';
import { Preferences } from '@capacitor/preferences';
import { SecureStorage, type DataType } from '@aparajita/capacitor-secure-storage';
import type { PendingLogin, StoredTokens } from './auth.models';

const TOKENS_KEY = 'otziv.mobile.tokens';
const PENDING_LOGIN_KEY = 'otziv.mobile.pendingLogin';

@Injectable({ providedIn: 'root' })
export class MobileAuthStorageService {
  private readonly secureStorageAvailable = Capacitor.isNativePlatform() && Capacitor.isPluginAvailable('SecureStorage');

  async readTokens(): Promise<StoredTokens | null> {
    return this.readJson<StoredTokens>(TOKENS_KEY);
  }

  async writeTokens(tokens: StoredTokens): Promise<void> {
    await this.writeJson(TOKENS_KEY, tokens);
  }

  async clearTokens(): Promise<void> {
    await this.removeJson(TOKENS_KEY);
  }

  async writePendingLogin(login: PendingLogin): Promise<void> {
    await this.writeJson(PENDING_LOGIN_KEY, login);
  }

  async readPendingLogin(): Promise<PendingLogin | null> {
    return this.readJson<PendingLogin>(PENDING_LOGIN_KEY);
  }

  async clearPendingLogin(): Promise<void> {
    await this.removeJson(PENDING_LOGIN_KEY);
  }

  private async readJson<T extends object>(key: string): Promise<T | null> {
    try {
      if (this.secureStorageAvailable) {
        const secureValue = await SecureStorage.get(key).catch(() => null);
        if (secureValue) {
          return this.normalizeStoredValue<T>(secureValue);
        }

        const legacyValue = await this.readPreference<T>(key);
        if (legacyValue) {
          await SecureStorage.set(key, legacyValue as DataType);
          await Preferences.remove({ key });
        }
        return legacyValue;
      }

      return this.readPreference<T>(key);
    } catch {
      await this.removeJson(key);
      return null;
    }
  }

  private async writeJson<T extends object>(key: string, value: T): Promise<void> {
    if (this.secureStorageAvailable) {
      await SecureStorage.set(key, value as DataType);
      await Preferences.remove({ key });
      return;
    }

    await Preferences.set({ key, value: JSON.stringify(value) });
  }

  private async removeJson(key: string): Promise<void> {
    if (this.secureStorageAvailable) {
      await SecureStorage.remove(key).catch(() => false);
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
}
