import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { appEnvironment } from './app-environment';
import type {
  AdminCategory,
  AdminSubCategory,
  TitleRequest,
  SubCategoryRequest,
} from './admin-dictionaries.api';

@Injectable({ providedIn: 'root' })
export class AdminTaxonomyApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${appEnvironment.apiBaseUrl}/api/admin`;

  getCategories(keyword = ''): Observable<AdminCategory[]> {
    return this.http.get<AdminCategory[]>(`${this.baseUrl}/categories`, {
      params: this.keywordParams(keyword),
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

  private keywordParams(keyword: string): HttpParams {
    const value = keyword.trim();
    return value ? new HttpParams().set('keyword', value) : new HttpParams();
  }
}
