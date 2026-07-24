import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MobileUpdateApi, type MobileUpdateRelease } from '../../../core/mobile-update.api';
import { AdminLayoutComponent } from '../../../shared/admin-layout.component';
import { apiErrorDetail } from '../../../shared/api-error-message';

@Component({
  selector: 'app-mobile-update',
  imports: [AdminLayoutComponent, DatePipe, FormsModule],
  templateUrl: './mobile-update.component.html',
  styleUrl: './mobile-update.component.scss'
})
export class MobileUpdateComponent {
  private readonly api = inject(MobileUpdateApi);

  readonly current = signal<MobileUpdateRelease | null>(null);
  readonly loading = signal(true);
  readonly publishing = signal(false);
  readonly error = signal('');
  readonly success = signal('');
  file: File | null = null;
  versionCode = 55;
  versionName = '1.0.55';
  minSupportedVersionCode = 0;
  required = false;
  notes = '';

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.api.current().subscribe({
      next: (release) => {
        this.current.set(release);
        this.loading.set(false);
        if (release.enabled && release.versionCode != null) {
          this.versionCode = release.versionCode + 1;
          this.minSupportedVersionCode = release.minSupportedVersionCode ?? 0;
        }
      },
      error: (error) => {
        this.error.set(apiErrorDetail(error, 'Не удалось загрузить сведения о мобильной версии.'));
        this.loading.set(false);
      }
    });
  }

  selectFile(event: Event): void {
    this.file = (event.target as HTMLInputElement).files?.[0] ?? null;
    this.error.set('');
  }

  publish(): void {
    if (!this.file || this.versionCode < 1 || !this.versionName.trim()) {
      this.error.set('Выберите APK и заполните номер и название версии.');
      return;
    }
    this.publishing.set(true);
    this.error.set('');
    this.success.set('');
    this.api.publish({
      apk: this.file,
      versionCode: this.versionCode,
      versionName: this.versionName.trim(),
      minSupportedVersionCode: Math.max(0, this.minSupportedVersionCode),
      required: this.required,
      notes: this.notes.trim()
    }).subscribe({
      next: (release) => {
        this.current.set(release);
        this.publishing.set(false);
        this.success.set(`Версия ${release.versionName} опубликована для сотрудников.`);
        this.file = null;
      },
      error: (error) => {
        this.error.set(apiErrorDetail(error, 'Не удалось опубликовать APK.'));
        this.publishing.set(false);
      }
    });
  }

  formatSize(bytes: number | null): string {
    return bytes ? `${(bytes / 1024 / 1024).toFixed(1).replace('.', ',')} МБ` : '—';
  }
}
