import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, forkJoin, map } from 'rxjs';
import type { AdminBot, AdminCategory, AdminCity, AdminManagerText, AdminProduct, AdminPromoText, AdminSubCategory, BotCountResponse, BotImportResponse, BotRequest, BotsResponse, DictionarySummary, ManagerTextRequest, OperatorPhone, OperatorPhoneRequest, OperatorPhonesResponse, ProductRequest, ProductsResponse, PromoTextAssignment, PromoTextAssignmentRequest, PromoTextManagementResponse, PromoTextRequest, SubCategoryRequest, TitleRequest } from './api.service';
import { mobileEnvironment } from './mobile-environment';

@Injectable({ providedIn: 'root' })
export class DictionariesApi {
  private readonly http = inject(HttpClient);
  private apiUrl(path: string): string { return mobileEnvironment.apiBaseUrl + path; }
  getDictionarySummary(includeAdminTabs: boolean): Observable<DictionarySummary> {
    const categories$ = this.http.get<unknown[]>(this.apiUrl('/api/admin/categories'));

    if (!includeAdminTabs) {
      return categories$.pipe(
        map((categories) => ({
          items: [
            {
              key: 'categories',
              title: 'Категории',
              icon: 'category',
              count: categories.length,
              description: 'доступные категории компаний'
            }
          ]
        }))
      );
    }

    return forkJoin({
      categories: categories$,
      cities: this.http.get<unknown[]>(this.apiUrl('/api/admin/cities')),
      products: this.http.get<{ products?: unknown[] }>(this.apiUrl('/api/admin/products')),
      phones: this.http.get<{ phones?: unknown[] }>(this.apiUrl('/api/admin/phones')),
      accounts: this.http.get<BotCountResponse>(this.apiUrl('/api/admin/bots/count')),
      promo: this.http.get<unknown[]>(this.apiUrl('/api/admin/promo-texts')),
      managerTexts: this.http.get<unknown[]>(this.apiUrl('/api/admin/manager-texts'))
    }).pipe(
      map((response) => ({
        items: [
          { key: 'categories', title: 'Категории', icon: 'category', count: response.categories.length, description: 'типы компаний и подкатегории' },
          { key: 'cities', title: 'Города', icon: 'location_city', count: response.cities.length, description: 'города филиалов и заказов' },
          { key: 'products', title: 'Продукты', icon: 'inventory_2', count: response.products.products?.length ?? 0, description: 'услуги и цены заказов' },
          { key: 'phones', title: 'Телефоны', icon: 'phone_iphone', count: response.phones.phones?.length ?? 0, description: 'телефоны операторов' },
          { key: 'accounts', title: 'Аккаунты', icon: 'manage_accounts', count: response.accounts.count ?? 0, description: 'боты и рабочие аккаунты' },
          { key: 'promo', title: 'Промо', icon: 'smart_button', count: response.promo.length, description: 'шаблоны сообщений' },
          { key: 'managerTexts', title: 'Тексты менеджеров', icon: 'article', count: response.managerTexts.length, description: 'персональные тексты' }
        ]
      }))
    );
  }

  getAdminCategories(keyword = ''): Observable<AdminCategory[]> {
    return this.http.get<AdminCategory[]>(this.apiUrl('/api/admin/categories'), {
      params: this.keywordParams(keyword)
    });
  }

  createAdminCategory(request: TitleRequest): Observable<AdminCategory> {
    return this.http.post<AdminCategory>(this.apiUrl('/api/admin/categories'), request);
  }

  updateAdminCategory(id: number, request: TitleRequest): Observable<AdminCategory> {
    return this.http.put<AdminCategory>(this.apiUrl(`/api/admin/categories/${id}`), request);
  }

  deleteAdminCategory(id: number): Observable<void> {
    return this.http.delete<void>(this.apiUrl(`/api/admin/categories/${id}`));
  }

  getAdminSubCategories(keyword = '', categoryId?: number | null): Observable<AdminSubCategory[]> {
    let params = this.keywordParams(keyword);
    if (categoryId != null) {
      params = params.set('categoryId', String(categoryId));
    }
    return this.http.get<AdminSubCategory[]>(this.apiUrl('/api/admin/subcategories'), { params });
  }

  createAdminSubCategory(request: SubCategoryRequest): Observable<AdminSubCategory> {
    return this.http.post<AdminSubCategory>(this.apiUrl('/api/admin/subcategories'), request);
  }

  updateAdminSubCategory(id: number, request: SubCategoryRequest): Observable<AdminSubCategory> {
    return this.http.put<AdminSubCategory>(this.apiUrl(`/api/admin/subcategories/${id}`), request);
  }

  deleteAdminSubCategory(id: number): Observable<void> {
    return this.http.delete<void>(this.apiUrl(`/api/admin/subcategories/${id}`));
  }

  getAdminCities(keyword = ''): Observable<AdminCity[]> {
    return this.http.get<AdminCity[]>(this.apiUrl('/api/admin/cities'), {
      params: this.keywordParams(keyword)
    });
  }

  createAdminCity(request: TitleRequest): Observable<AdminCity> {
    return this.http.post<AdminCity>(this.apiUrl('/api/admin/cities'), request);
  }

  updateAdminCity(id: number, request: TitleRequest): Observable<AdminCity> {
    return this.http.put<AdminCity>(this.apiUrl(`/api/admin/cities/${id}`), request);
  }

  deleteAdminCity(id: number): Observable<void> {
    return this.http.delete<void>(this.apiUrl(`/api/admin/cities/${id}`));
  }

  getAdminProducts(keyword = ''): Observable<ProductsResponse> {
    return this.http.get<ProductsResponse>(this.apiUrl('/api/admin/products'), {
      params: this.keywordParams(keyword)
    });
  }

  createAdminProduct(request: ProductRequest): Observable<AdminProduct> {
    return this.http.post<AdminProduct>(this.apiUrl('/api/admin/products'), request);
  }

  updateAdminProduct(id: number, request: ProductRequest): Observable<AdminProduct> {
    return this.http.put<AdminProduct>(this.apiUrl(`/api/admin/products/${id}`), request);
  }

  deleteAdminProduct(id: number): Observable<void> {
    return this.http.delete<void>(this.apiUrl(`/api/admin/products/${id}`));
  }

  getAdminBots(keyword = '', page = 0, size = 50): Observable<BotsResponse> {
    return this.http.get<BotsResponse>(this.apiUrl('/api/admin/bots'), {
      params: this.keywordParams(keyword).set('page', String(page)).set('size', String(size))
    });
  }

  getAdminBot(id: number): Observable<AdminBot> {
    return this.http.get<AdminBot>(this.apiUrl(`/api/admin/bots/${id}`));
  }

  createAdminBot(request: BotRequest): Observable<AdminBot> {
    return this.http.post<AdminBot>(this.apiUrl('/api/admin/bots'), request);
  }

  updateAdminBot(id: number, request: BotRequest): Observable<AdminBot> {
    return this.http.put<AdminBot>(this.apiUrl(`/api/admin/bots/${id}`), request);
  }

  deleteAdminBot(id: number): Observable<void> {
    return this.http.delete<void>(this.apiUrl(`/api/admin/bots/${id}`));
  }

  importAdminBots(file: File): Observable<BotImportResponse> {
    const formData = new FormData();
    formData.append('file', file);
    return this.http.post<BotImportResponse>(this.apiUrl('/api/admin/bots/import'), formData);
  }

  getAdminPromoTextManagement(keyword = ''): Observable<PromoTextManagementResponse> {
    return this.http.get<PromoTextManagementResponse>(this.apiUrl('/api/admin/promo-texts/management'), {
      params: this.keywordParams(keyword)
    });
  }

  createAdminPromoText(request: PromoTextRequest): Observable<AdminPromoText> {
    return this.http.post<AdminPromoText>(this.apiUrl('/api/admin/promo-texts'), request);
  }

  updateAdminPromoText(id: number, request: PromoTextRequest): Observable<AdminPromoText> {
    return this.http.put<AdminPromoText>(this.apiUrl(`/api/admin/promo-texts/${id}`), request);
  }

  saveAdminPromoTextAssignment(request: PromoTextAssignmentRequest): Observable<PromoTextAssignment> {
    return this.http.put<PromoTextAssignment>(this.apiUrl('/api/admin/promo-text-assignments'), request);
  }

  resetAdminPromoTextAssignment(managerId: number, section: string, buttonKey: string): Observable<void> {
    return this.http.delete<void>(
      this.apiUrl(`/api/admin/promo-text-assignments/${managerId}/${section}/${buttonKey}`)
    );
  }

  getAdminManagerTexts(keyword = ''): Observable<AdminManagerText[]> {
    return this.http.get<AdminManagerText[]>(this.apiUrl('/api/admin/manager-texts'), {
      params: this.keywordParams(keyword)
    });
  }

  updateAdminManagerText(managerId: number, request: ManagerTextRequest): Observable<AdminManagerText> {
    return this.http.put<AdminManagerText>(this.apiUrl(`/api/admin/manager-texts/${managerId}`), request);
  }

  getOperatorPhones(keyword = ''): Observable<OperatorPhonesResponse> {
    const params = this.keywordParams(keyword);
    return this.http.get<OperatorPhonesResponse>(this.apiUrl('/api/admin/phones'), { params });
  }

  createOperatorPhone(request: OperatorPhoneRequest): Observable<OperatorPhone> {
    return this.http.post<OperatorPhone>(this.apiUrl('/api/admin/phones'), request);
  }

  updateOperatorPhone(id: number, request: OperatorPhoneRequest): Observable<OperatorPhone> {
    return this.http.put<OperatorPhone>(this.apiUrl(`/api/admin/phones/${id}`), request);
  }

  deleteOperatorPhone(id: number): Observable<void> {
    return this.http.delete<void>(this.apiUrl(`/api/admin/phones/${id}`));
  }

  deleteOperatorPhoneDeviceToken(phoneId: number, token: string): Observable<void> {
    return this.http.delete<void>(
      this.apiUrl(`/api/admin/phones/${phoneId}/device-tokens/${encodeURIComponent(token)}`)
    );
  }
  private keywordParams(keyword: string): HttpParams {
    const value = keyword.trim();
    return value ? new HttpParams().set('keyword', value) : new HttpParams();
  }
}
