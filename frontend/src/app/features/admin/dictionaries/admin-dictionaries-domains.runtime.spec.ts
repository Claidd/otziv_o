import { AdminTaxonomyApi } from '../../../core/admin-taxonomy.api';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { of } from 'rxjs';
import { AdminBot } from '../../../core/admin-dictionaries.api';
import { AuthService } from '../../../core/auth.service';
import { ReputationAiApi } from '../../../core/reputation-ai.api';
import { ToastService } from '../../../shared/toast.service';
import { AdminDictionariesComponent } from './admin-dictionaries.component';

describe('dictionary feature navigation', () => {
  function create(tab: string, admin = true) {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(),
      { provide: ActivatedRoute, useValue: { snapshot: { data: {}, queryParamMap: convertToParamMap({ tab }) } } },
      { provide: AdminTaxonomyApi, useValue: { getCategories: () => of([]) } },
      { provide: AuthService, useValue: { tokenParsed: () => null, hasAnyRealmRole: () => admin, hasRealmRole: () => admin } },
      { provide: ReputationAiApi, useValue: {} },
      { provide: ToastService, useValue: { success: vi.fn(), error: vi.fn() } }
    ] });
    TestBed.overrideComponent(AdminDictionariesComponent, { set: { template: '', imports: [] } });
    return TestBed.createComponent(AdminDictionariesComponent);
  }

  it('direct account entry uses narrow requests and cancels every old view read on navigation', () => {
    const fixture = create('accounts');
    const page = fixture.componentInstance;
    const http = TestBed.inject(HttpTestingController);
    const accounts = http.match(() => true);
    expect(accounts).toHaveLength(2);
    expect(page.activeLoading()).toBe(true);
    page.selectBot({ id: 18 } as AdminBot);
    const detail = http.expectOne(request => request.url.endsWith('/bots/18'));
    page.setTab('phones');
    expect(accounts.every(request => request.cancelled)).toBe(true);
    expect(detail.cancelled).toBe(true);
    const phones = http.expectOne(request => request.url.endsWith('/phones'));
    phones.flush({ phones: [], operators: [] });
    expect(page.activeLoading()).toBe(false);
    expect(page.phoneForm.controls.number.value).toBe('+7');
    expect(page.selectedId()).toBeNull();
    fixture.destroy();
    http.verify();
  });

  it('hiding cancels pending reads; returning to a loaded dirty form preserves user edits', () => {
    let visibility: DocumentVisibilityState = 'visible';
    const spy = vi.spyOn(document, 'visibilityState', 'get').mockImplementation(() => visibility);
    const fixture = create('phones');
    const page = fixture.componentInstance;
    const http = TestBed.inject(HttpTestingController);
    const pending = http.expectOne(request => request.url.endsWith('/phones'));
    visibility = 'hidden';
    page.onDocumentVisibilityChange();
    expect(pending.cancelled).toBe(true);
    expect(page.activeLoading()).toBe(false);
    visibility = 'visible';
    page.onDocumentVisibilityChange();
    http.expectOne(request => request.url.endsWith('/phones')).flush({ phones: [], operators: [] });
    page.phoneForm.controls.fio.setValue('Unsaved operator name');
    page.phoneForm.markAsDirty();
    visibility = 'hidden'; page.onDocumentVisibilityChange();
    visibility = 'visible'; page.onDocumentVisibilityChange();
    expect(page.phoneForm.controls.fio.value).toBe('Unsaved operator name');
    http.expectNone(() => true);
    fixture.destroy();
    http.verify();
    spy.mockRestore();
  });

  it('manager permissions cannot start account/settings loads through a direct tab route', () => {
    const fixture = create('accounts', false);
    const page = fixture.componentInstance;
    const http = TestBed.inject(HttpTestingController);
    expect(page.activeTab()).toBe('categories');
    page.setTab('settings');
    expect(page.activeTab()).toBe('categories');
    http.expectNone(() => true);
    fixture.destroy();
  });
});
