import { DatePipe } from '@angular/common';
import { Component, OnDestroy, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  AdminCityStatsApi,
  CityStatsBoard,
  CityStatsItem,
  CityStatsSort,
  SortDirection
} from '../../../core/admin-city-stats.api';
import { appEnvironment } from '../../../core/app-environment';
import { AdminLayoutComponent } from '../../../shared/admin-layout.component';
import { apiErrorMessage } from '../../../shared/api-error-message';
import { LoadErrorCardComponent } from '../../../shared/load-error-card.component';
import { ToastService } from '../../../shared/toast.service';

type SortOption = {
  key: CityStatsSort;
  label: string;
  defaultDirection: SortDirection;
};

type StatCard = {
  label: string;
  value: number;
  tone: 'default' | 'archive' | 'bots' | 'balance';
  detail?: string;
};

@Component({
  selector: 'app-admin-cities',
  imports: [AdminLayoutComponent, DatePipe, FormsModule, LoadErrorCardComponent],
  templateUrl: './admin-cities.component.html',
  styleUrl: './admin-cities.component.scss'
})
export class AdminCitiesComponent implements OnDestroy {
  private readonly cityStatsApi = inject(AdminCityStatsApi);
  private readonly toastService = inject(ToastService);
  private readonly refreshTimer = window.setInterval(() => this.loadBoard(false), 900000);

  readonly sortOptions: SortOption[] = [
    { key: 'name', label: 'По названию', defaultDirection: 'asc' },
    { key: 'countAll', label: 'По количеству (все)', defaultDirection: 'desc' },
    { key: 'countArchive', label: 'По количеству (без архивных)', defaultDirection: 'desc' },
    { key: 'bots', label: 'По ботам', defaultDirection: 'desc' },
    { key: 'balance', label: 'По балансу', defaultDirection: 'desc' }
  ];
  readonly pageSizeOptions = [20, 50, 100];

  readonly board = signal<CityStatsBoard | null>(null);
  readonly search = signal('');
  readonly sort = signal<CityStatsSort | null>(null);
  readonly direction = signal<SortDirection>('desc');
  readonly page = signal(0);
  readonly size = signal(20);
  readonly loading = signal(false);
  readonly exporting = signal(false);
  readonly error = signal<string | null>(null);

  readonly cities = computed(() => this.board()?.cities ?? []);
  readonly stats = computed(() => this.board()?.statistics);
  readonly currentPage = computed(() => this.board()?.page ?? {
    pageNumber: 0,
    pageSize: this.size(),
    totalElements: 0,
    totalPages: 0,
    first: true,
    last: true
  });
  readonly cards = computed<StatCard[]>(() => {
    const stats = this.stats();
    return [
      { label: 'Всего городов', value: stats?.totalCities ?? 0, tone: 'default' },
      { label: 'Среднее без архивных', value: stats?.averageNotArchivePerCity ?? 0, tone: 'archive' },
      {
        label: 'Неопубликованных (без архивных)',
        value: stats?.totalUnpublishedNotArchive ?? 0,
        tone: 'archive',
        detail: stats && stats.archivedCount > 0 ? `-${stats.archivedCount} архивных` : undefined
      },
      { label: 'Доступных аккаунтов', value: stats?.totalActiveBots ?? 0, tone: 'bots' },
      {
        label: 'Общий баланс (+/-)',
        value: stats?.totalBotBalance ?? 0,
        tone: 'balance',
        detail: stats && stats.totalBotBalance !== 0
          ? (stats.totalBotBalance > 0 ? 'Профицит' : 'Дефицит')
          : undefined
      }
    ];
  });
  readonly pageNumbers = computed(() => Array.from(
    { length: this.currentPage().totalPages },
    (_, index) => index
  ));
  readonly activeSortLabel = computed(() => {
    const sort = this.sort();
    if (!sort) {
      return '';
    }

    return this.sortOptions.find((option) => option.key === sort)?.label ?? '';
  });

  constructor() {
    this.loadBoard();
  }

  ngOnDestroy(): void {
    window.clearInterval(this.refreshTimer);
  }

  loadBoard(showSpinner = true): void {
    if (showSpinner) {
      this.loading.set(true);
    }
    this.error.set(null);

    this.cityStatsApi.getBoard({
      search: this.search(),
      sort: this.sort(),
      direction: this.direction(),
      page: this.page(),
      size: this.size()
    }).subscribe({
      next: (board) => {
        this.board.set(board);
        this.direction.set(board.direction);
        this.loading.set(false);
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось загрузить статистику городов');
        this.error.set(message);
        this.loading.set(false);
        this.toastService.error('Города не загрузились', message);
      }
    });
  }

  searchCities(): void {
    this.page.set(0);
    this.loadBoard();
  }

  clearSearch(): void {
    this.search.set('');
    this.page.set(0);
    this.loadBoard();
  }

  toggleSort(option: SortOption): void {
    if (this.sort() === option.key) {
      this.direction.update((direction) => direction === 'asc' ? 'desc' : 'asc');
    } else {
      this.sort.set(option.key);
      this.direction.set(option.defaultDirection);
    }

    this.page.set(0);
    this.loadBoard();
  }

  resetSort(): void {
    this.sort.set(null);
    this.direction.set('desc');
    this.page.set(0);
    this.loadBoard();
  }

  changePageSize(value: string | number): void {
    this.size.set(Number(value));
    this.page.set(0);
    this.loadBoard();
  }

  goToPage(page: number): void {
    const current = this.currentPage();
    if (page < 0 || page >= current.totalPages || page === current.pageNumber) {
      return;
    }

    this.page.set(page);
    this.loadBoard();
  }

  exportAll(): void {
    if (this.exporting()) {
      return;
    }

    const confirmed = window.confirm('Выгрузка ВСЕХ данных может занять некоторое время. Продолжить?');
    if (!confirmed) {
      return;
    }

    this.exporting.set(true);
    this.cityStatsApi.exportAll({
      search: this.search(),
      sort: this.sort(),
      direction: this.direction(),
      page: 0,
      size: this.size()
    }).subscribe({
      next: (blob) => {
        this.downloadBlob(blob);
        this.exporting.set(false);
        this.toastService.success('Экспорт готов', 'Файл статистики городов скачан');
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось выгрузить Excel');
        this.exporting.set(false);
        this.toastService.error('Экспорт не выполнен', message);
      }
    });
  }

  reviewUrl(city: CityStatsItem): string {
    return this.legacyUrl(`/admin/reviews?cityId=${city.cityId}&published=false`);
  }

  botsUrl(city: CityStatsItem): string {
    return this.legacyUrl(`/admin/bots?cityId=${city.cityId}&active=true`);
  }

  detailsUrl(city: CityStatsItem): string {
    return this.legacyUrl(`/admin/cities/${city.cityId}`);
  }

  countClass(city: CityStatsItem): string {
    return `count-badge count-${city.countTone}`;
  }

  balanceClass(city: CityStatsItem): string {
    return `circle-indicator circle-${city.balanceTone}`;
  }

  sortIcon(option: SortOption): string {
    if (this.sort() !== option.key) {
      return 'unfold_more';
    }

    return this.direction() === 'asc' ? 'arrow_upward' : 'arrow_downward';
  }

  trackSort(_index: number, option: SortOption): CityStatsSort {
    return option.key;
  }

  trackCity(_index: number, city: CityStatsItem): number {
    return city.cityId;
  }

  trackCard(_index: number, card: StatCard): string {
    return card.label;
  }

  trackPage(_index: number, page: number): number {
    return page;
  }

  private downloadBlob(blob: Blob): void {
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    const now = new Date();
    const stamp = [
      now.getFullYear(),
      String(now.getMonth() + 1).padStart(2, '0'),
      String(now.getDate()).padStart(2, '0')
    ].join('-');

    link.href = url;
    link.download = `cities_full_export_${stamp}.xlsx`;
    link.click();
    URL.revokeObjectURL(url);
  }

  private legacyUrl(path: string): string {
    return `${appEnvironment.legacyBaseUrl}${path}`;
  }

  private errorMessage(err: unknown, fallback: string): string {
    return apiErrorMessage(err, fallback);
  }
}
