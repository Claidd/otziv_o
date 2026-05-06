import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { appEnvironment } from './app-environment';

export interface DictionaryOption {
  id: number;
  title: string;
}

export interface AdminCategory {
  id: number;
  title: string;
  subCategoryCount: number;
  subCategories: DictionaryOption[];
}

export interface AdminSubCategory {
  id: number;
  title: string;
  category?: DictionaryOption | null;
}

export interface AdminCity {
  id: number;
  title: string;
}

export interface AdminProduct {
  id: number;
  title: string;
  price: number;
  photo: boolean;
  category?: DictionaryOption | null;
}

export interface AdminBot {
  id: number;
  login: string;
  password: string;
  fio: string;
  active: boolean;
  counter: number;
  status?: DictionaryOption | null;
  worker?: DictionaryOption | null;
  city?: DictionaryOption | null;
}

export interface AdminPromoText {
  id: number;
  position: number;
  text: string;
}

export interface AdminManagerText {
  managerId: number;
  managerTitle: string;
  payText: string;
  beginText: string;
  offerText: string;
  reminderText: string;
  startText: string;
}

export interface PromoButtonSlot {
  section: string;
  sectionTitle: string;
  buttonKey: string;
  buttonLabel: string;
  outputPosition: number;
  defaultPromoPosition: number;
  defaultPromoTextId?: number | null;
}

export interface PromoTextAssignment {
  id: number;
  managerId?: number | null;
  managerTitle: string;
  section: string;
  sectionTitle: string;
  buttonKey: string;
  buttonLabel: string;
  outputPosition: number;
  promoTextId?: number | null;
  promoTextLabel: string;
}

export interface PromoTextManagementResponse {
  texts: AdminPromoText[];
  managers: DictionaryOption[];
  assignments: PromoTextAssignment[];
  buttons: PromoButtonSlot[];
}

export interface ProductsResponse {
  products: AdminProduct[];
  categories: DictionaryOption[];
}

export interface BotsResponse {
  bots: AdminBot[];
  workers: DictionaryOption[];
  statuses: DictionaryOption[];
  cities: DictionaryOption[];
}

export interface BotImportResponse {
  totalRows: number;
  added: number;
  skippedDuplicates: number;
  skippedInvalid: number;
  errors: string[];
}

export interface BotBrowserOpenResponse {
  botId: number;
  vncUrl: string;
  userAgent?: string;
  platform?: string;
}

export interface TitleRequest {
  title: string;
}

export interface SubCategoryRequest {
  title: string;
  categoryId: number | null;
}

export interface ProductRequest {
  title: string;
  price: number;
  categoryId: number | null;
  photo: boolean;
}

export interface BotRequest {
  login: string;
  password: string;
  fio: string;
  workerId: number | null;
  cityId: number | null;
  statusId: number | null;
  active: boolean;
  counter: number;
}

export interface PromoTextRequest {
  text: string;
}

export interface PromoTextAssignmentRequest {
  managerId: number;
  section: string;
  buttonKey: string;
  promoTextId: number;
}

export interface ManagerTextRequest {
  payText: string;
  beginText: string;
  offerText: string;
  reminderText: string;
  startText: string;
}

@Injectable({ providedIn: 'root' })
export class AdminDictionariesApi {
  private readonly baseUrl = `${appEnvironment.apiBaseUrl}/api/admin`;

  constructor(private readonly http: HttpClient) {}

  getCategories(keyword = ''): Observable<AdminCategory[]> {
    return this.http.get<AdminCategory[]>(`${this.baseUrl}/categories`, {
      params: this.keywordParams(keyword)
    });
  }

  createCategory(request: TitleRequest): Observable<AdminCategory> {
    return this.http.post<AdminCategory>(`${this.baseUrl}/categories`, request);
  }

  updateCategory(id: number, request: TitleRequest): Observable<AdminCategory> {
    return this.http.put<AdminCategory>(`${this.baseUrl}/categories/${id}`, request);
  }

  deleteCategory(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/categories/${id}`);
  }

  getSubCategories(keyword = '', categoryId?: number | null): Observable<AdminSubCategory[]> {
    let params = this.keywordParams(keyword);
    if (categoryId != null) {
      params = params.set('categoryId', String(categoryId));
    }

    return this.http.get<AdminSubCategory[]>(`${this.baseUrl}/subcategories`, { params });
  }

  createSubCategory(request: SubCategoryRequest): Observable<AdminSubCategory> {
    return this.http.post<AdminSubCategory>(`${this.baseUrl}/subcategories`, request);
  }

  updateSubCategory(id: number, request: SubCategoryRequest): Observable<AdminSubCategory> {
    return this.http.put<AdminSubCategory>(`${this.baseUrl}/subcategories/${id}`, request);
  }

  deleteSubCategory(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/subcategories/${id}`);
  }

  getCities(keyword = ''): Observable<AdminCity[]> {
    return this.http.get<AdminCity[]>(`${this.baseUrl}/cities`, {
      params: this.keywordParams(keyword)
    });
  }

  createCity(request: TitleRequest): Observable<AdminCity> {
    return this.http.post<AdminCity>(`${this.baseUrl}/cities`, request);
  }

  updateCity(id: number, request: TitleRequest): Observable<AdminCity> {
    return this.http.put<AdminCity>(`${this.baseUrl}/cities/${id}`, request);
  }

  deleteCity(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/cities/${id}`);
  }

  getProducts(keyword = ''): Observable<ProductsResponse> {
    return this.http.get<ProductsResponse>(`${this.baseUrl}/products`, {
      params: this.keywordParams(keyword)
    });
  }

  createProduct(request: ProductRequest): Observable<AdminProduct> {
    return this.http.post<AdminProduct>(`${this.baseUrl}/products`, request);
  }

  updateProduct(id: number, request: ProductRequest): Observable<AdminProduct> {
    return this.http.put<AdminProduct>(`${this.baseUrl}/products/${id}`, request);
  }

  deleteProduct(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/products/${id}`);
  }

  getBots(keyword = ''): Observable<BotsResponse> {
    return this.http.get<BotsResponse>(`${this.baseUrl}/bots`, {
      params: this.keywordParams(keyword)
    });
  }

  getBot(id: number): Observable<AdminBot> {
    return this.http.get<AdminBot>(`${this.baseUrl}/bots/${id}`);
  }

  createBot(request: BotRequest): Observable<AdminBot> {
    return this.http.post<AdminBot>(`${this.baseUrl}/bots`, request);
  }

  updateBot(id: number, request: BotRequest): Observable<AdminBot> {
    return this.http.put<AdminBot>(`${this.baseUrl}/bots/${id}`, request);
  }

  deleteBot(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/bots/${id}`);
  }

  importBots(file: File): Observable<BotImportResponse> {
    const formData = new FormData();
    formData.append('file', file);
    return this.http.post<BotImportResponse>(`${this.baseUrl}/bots/import`, formData);
  }

  openBotBrowser(botId: number): Observable<BotBrowserOpenResponse> {
    return this.http.post<BotBrowserOpenResponse>(
      `${appEnvironment.apiBaseUrl}/api/bots/${botId}/browser/open`,
      {}
    );
  }

  closeBotBrowser(botId: number): Observable<void> {
    return this.http.post<void>(
      `${appEnvironment.apiBaseUrl}/api/bots/${botId}/browser/close`,
      {}
    );
  }

  getPromoTexts(keyword = ''): Observable<AdminPromoText[]> {
    return this.http.get<AdminPromoText[]>(`${this.baseUrl}/promo-texts`, {
      params: this.keywordParams(keyword)
    });
  }

  getPromoTextManagement(keyword = ''): Observable<PromoTextManagementResponse> {
    return this.http.get<PromoTextManagementResponse>(`${this.baseUrl}/promo-texts/management`, {
      params: this.keywordParams(keyword)
    });
  }

  createPromoText(request: PromoTextRequest): Observable<AdminPromoText> {
    return this.http.post<AdminPromoText>(`${this.baseUrl}/promo-texts`, request);
  }

  updatePromoText(id: number, request: PromoTextRequest): Observable<AdminPromoText> {
    return this.http.put<AdminPromoText>(`${this.baseUrl}/promo-texts/${id}`, request);
  }

  savePromoTextAssignment(request: PromoTextAssignmentRequest): Observable<PromoTextAssignment> {
    return this.http.put<PromoTextAssignment>(`${this.baseUrl}/promo-text-assignments`, request);
  }

  resetPromoTextAssignment(managerId: number, section: string, buttonKey: string): Observable<void> {
    return this.http.delete<void>(
      `${this.baseUrl}/promo-text-assignments/${managerId}/${section}/${buttonKey}`
    );
  }

  getManagerTexts(keyword = ''): Observable<AdminManagerText[]> {
    return this.http.get<AdminManagerText[]>(`${this.baseUrl}/manager-texts`, {
      params: this.keywordParams(keyword)
    });
  }

  updateManagerText(managerId: number, request: ManagerTextRequest): Observable<AdminManagerText> {
    return this.http.put<AdminManagerText>(`${this.baseUrl}/manager-texts/${managerId}`, request);
  }

  private keywordParams(keyword: string): HttpParams {
    const value = keyword.trim();
    return value ? new HttpParams().set('keyword', value) : new HttpParams();
  }
}
