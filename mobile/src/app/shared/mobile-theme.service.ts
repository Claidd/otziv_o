import { Injectable, signal } from '@angular/core';

export type MobileThemeMode = 'light' | 'dark';

@Injectable({ providedIn: 'root' })
export class MobileThemeService {
  private readonly storageKey = 'otziv-theme';

  readonly theme = signal<MobileThemeMode>(this.initialTheme());

  constructor() {
    this.applyTheme(this.theme());
  }

  setTheme(theme: MobileThemeMode): void {
    this.theme.set(theme);
    this.applyTheme(theme);
    localStorage.setItem(this.storageKey, theme);
  }

  private initialTheme(): MobileThemeMode {
    const savedTheme = localStorage.getItem(this.storageKey);

    if (savedTheme === 'light' || savedTheme === 'dark') {
      return savedTheme;
    }

    return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }

  private applyTheme(theme: MobileThemeMode): void {
    const isDark = theme === 'dark';
    document.body.classList.toggle('otziv-dark-theme', isDark);
    this.updateMeta('theme-color', isDark ? '#181a1e' : '#f6f6f9');
    this.updateMeta('color-scheme', isDark ? 'dark light' : 'light dark');
  }

  private updateMeta(name: string, content: string): void {
    document.querySelector(`meta[name="${name}"]`)?.setAttribute('content', content);
  }
}
