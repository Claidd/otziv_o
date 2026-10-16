import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { MobileUpdateApi, type MobileUpdateRelease } from '../../../core/mobile-update.api';
import { AdminLayoutComponent } from '../../../shared/admin-layout.component';
import { apiErrorDetail } from '../../../shared/api-error-message';

@Component({
  selector: 'app-mobile-update',
  imports: [AdminLayoutComponent, DatePipe],
  templateUrl: './mobile-update.component.html',
  styleUrl: './mobile-update.component.scss'
})
export class MobileUpdateComponent {
  private readonly api = inject(MobileUpdateApi);

  readonly current = signal<MobileUpdateRelease | null>(null);
  readonly loading = signal(true);
  readonly error = signal('');

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.api.current().subscribe({
      next: (release) => {
        this.current.set(release);
        this.loading.set(false);
      },
      error: (error) => {
        this.error.set(apiErrorDetail(error, 'Не удалось загрузить сведения о мобильной версии.'));
        this.loading.set(false);
      }
    });
  }

  formatSize(bytes: number | null): string {
    return bytes ? `${(bytes / 1024 / 1024).toFixed(1).replace('.', ',')} МБ` : '—';
  }
}
