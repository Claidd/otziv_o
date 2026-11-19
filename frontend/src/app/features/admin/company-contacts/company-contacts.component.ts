import { Component } from '@angular/core';
import { AdminLayoutComponent } from '../../../shared/admin-layout.component';
import { InactiveCompanyContactsComponent } from '../../../shared/inactive-company-contacts.component';

@Component({ standalone: true, imports: [AdminLayoutComponent, InactiveCompanyContactsComponent],
  template: '<app-admin-layout title="Недоступные чаты"><app-inactive-company-contacts /></app-admin-layout>' })
export class CompanyContactsComponent { }
