import { WritableSignal, signal } from '@angular/core';
import { FormBuilder, FormControl, Validators } from '@angular/forms';
import { Observable } from 'rxjs';
import type { AdminCitiesApi } from '../../../core/admin-cities.api';
import type {
  AdminCity,
  CityCoordinateImportResponse,
  CityDistanceRebuildResponse,
  CityRequest
} from '../../../core/admin-dictionaries.api';
import { apiErrorMessage } from '../../../shared/api-error-message';
import type { ToastService } from '../../../shared/toast.service';
import { DictionaryViewScope } from './dictionary-view-scope';

/**
 * A coordinate field holds text put by selectCity, but once typed into <input type="number"> Angular's number
 * accessor stores a number there, and null when the field is cleared.
 */
type CoordinateValue = string | number | null;

type CityDeps = {
  api: AdminCitiesApi;
  /** warning is optional: without it a problem is reported as an error toast. */
  toast: Pick<ToastService, 'success' | 'error'> & Partial<Pick<ToastService, 'warning'>>;
  isActive: () => boolean;
  selectedId: WritableSignal<number | null>;
  search: () => string;
};

/** The bulk rebuild (and the rebuild after a coordinate import) computes pairs of cities with an ID from this one. */
const BULK_REBUILD_MIN_CITY_ID = 150;
const COORDINATE_RANGE_MESSAGE = 'Проверьте координаты: широта от -90 до 90, долгота от -180 до 180.';
/** Errors of an imported file quoted in the notice; the rest is only counted. */
const IMPORT_ERRORS_SHOWN = 3;

/** Per-screen city drafts and commands. Submitted maintenance jobs survive navigation. */
export class AdminCitiesFacade {
  private readonly scope: DictionaryViewScope;
  private editorGeneration = 0;
  /** Set by a coordinate import: the next list tells which cities below ID 150 still wait for a rebuild. */
  private reportUnrebuiltAfterLoad = false;
  readonly cities = signal<AdminCity[]>([]);
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly deleting = signal(false);
  readonly rebuildingCityDistances = signal(false);
  readonly error = signal<string | null>(null);
  readonly cityForm = new FormBuilder().nonNullable.group({
    title: ['', Validators.required],
    latitude: new FormControl<CoordinateValue>(''),
    longitude: new FormControl<CoordinateValue>('')
  });

  constructor(private readonly deps: CityDeps) {
    // A save that ends after the tab was left and opened again reads the list again (see DictionaryViewScope).
    this.scope = new DictionaryViewScope(deps.isActive, () => this.load());
  }

  deactivate(): void { this.editorGeneration++; this.reportUnrebuiltAfterLoad = false; this.scope.deactivate(); }
  destroy(): void { this.editorGeneration++; this.scope.destroy(); }

  load(): void {
    if (!this.scope.active() || this.busy()) return;
    this.error.set(null);
    this.scope.read('cities', this.deps.api.getCities(this.deps.search()), this.loading).subscribe({
      next: cities => {
        this.cities.set(cities);
        if (this.reportUnrebuiltAfterLoad) {
          this.reportUnrebuiltAfterLoad = false;
          this.reportUnrebuiltCities(cities);
        }
      },
      error: err => {
        this.reportUnrebuiltAfterLoad = false;
        this.fail(err, 'Справочник не загрузился', 'Не удалось загрузить города');
      }
    });
  }

  selectCity(city: AdminCity): void {
    this.editorGeneration++;
    this.deps.selectedId.set(city.id);
    this.cityForm.setValue({ title: city.title,
      latitude: city.latitude == null ? '' : String(city.latitude),
      longitude: city.longitude == null ? '' : String(city.longitude) });
    this.error.set(null);
  }

  clearSelection(): void {
    this.editorGeneration++;
    this.cityForm.reset({ title: '', latitude: '', longitude: '' });
    this.error.set(null);
  }

  saveCity(): void {
    if (!this.scope.active() || this.busy()) return;
    // Only the title has a validator of its own. The min/max attributes of the coordinate inputs make the form
    // invalid too: those values are checked below with a message that names the coordinates.
    if (this.cityForm.controls.title.invalid) { this.cityForm.markAllAsTouched(); return; }
    const raw = this.cityForm.getRawValue();
    const request: CityRequest = { title: raw.title.trim(),
      latitude: this.coordinateValue(raw.latitude), longitude: this.coordinateValue(raw.longitude) };
    if (!this.validCoordinate(request.latitude, -90, 90) || !this.validCoordinate(request.longitude, -180, 180)) {
      this.cityForm.markAllAsTouched();
      this.error.set(COORDINATE_RANGE_MESSAGE);
      // The page error card is above the list: on a phone only the toast is seen next to the form.
      this.deps.toast.error('Город не сохранен', COORDINATE_RANGE_MESSAGE);
      return;
    }
    const id = this.deps.selectedId();
    const generation = this.editorGeneration;
    this.saving.set(true);
    this.error.set(null);
    const call = id == null ? this.deps.api.createCity(request) : this.deps.api.updateCity(id, request);
    const named = `«${request.title}»`;
    this.scope.write(call, this.saving, outcome => outcome.failed
      ? this.deps.toast.error('Город не сохранен', `${named}: ${apiErrorMessage(outcome.error, 'Не удалось сохранить город')}`)
      : this.deps.toast.success('Город сохранен', `${named}, ID ${outcome.value?.id ?? id ?? '—'}`)
    ).subscribe({
      next: saved => {
        this.saving.set(false);
        // Another city opened meanwhile keeps its form, but the list is read again: otherwise the saved row would
        // still show the old coordinates, and opening it would put them back into the form.
        if (this.sameEditor(generation, id)) this.deps.selectedId.set(saved.id);
        this.deps.toast.success('Город сохранен', `${named}, ID ${saved.id}`);
        this.load();
      },
      error: err => {
        const message = apiErrorMessage(err, 'Не удалось сохранить город');
        if (this.sameEditor(generation, id)) this.error.set(message);
        this.deps.toast.error('Город не сохранен', `${named}: ${message}`);
      }
    });
  }

  deleteSelected(): void {
    const id = this.deps.selectedId();
    if (id == null || !this.scope.active() || this.busy()) return;
    const generation = this.editorGeneration;
    this.deleting.set(true);
    const confirmed = window.confirm('Удалить запись из справочника "Города"?');
    if (!confirmed || !this.scope.active() || !this.sameEditor(generation, id)) {
      this.deleting.set(false);
      return;
    }
    this.error.set(null);
    this.scope.write(this.deps.api.deleteCity(id), this.deleting, outcome => outcome.failed
      ? this.deps.toast.error('Запись не удалена', apiErrorMessage(outcome.error, 'Не удалось удалить город'))
      : this.deps.toast.success('Запись удалена', 'Города')
    ).subscribe({
      next: () => {
        this.deleting.set(false);
        if (this.sameEditor(generation, id)) {
          this.deps.selectedId.set(null);
          this.clearSelection();
        }
        this.deps.toast.success('Запись удалена', 'Города');
        this.load();
      },
      error: err => {
        const message = apiErrorMessage(err, 'Не удалось удалить город');
        if (this.sameEditor(generation, id)) this.error.set(message);
        this.deps.toast.error('Запись не удалена', message);
      }
    });
  }

  rebuildCityDistances(): void {
    if (!this.scope.active() || this.busy()) return;
    this.runRebuild(this.deps.api.rebuildCityDistances(BULK_REBUILD_MIN_CITY_ID), 'Матрица расстояний пересчитана',
      'Матрица не пересчитана', 'Не удалось пересчитать расстояния',
      `Нет городов с ID от ${BULK_REBUILD_MIN_CITY_ID} с координатами.`);
  }

  rebuildSelectedCityDistances(): void {
    const id = this.deps.selectedId();
    if (id == null || !this.scope.active() || this.busy()) return;
    // The server rebuilds from the saved coordinates, not from the form; saving rebuilds the city by itself.
    if (this.cityForm.controls.latitude.dirty || this.cityForm.controls.longitude.dirty) {
      this.warn('Сначала сохраните город',
        'Пересчёт берёт сохранённые координаты. «Сохранить» запишет новые и сам пересчитает расстояния.');
      return;
    }
    this.runRebuild(this.deps.api.rebuildCityDistancesForCity(id), 'Город пересчитан',
      'Город не пересчитан', 'Не удалось пересчитать расстояния города',
      'У города нет сохранённых координат. Укажите широту и долготу и нажмите «Сохранить».');
  }

  importCityCoordinates(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    // Cleared at once: choosing the same file again must fire a change even when this attempt is refused.
    input.value = '';
    if (!file || !this.scope.active() || this.busy()) return;
    this.rebuildingCityDistances.set(true);
    this.error.set(null);
    this.scope.write(this.deps.api.importCityCoordinates(file), this.rebuildingCityDistances).subscribe({
      next: result => {
        this.rebuildingCityDistances.set(false);
        this.reportCoordinateImport(result);
        this.reportUnrebuiltAfterLoad = true;
        this.load();
      },
      error: err => this.fail(err, 'Координаты не загружены', 'Не удалось импортировать координаты')
    });
  }

  private runRebuild(call: Observable<CityDistanceRebuildResponse>, title: string, errorTitle: string, fallback: string,
    nothingToRebuild: string): void {
    this.rebuildingCityDistances.set(true);
    this.error.set(null);
    this.scope.write(call, this.rebuildingCityDistances).subscribe({
      next: result => {
        this.rebuildingCityDistances.set(false);
        if (result.citiesWithCoordinates > 0) {
          this.deps.toast.success(title,
            `городов: ${result.citiesWithCoordinates}, без координат: ${result.citiesWithoutCoordinates}, связей: ${result.distancesSaved}`);
        } else {
          this.warn(errorTitle, nothingToRebuild);
        }
        this.load();
      },
      error: err => this.fail(err, errorTitle, fallback)
    });
  }

  /**
   * The counts of a coordinate import, plus a notice that stays open when rows were skipped: the server explains each
   * skipped row («Строка 5: город #999 не найден»), and a file that updated nothing is not a success.
   */
  private reportCoordinateImport(result: CityCoordinateImportResponse): void {
    const errors = result.errors ?? [];
    if (result.updated > 0) {
      const errorTail = errors.length ? `, ошибок: ${errors.length}` : '';
      this.deps.toast.success('Координаты загружены',
        `обновлено: ${result.updated}, пропущено: ${result.skipped}${errorTail}, связей: ${result.distancesSaved}`);
    }
    if (result.updated > 0 && result.skipped === 0 && errors.length === 0) return;
    this.warn(result.updated > 0 ? 'Часть строк не загружена' : 'Координаты не загружены',
      this.skippedRowsMessage(result.skipped, errors), true);
  }

  private skippedRowsMessage(skipped: number, errors: string[]): string {
    if (skipped === 0 && errors.length === 0) {
      return 'В файле нет строк с координатами. Нужны столбцы: ID города, широта, долгота.';
    }
    const shown = errors.slice(0, IMPORT_ERRORS_SHOWN);
    // The server returns at most 20 errors, so the rest is counted from the skipped rows.
    const rest = Math.max(skipped, errors.length) - shown.length;
    const details = shown.length ? ` ${shown.join('; ')}${rest > 0 ? `; и ещё ${rest}` : ''}.` : '';
    return `Пропущено строк: ${skipped}.${details}`;
  }

  /** The import rebuilds only cities with an ID from 150: older cities from the file stay «не рассчитана». */
  private reportUnrebuiltCities(cities: AdminCity[]): void {
    const waiting = cities.filter(city => city.id < BULK_REBUILD_MIN_CITY_ID
      && city.latitude != null && city.longitude != null && !city.distanceMatrixReady).length;
    if (waiting === 0) return;
    this.warn('Нужен пересчёт городов',
      `Городов с ID меньше ${BULK_REBUILD_MIN_CITY_ID} без пересчёта расстояний: ${waiting}. `
      + 'Откройте такой город и нажмите «Пересчитать город».');
  }

  private busy(): boolean { return this.saving() || this.deleting() || this.rebuildingCityDistances(); }
  private sameEditor(generation: number, id: number | null): boolean {
    return generation === this.editorGeneration && id === this.deps.selectedId();
  }
  private fail(err: unknown, title: string, fallback: string): void {
    const message = apiErrorMessage(err, fallback);
    this.error.set(message);
    this.deps.toast.error(title, message);
  }
  /** A problem that is not a failure; keepOpen leaves the notice until it is closed, for text worth reading. */
  private warn(title: string, message: string, keepOpen = false): void {
    const toast = this.deps.toast;
    if (!toast.warning) {
      toast.error(title, message);
      return;
    }
    if (keepOpen) toast.warning(title, message, { label: 'Понятно', callback: () => undefined });
    else toast.warning(title, message);
  }
  private coordinateValue(value: CoordinateValue | undefined): number | null {
    if (value == null) return null;
    if (typeof value === 'number') return value;
    const normalized = value.trim().replace(',', '.');
    return normalized ? Number(normalized) : null;
  }
  private validCoordinate(value: number | null | undefined, min: number, max: number): boolean {
    return value == null || (Number.isFinite(value) && value >= min && value <= max);
  }
}
