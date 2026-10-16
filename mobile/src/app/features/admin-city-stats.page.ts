import { ChangeDetectionStrategy } from '@angular/core';
import { Capacitor } from '@capacitor/core';
import { Directory, Filesystem } from '@capacitor/filesystem';
import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { IonContent, IonRefresher, IonRefresherContent, type RefresherCustomEvent } from '@ionic/angular';
import { firstValueFrom } from 'rxjs';
import { AdminCityStatsApi, type CityStatsBoard, type CityStatsQuery, type CityStatsSort, type SortDirection } from '../core/admin-city-stats.api';
import { MobileConfirmService } from '../shared/mobile-confirm.service';
import { MobileHeaderComponent } from '../shared/mobile-header.component';

const SORTS: readonly { value: CityStatsSort; label: string; direction: SortDirection }[] = [
  { value: 'name', label: 'Название', direction: 'asc' },
  { value: 'countAll', label: 'Отзывы: все', direction: 'desc' },
  { value: 'countArchive', label: 'Отзывы: без архива', direction: 'desc' },
  { value: 'bots', label: 'Доступные аккаунты', direction: 'desc' },
  { value: 'balance', label: 'Баланс', direction: 'desc' }
];

@Component({
  changeDetection: ChangeDetectionStrategy.Eager,
  selector: 'app-admin-city-stats-page',
  imports: [FormsModule, IonContent, IonRefresher, IonRefresherContent, MobileHeaderComponent],
  template: `
    <div class="ion-page">
      <app-mobile-header title="Города" />
      <ion-content>
        <ion-refresher slot="fixed" (ionRefresh)="refresh($event)"><ion-refresher-content /></ion-refresher>
        <main class="city-page">
          <header class="page-head">
            <div><small>АДМИН / АНАЛИТИКА</small><h1>Статистика городов</h1></div>
            <button type="button" (click)="load()" [disabled]="loading()" aria-label="Обновить статистику"><span class="material-icons-sharp">refresh</span></button>
          </header>

          @if (board(); as data) {
            <section class="totals" aria-label="Общая статистика">
              <article><small>Городов</small><strong>{{ data.statistics.totalCities }}</strong></article>
              <article><small>Неопубликованных без архива</small><strong>{{ data.statistics.totalUnpublishedNotArchive }}</strong></article>
              <article><small>Аккаунтов</small><strong>{{ data.statistics.totalActiveBots }}</strong></article>
              <article><small>Общий баланс</small><strong>{{ data.statistics.totalBotBalance }}</strong></article>
            </section>
          }

          <form class="search" (ngSubmit)="applySearch()">
            <label><span>Поиск по названию</span><input name="citySearch" type="search" [ngModel]="searchDraft()" (ngModelChange)="searchDraft.set($event || '')"></label>
            <button type="submit" [disabled]="loading()">Найти</button>
          </form>

          <div class="filters">
            <label><span>Сортировка</span>
              <select [ngModel]="sort()" (ngModelChange)="setSort($event)" [ngModelOptions]="{ standalone: true }">
                <option [ngValue]="null">По умолчанию</option>
                @for (option of sortOptions; track option.value) { <option [ngValue]="option.value">{{ option.label }}</option> }
              </select>
            </label>
            <button type="button" (click)="toggleDirection()" [disabled]="!sort()" [attr.aria-label]="direction() === 'asc' ? 'По убыванию' : 'По возрастанию'">
              <span class="material-icons-sharp">{{ direction() === 'asc' ? 'arrow_upward' : 'arrow_downward' }}</span>
            </button>
            <label><span>На странице</span>
              <select [ngModel]="size()" (ngModelChange)="setSize($event)" [ngModelOptions]="{ standalone: true }">
                <option [ngValue]="20">20</option><option [ngValue]="50">50</option><option [ngValue]="100">100</option>
              </select>
            </label>
          </div>

          @if (error()) { <p class="alert" role="alert">{{ error() }} <button type="button" (click)="load()">Повторить</button></p> }
          @if (notice()) { <p class="notice" role="status">{{ notice() }}</p> }

          <section class="city-list" aria-label="Города">
            @if (loading() && !board()) { <p class="empty">Загружаю статистику…</p> }
            @for (city of board()?.cities ?? []; track city.cityId) {
              <article class="city-card">
                <header><h2>{{ city.cityTitle }}</h2><small>ID {{ city.cityId }}</small></header>
                <div class="city-metrics">
                  <span><small>Отзывы: все</small><strong>{{ city.unpublishedCount }}</strong></span>
                  <span><small>Без архива</small><strong>{{ city.unpublishedNotArchiveCount }}</strong></span>
                  <span><small>Аккаунтов</small><strong>{{ city.activeBotsCount }}</strong></span>
                  <span><small>Баланс</small><strong [class.negative]="city.botBalance < 0">{{ city.botBalance }}</strong></span>
                </div>
                <p>{{ city.botStatus }} · {{ city.botPercentage }}%</p>
              </article>
            } @empty {
              @if (!loading()) { <p class="empty">Городов по текущим условиям нет.</p> }
            }
          </section>

          @if (board(); as data) {
            <nav class="pager" aria-label="Страницы городов">
              <button type="button" (click)="goToPage(data.page.pageNumber - 1)" [disabled]="loading() || data.page.first">Назад</button>
              <span>{{ data.page.totalElements ? data.page.pageNumber + 1 : 0 }} / {{ data.page.totalPages }}</span>
              <button type="button" (click)="goToPage(data.page.pageNumber + 1)" [disabled]="loading() || data.page.last">Далее</button>
            </nav>
            <div class="export-row">
              <small>Обновлено {{ generatedAt(data.generatedAt) }}</small>
              <button type="button" (click)="exportAll()" [disabled]="exporting()">{{ exporting() ? 'Выгружаю…' : 'Выгрузить все в Excel' }}</button>
            </div>
          }
        </main>
      </ion-content>
    </div>
  `,
  styles: [`
    :host { display: contents; }
    ion-content { --background: var(--otziv-background); }
    .city-page { display:grid; gap:.8rem; max-width:44rem; margin:0 auto; padding:1rem 1rem calc(1.5rem + env(safe-area-inset-bottom)); color:var(--otziv-dark); }
    .page-head { display:flex; align-items:center; justify-content:space-between; gap:.5rem; }
    .page-head h1 { margin:.15rem 0 0; font-size:1.2rem; }
    .page-head small, .city-card small, .totals small, .filters span, .search span, .export-row small { color:var(--otziv-info); font-size:.68rem; font-weight:800; }
    .page-head button, .filters button { min-width:2.75rem; min-height:2.75rem; border:1px solid rgba(103,116,131,.2); border-radius:.75rem; background:var(--otziv-white); color:var(--otziv-dark); }
    .totals { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:.5rem; }
    .totals article, .city-card, .empty { border:1px solid rgba(103,116,131,.17); border-radius:.9rem; padding:.75rem; background:var(--otziv-white); }
    .totals article { display:grid; gap:.2rem; }
    .totals strong { font-size:1.1rem; }
    .search, .filters, .pager, .export-row { display:flex; gap:.5rem; align-items:end; }
    .search label, .filters label { display:grid; gap:.22rem; flex:1; min-width:0; }
    input, select { width:100%; min-width:0; min-height:2.75rem; border:1px solid rgba(103,116,131,.2); border-radius:.75rem; padding:.5rem; background:var(--otziv-white); color:var(--otziv-dark); font:inherit; }
    button { min-height:2.75rem; border:1px solid rgba(103,116,131,.2); border-radius:.75rem; padding:.45rem .75rem; background:var(--otziv-white); color:var(--otziv-dark); font:inherit; font-weight:800; }
    .search button, .export-row button { color:#fff; border-color:var(--otziv-primary); background:var(--otziv-primary); }
    button:disabled { opacity:.55; }
    .filters label:first-child { flex:2; }
    .city-list { display:grid; gap:.5rem; }
    .city-card header { display:flex; justify-content:space-between; gap:.5rem; align-items:start; }
    .city-card h2 { margin:0; font-size:.95rem; }
    .city-card p { margin:.45rem 0 0; color:var(--otziv-info); font-size:.72rem; }
    .city-metrics { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:.25rem; margin-top:.6rem; }
    .city-metrics span { display:grid; gap:.25rem; min-width:0; border-radius:.55rem; padding:.4rem .3rem; background:var(--otziv-muted-surface); }
    .city-metrics small { font-size:.58rem; line-height:1.1; }.city-metrics strong { font-size:.8rem; }
    .negative { color:var(--otziv-danger); }
    .pager { justify-content:space-between; align-items:center; }.pager span { font-size:.8rem; font-weight:800; }
    .export-row { justify-content:space-between; align-items:center; }.export-row small { min-width:0; }
    .alert, .notice { margin:0; padding:.65rem; border-radius:.7rem; font-size:.76rem; line-height:1.35; }
    .alert { color:var(--otziv-danger); background:rgba(239,52,95,.12); }.notice { color:#267d68; background:rgba(68,158,133,.12); }
    .alert button { min-height:2rem; margin-left:.3rem; }
  `]
})
export class AdminCityStatsPage implements OnInit, OnDestroy {
  private readonly api = inject(AdminCityStatsApi);
  private readonly confirm = inject(MobileConfirmService);
  readonly sortOptions = SORTS;
  readonly board = signal<CityStatsBoard | null>(null);
  readonly searchDraft = signal('');
  readonly search = signal('');
  readonly sort = signal<CityStatsSort | null>(null);
  readonly direction = signal<SortDirection>('desc');
  readonly page = signal(0);
  readonly size = signal(20);
  readonly loading = signal(false);
  readonly exporting = signal(false);
  readonly error = signal<string | null>(null);
  readonly notice = signal<string | null>(null);
  private requestEpoch = 0;
  private destroyed = false;

  ngOnInit(): void { void this.load(); }
  ngOnDestroy(): void { this.destroyed = true; this.requestEpoch += 1; }

  async refresh(event: RefresherCustomEvent): Promise<void> { await this.load(); event.target.complete(); }

  async load(): Promise<void> {
    const epoch = ++this.requestEpoch;
    this.loading.set(true);
    this.error.set(null);
    try {
      const board = await firstValueFrom(this.api.getBoard(this.query()));
      if (this.destroyed || epoch !== this.requestEpoch) return;
      this.board.set(board);
      this.direction.set(board.direction);
    } catch (error) {
      if (!this.destroyed && epoch === this.requestEpoch) this.error.set(this.errorMessage(error, 'Не удалось загрузить статистику городов.'));
    } finally {
      if (epoch === this.requestEpoch) this.loading.set(false);
    }
  }

  applySearch(): void { this.search.set(this.searchDraft().trim()); this.page.set(0); void this.load(); }
  setSort(value: CityStatsSort | null): void {
    this.sort.set(value);
    this.direction.set(SORTS.find((item) => item.value === value)?.direction ?? 'desc');
    this.page.set(0);
    void this.load();
  }
  toggleDirection(): void { if (this.sort()) { this.direction.update((value) => value === 'asc' ? 'desc' : 'asc'); this.page.set(0); void this.load(); } }
  setSize(value: number): void { this.size.set([20, 50, 100].includes(Number(value)) ? Number(value) : 20); this.page.set(0); void this.load(); }
  goToPage(page: number): void {
    const current = this.board()?.page;
    if (!current || page < 0 || page >= current.totalPages || page === current.pageNumber) return;
    this.page.set(page);
    void this.load();
  }
  generatedAt(value: string): string { return new Date(value).toLocaleString('ru-RU'); }

  async exportAll(): Promise<void> {
    if (this.exporting()) return;
    if (!await this.confirm.confirm({
      title: 'Выгрузить все города',
      message: 'Выгрузка всех данных может занять некоторое время. Продолжить?',
      confirmText: 'Выгрузить'
    })) return;
    this.exporting.set(true);
    this.error.set(null);
    this.notice.set(null);
    try {
      const blob = await firstValueFrom(this.api.exportAll(this.query()));
      const stamp = new Date().toISOString().slice(0, 10);
      const fileName = `cities_full_export_${stamp}.xlsx`;
      if (Capacitor.isNativePlatform()) {
        const data = await this.blobBase64(blob);
        await Filesystem.writeFile({ path: fileName, data, directory: Directory.Documents });
        this.notice.set(`Excel сохранён в документах приложения: ${fileName}`);
      } else {
        const url = URL.createObjectURL(blob);
        const anchor = document.createElement('a');
        anchor.href = url;
        anchor.download = fileName;
        anchor.click();
        window.setTimeout(() => URL.revokeObjectURL(url), 30_000);
        this.notice.set(`Файл ${fileName} подготовлен для скачивания.`);
      }
    } catch (error) {
      this.error.set(this.errorMessage(error, 'Не удалось выгрузить Excel.'));
    } finally {
      this.exporting.set(false);
    }
  }

  private query(): CityStatsQuery {
    return { search: this.search(), sort: this.sort(), direction: this.direction(), page: this.page(), size: this.size() };
  }

  private blobBase64(blob: Blob): Promise<string> {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onerror = () => reject(new Error('Не удалось прочитать файл экспорта.'));
      reader.onload = () => resolve(String(reader.result).split(',', 2)[1] ?? '');
      reader.readAsDataURL(blob);
    });
  }

  private errorMessage(error: unknown, fallback: string): string {
    if (error && typeof error === 'object' && 'error' in error) {
      const body = (error as { error?: { detail?: string; message?: string } | string }).error;
      if (typeof body === 'string' && body.trim()) return body;
      if (body && typeof body === 'object') return body.detail || body.message || fallback;
    }
    return fallback;
  }
}
