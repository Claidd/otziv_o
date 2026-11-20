import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { InactiveCompanyContactsComponent } from './inactive-company-contacts.component';

describe('inactive company contact review', () => {
  it('cancels without a request and checks once after confirmation, then refreshes actual exclusion', async () => {
    TestBed.configureTestingModule({providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])]});
    const fixture = TestBed.createComponent(InactiveCompanyContactsComponent);
    const http = TestBed.inject(HttpTestingController);
    const row = {companyId:2116,title:'Pervoцвет',companyStatus:'На стопе',platform:'TELEGRAM',description:'Чат недоступен',checkStatus:'UNAVAILABLE',checkedAt:null,excluded:true};
    http.expectOne(r=>r.url.endsWith('/contacts')).flush([row]);
    http.expectOne(r=>r.url.endsWith('/delivery-reviews')).flush([]); fixture.detectChanges();
    const click = (name: string) => {
      const button = Array.from(fixture.nativeElement.querySelectorAll('button')).find(b=>(b as HTMLElement).textContent?.trim()===name) as HTMLButtonElement;
      expect(button).toBeTruthy();button.click();fixture.detectChanges();
    };
    click('Проверить доступ');click('Отмена');http.expectNone(r=>r.method==='POST');
    click('Проверить доступ');click('Проверить');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Проверяю…');
    http.expectOne(r=>r.method==='POST'&&r.url.endsWith('/2116/recheck')).flush({...row,excluded:false,checkStatus:'AVAILABLE',description:'Доступ подтверждён'});
    http.expectOne(r=>r.method==='GET'&&r.url.endsWith('/contacts')).flush([]);
    http.expectOne(r=>r.url.endsWith('/delivery-reviews')).flush([]);fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Pervoцвет: Доступ подтверждён');
    expect(fixture.nativeElement.querySelectorAll('article')).toHaveLength(0);http.verify();
  });
  it('moves a closed idle chat to exclusions after confirmation without sending the historical message', () => {
    TestBed.configureTestingModule({providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])]});
    const fixture=TestBed.createComponent(InactiveCompanyContactsComponent);
    const http=TestBed.inject(HttpTestingController);
    const review={stateId:1238696,companyId:1732,title:'Single',managerId:2,scenario:'Предложение заказа',preparedAt:null,targetUrl:'/orders'};
    http.expectOne(r=>r.url.endsWith('/contacts')).flush([]);
    http.expectOne(r=>r.url.endsWith('/delivery-reviews')).flush([review]);fixture.detectChanges();
    const click=(name:string)=>{(Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[]).find(b=>b.textContent?.trim()===name)!.click();fixture.detectChanges();};
    expect(fixture.nativeElement.textContent).toContain('операция №1238696');
    click('Проверить доступ');click('Отмена');http.expectNone(r=>r.method==='POST');
    click('Проверить доступ');click('Проверить');
    const contact={companyId:1732,title:'Single',companyStatus:'На стопе',platform:'MAX',description:'Чат закрыт',checkStatus:'UNAVAILABLE',checkedAt:null,excluded:true};
    http.expectOne(r=>r.method==='POST'&&r.url.endsWith('/1732/recheck')).flush(contact);
    http.expectOne(r=>r.url.endsWith('/contacts')).flush([contact]);
    http.expectOne(r=>r.url.endsWith('/delivery-reviews')).flush([]);fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Рассылка приостановлена');
    expect(fixture.nativeElement.textContent).not.toContain('операция №1238696');
    http.verify();
  });
});
