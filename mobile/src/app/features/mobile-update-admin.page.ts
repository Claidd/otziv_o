import { Capacitor } from '@capacitor/core';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { IonContent, IonRefresher, IonRefresherContent, type RefresherCustomEvent } from '@ionic/angular/standalone';
import { firstValueFrom } from 'rxjs';
import { AppUpdater, type AppUpdaterInfo } from '../core/mobile-app-update.plugin';
import { MobileUpdateAdminApi, type MobilePublishedRelease } from '../core/mobile-update-admin.api';
import { formatUpdateSize } from '../core/mobile-update.helpers';
import { MobileExternalLinkService } from '../shared/mobile-external-link.service';
import { MobileHeaderComponent } from '../shared/mobile-header.component';

const RELEASE_GUIDE = 'https://github.com/Claidd/otziv_o/blob/main/docs/UNIFIED_MAIN_WORKFLOW.md';
const SIGNING_GUIDE = 'https://github.com/Claidd/otziv_o/blob/main/docs/ANDROID_RELEASE_SIGNING.md';

@Component({
  selector: 'app-admin-mobile-update-page',
  imports: [IonContent, IonRefresher, IonRefresherContent, MobileHeaderComponent],
  template: `
    <div class="ion-page">
      <app-mobile-header title="Обновление" />
      <ion-content>
        <ion-refresher slot="fixed" (ionRefresh)="refresh($event)"><ion-refresher-content /></ion-refresher>
        <main class="update-page">
          <header class="page-head"><div><small>АДМИН / ANDROID</small><h1>Версия приложения</h1></div><button type="button" (click)="load()" [disabled]="loading()" aria-label="Обновить"><span class="material-icons-sharp">refresh</span></button></header>
          @if (error()) { <p class="alert" role="alert">{{ error() }}</p> }
          @if (loading() && !release()) { <p class="card">Загружаю сведения об обновлении…</p> }
          @if (release(); as current) {
            <section class="card">
              <div class="section-head"><h2>Опубликованная версия</h2><span class="status" [class.available]="current.enabled">{{ current.enabled ? 'Опубликована' : 'Нет публикации' }}</span></div>
              @if (current.enabled) {
                <strong class="version">{{ current.versionName || '—' }}</strong>
                <div class="metrics"><span><small>versionCode</small><b>{{ current.versionCode ?? '—' }}</b></span><span><small>Размер</small><b>{{ fileSize(current.fileSize) }}</b></span><span><small>Дата</small><b>{{ date(current.publishedAt) }}</b></span><span><small>Обновление</small><b>{{ current.required ? 'Обязательное' : 'Можно отложить' }}</b></span></div>
                <p>Минимальный versionCode: <b>{{ current.minSupportedVersionCode ?? 0 }}</b></p>
                @if (current.notes) { <p class="notes">{{ current.notes }}</p> }
                @if (current.sha256) { <div class="checksum"><small>SHA-256 файла</small><code>{{ current.sha256 }}</code></div> }
              } @else { <p>APK для сотрудников ещё не опубликован.</p> }
            </section>
            <section class="card">
              <h2>Это устройство</h2>
              @if (installed(); as device) { <p><b>{{ device.versionName }}</b> · versionCode {{ device.versionCode }}</p><p class="update-status">{{ updateStatus() }}</p> }
              @else { <p>{{ deviceError() || 'Номер установленной версии доступен в Android-приложении.' }}</p> }
            </section>
          }
          <section class="card release-flow">
            <h2>Как публикуется APK</h2>
            <p>Новая версия проходит сборку и проверку подписи, CI точного коммита main, резервную копию и установку через управляемый выпуск. После него приложение сотрудников увидит обновление автоматически.</p>
            <button type="button" (click)="openReleaseGuide()">Открыть инструкцию по выпуску</button>
            <button type="button" (click)="openSigningGuide()">Проверка подписи APK</button>
          </section>
        </main>
      </ion-content>
    </div>
  `,
  styles: [`
    :host{display:contents}ion-content{--background:var(--otziv-background)}.update-page{display:grid;gap:.75rem;max-width:42rem;margin:auto;padding:1rem .8rem calc(5rem + env(safe-area-inset-bottom));color:var(--otziv-dark)}.page-head,.section-head{display:flex;align-items:center;justify-content:space-between;gap:.5rem}.page-head small{font-size:.65rem;font-weight:900;color:var(--otziv-info)}.page-head h1{margin:.1rem 0;font-size:1.3rem}.card{display:grid;gap:.7rem;padding:.85rem;border:1px solid rgba(108,155,207,.23);border-radius:.9rem;background:var(--otziv-white)}.card h2{margin:0;font-size:1rem}.card p{margin:0;color:var(--otziv-info);font-size:.77rem;line-height:1.45}.card p b{color:var(--otziv-dark)}.version{font-size:1.45rem}.metrics{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:.45rem}.metrics span{display:grid;gap:.2rem;min-width:0;padding:.55rem;border-radius:.6rem;background:var(--otziv-muted-surface)}.metrics small,.checksum small{font-size:.64rem;color:var(--otziv-info);font-weight:800}.metrics b{font-size:.8rem}.status{border-radius:999px;padding:.2rem .45rem;background:var(--otziv-muted-surface);font-size:.65rem;font-weight:900}.status.available{background:#ddf5ed;color:#13765b}.notes{white-space:pre-wrap}.checksum{display:grid;gap:.3rem}.checksum code{font-size:.67rem;overflow-wrap:anywhere}.update-status{font-weight:800}.release-flow button{justify-self:start}button{min-height:2.5rem;border:1px solid rgba(108,155,207,.3);border-radius:.7rem;padding:.5rem .7rem;background:var(--otziv-white);color:var(--otziv-primary);font:inherit;font-size:.76rem;font-weight:800}button:disabled{opacity:.5}.alert{margin:0;padding:.7rem;border-radius:.7rem;background:#fff0f4;color:var(--otziv-danger);font-size:.75rem}:host-context(body.otziv-dark-theme) .card,:host-context(body.otziv-dark-theme) button{background:#1f2629}
  `]
})
export class AdminMobileUpdatePage implements OnInit {
  private readonly api = inject(MobileUpdateAdminApi);
  private readonly links = inject(MobileExternalLinkService);
  readonly release = signal<MobilePublishedRelease | null>(null);
  readonly installed = signal<AppUpdaterInfo | null>(null);
  readonly deviceError = signal('');
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly updateStatus = computed(() => {
    const release = this.release();
    const installed = this.installed();
    if (!release?.enabled || !installed || release.versionCode == null) return 'Невозможно сравнить версии.';
    if (release.versionCode > installed.versionCode) {
      return release.required || installed.versionCode < (release.minSupportedVersionCode ?? 0)
        ? 'Доступно обязательное обновление.' : 'Доступна новая версия.';
    }
    if (release.versionCode === installed.versionCode) return 'Установлена актуальная опубликованная версия.';
    return 'На устройстве версия новее опубликованной.';
  });

  ngOnInit(): void { void this.load(); }
  async refresh(event: RefresherCustomEvent): Promise<void> { await this.load(); event.target.complete(); }

  async load(): Promise<void> {
    if (this.loading()) return;
    this.loading.set(true);
    this.error.set(null);
    try { this.release.set(await firstValueFrom(this.api.current())); }
    catch (error) { this.error.set(this.errorMessage(error, 'Не удалось загрузить опубликованную версию.')); }
    if (Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android') {
      try { this.installed.set(await AppUpdater.getInfo()); this.deviceError.set(''); }
      catch { this.installed.set(null); this.deviceError.set('Не удалось прочитать установленную версию.'); }
    } else {
      this.installed.set(null);
      this.deviceError.set('Номер установленной версии доступен в Android-приложении.');
    }
    this.loading.set(false);
  }

  fileSize(bytes: number | null): string { return bytes == null ? '—' : formatUpdateSize(bytes) || '—'; }
  date(value: string | null): string {
    if (!value) return '—';
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString('ru-RU');
  }
  async openReleaseGuide(): Promise<void> { await this.links.open(RELEASE_GUIDE); }
  async openSigningGuide(): Promise<void> { await this.links.open(SIGNING_GUIDE); }

  private errorMessage(error: unknown, fallback: string): string {
    if (error && typeof error === 'object' && 'error' in error) {
      const body = (error as { error?: { detail?: string; message?: string } | string }).error;
      if (typeof body === 'string' && body.trim()) return body;
      if (body && typeof body === 'object') return body.detail || body.message || fallback;
    }
    return fallback;
  }
}
