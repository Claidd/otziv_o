import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { appEnvironment } from './app-environment';
import type { ProductsResponse, AdminProduct, ProductRequest } from './admin-dictionaries.api';

@Injectable({ providedIn: 'root' })
export class AdminProductsApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${appEnvironment.apiBaseUrl}/api/admin`;

  getProducts(keyword = ''): Observable<ProductsResponse> {
    return this.http.get<ProductsResponse>(`${this.baseUrl}/products`, {
      params: this.keywordParams(keyword),
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

  private keywordParams(keyword: string): HttpParams {
    const value = keyword.trim();
    return value ? new HttpParams().set('keyword', value) : new HttpParams();
  }
}
