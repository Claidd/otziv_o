import { AdminTaxonomyApi } from '../../../core/admin-taxonomy.api';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { of } from 'rxjs';

import { AuthService } from '../../../core/auth.service';
import { ReputationAiApi } from '../../../core/reputation-ai.api';
import { ToastService } from '../../../shared/toast.service';
import { AdminDictionariesComponent } from './admin-dictionaries.component';

describe('city feature component wiring', () => {
  function create() {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(),
      { provide: ActivatedRoute, useValue: { snapshot: { data: {}, queryParamMap: convertToParamMap({ tab: 'cities' }) } } },
      { provide: AdminTaxonomyApi, useValue: { getCategories: () => of([]) } },
      { provide: AuthService, useValue: { tokenParsed: () => null, hasAnyRealmRole: () => true, hasRealmRole: () => true } },
      { provide: ReputationAiApi, useValue: {} },
      { provide: ToastService, useValue: { success: vi.fn(), error: vi.fn() } }
    ] });
    TestBed.overrideComponent(AdminDictionariesComponent, { set: { template: '', imports: [] } });
    return TestBed.createComponent(AdminDictionariesComponent);
  }

  it('direct city entry and navigation use the narrow read lifetime', () => {
    const fixture = create();
    const page = fixture.componentInstance;
    const http = TestBed.inject(HttpTestingController);
    const read = http.expectOne(request => request.url.endsWith('/cities'));
    expect(page.activeLoading()).toBe(true);
    page.setTab('categories');
    expect(read.cancelled).toBe(true);
    expect(page.activeLoading()).toBe(false);
    page.setTab('cities');
    http.expectOne(request => request.url.endsWith('/cities')).flush([]);
    expect(page.cities()).toEqual([]);
    fixture.destroy();
    http.verify();
  });

  it('binds city errors to the active form and ignores a save after tab navigation', () => {
    const fixture = create();
    const page = fixture.componentInstance;
    const http = TestBed.inject(HttpTestingController);
    http.expectOne(request => request.url.endsWith('/cities')).flush([]);
    page.cityForm.patchValue({ title: 'Город', latitude: '91' });
    page.saveActive();
    expect(page.activeError()).toContain('широта');
    page.cityForm.controls.latitude.setValue('52');
    page.saveActive();
    const write = http.expectOne(request => request.method === 'POST' && request.url.endsWith('/cities'));
    expect(page.activeSaving()).toBe(true);
    page.setTab('categories');
    expect(write.cancelled).toBe(false);
    page.selectedId.set(333);
    write.flush({ id: 18, title: 'Город', latitude: 52, longitude: null });
    expect(page.selectedId()).toBe(333);
    http.expectNone(() => true);
    fixture.destroy();
    http.verify();
  });
});
