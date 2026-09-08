import { WritableSignal, signal } from '@angular/core';
import { FormBuilder, Validators } from '@angular/forms';
import { Observable } from 'rxjs';
import type { AdminCitiesApi } from '../../../core/admin-cities.api';
import type { AdminCity, CityDistanceRebuildResponse, CityRequest } from '../../../core/admin-dictionaries.api';
import { apiErrorMessage } from '../../../shared/api-error-message';
import type { ToastService } from '../../../shared/toast.service';
import { DictionaryViewScope } from './dictionary-view-scope';

type CityDeps = {
  api: AdminCitiesApi;
  toast: Pick<ToastService, 'success' | 'error'>;
  isActive: () => boolean;
  selectedId: WritableSignal<number | null>;
  search: () => string;
};

/** Per-screen city drafts and commands. Submitted maintenance jobs survive navigation. */
export class AdminCitiesFacade {
  private readonly scope: DictionaryViewScope;
  private editorGeneration = 0;
  readonly cities = signal<AdminCity[]>([]);
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly deleting = signal(false);
  readonly rebuildingCityDistances = signal(false);
  readonly error = signal<string | null>(null);
  readonly cityForm = new FormBuilder().nonNullable.group({
    title: ['', Validators.required], latitude: [''], longitude: ['']
  });

  constructor(private readonly deps: CityDeps) {
    this.scope = new DictionaryViewScope(deps.isActive);
  }

  deactivate(): void { this.editorGeneration++; this.scope.deactivate(); }
  destroy(): void { this.editorGeneration++; this.scope.destroy(); }

  load(): void {
    if (!this.scope.active() || this.busy()) return;
    this.error.set(null);
    this.scope.read('cities', this.deps.api.getCities(this.deps.search()), this.loading).subscribe({
      next: cities => this.cities.set(cities),
      error: err => this.fail(err, 'Справочник не загрузился', 'Не удалось загрузить города')
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
    if (this.cityForm.invalid) { this.cityForm.markAllAsTouched(); return; }
    const raw = this.cityForm.getRawValue();
    const request: CityRequest = { title: raw.title.trim(),
      latitude: this.coordinateValue(raw.latitude), longitude: this.coordinateValue(raw.longitude) };
    if (!this.validCoordinate(request.latitude, -90, 90) || !this.validCoordinate(request.longitude, -180, 180)) {
      this.error.set('Проверьте координаты: широта от -90 до 90, долгота от -180 до 180.');
      return;
    }
    const id = this.deps.selectedId();
    const generation = this.editorGeneration;
    this.saving.set(true);
    this.error.set(null);
    const call = id == null ? this.deps.api.createCity(request) : this.deps.api.updateCity(id, request);
    this.scope.write(call, this.saving).subscribe({
      next: saved => {
        this.saving.set(false);
        if (!this.sameEditor(generation, id)) return;
        this.deps.selectedId.set(saved.id);
        this.deps.toast.success('Город сохранен', `ID ${saved.id}`);
        this.load();
      },
      error: err => { if (this.sameEditor(generation, id)) this.fail(err, 'Запись не сохранена', 'Не удалось сохранить город'); }
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
    this.scope.write(this.deps.api.deleteCity(id), this.deleting).subscribe({
      next: () => {
        this.deleting.set(false);
        if (!this.sameEditor(generation, id)) return;
        this.deps.selectedId.set(null);
        this.clearSelection();
        this.deps.toast.success('Запись удалена', 'Города');
        this.load();
      },
      error: err => { if (this.sameEditor(generation, id)) this.fail(err, 'Запись не удалена', 'Не удалось удалить город'); }
    });
  }

  rebuildCityDistances(): void {
    if (!this.scope.active() || this.busy()) return;
    this.runRebuild(this.deps.api.rebuildCityDistances(150), 'Матрица расстояний пересчитана',
      'Матрица не пересчитана', 'Не удалось пересчитать расстояния');
  }

  rebuildSelectedCityDistances(): void {
    const id = this.deps.selectedId();
    if (id == null || !this.scope.active() || this.busy()) return;
    this.runRebuild(this.deps.api.rebuildCityDistancesForCity(id), 'Город пересчитан',
      'Город не пересчитан', 'Не удалось пересчитать расстояния города');
  }

  importCityCoordinates(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file || !this.scope.active() || this.busy()) return;
    this.rebuildingCityDistances.set(true);
    this.error.set(null);
    this.scope.write(this.deps.api.importCityCoordinates(file), this.rebuildingCityDistances).subscribe({
      next: result => {
        this.rebuildingCityDistances.set(false);
        const errorTail = result.errors.length ? `, ошибок: ${result.errors.length}` : '';
        this.deps.toast.success('Координаты загружены',
          `обновлено: ${result.updated}, пропущено: ${result.skipped}${errorTail}, связей: ${result.distancesSaved}`);
        this.load();
      },
      error: err => this.fail(err, 'Координаты не загружены', 'Не удалось импортировать координаты')
    });
    input.value = '';
  }

  private runRebuild(call: Observable<CityDistanceRebuildResponse>, title: string, errorTitle: string, fallback: string): void {
    this.rebuildingCityDistances.set(true);
    this.error.set(null);
    this.scope.write(call, this.rebuildingCityDistances).subscribe({
      next: result => {
        this.rebuildingCityDistances.set(false);
        this.deps.toast.success(title,
          `городов: ${result.citiesWithCoordinates}, без координат: ${result.citiesWithoutCoordinates}, связей: ${result.distancesSaved}`);
        this.load();
      },
      error: err => this.fail(err, errorTitle, fallback)
    });
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
  private coordinateValue(value: string): number | null {
    const normalized = value.trim().replace(',', '.');
    return normalized ? Number(normalized) : null;
  }
  private validCoordinate(value: number | null | undefined, min: number, max: number): boolean {
    return value == null || (Number.isFinite(value) && value >= min && value <= max);
  }
}
