import { Component, ViewChild } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { FormsModule, NgForm } from '@angular/forms';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { BillingPayerEditorComponent } from './billing-payer-editor.component';

@Component({imports:[FormsModule,BillingPayerEditorComponent],template:`<form #company="ngForm"><input name="title" [(ngModel)]="title" required><app-billing-payer-editor [companyId]="1" /></form>`})
class Host {title='Компания';@ViewChild(NgForm) form!:NgForm;@ViewChild(BillingPayerEditorComponent) editor!:BillingPayerEditorComponent;}
describe('Optional company payer editor',()=>{
  let f:ReturnType<typeof TestBed.createComponent<Host>>,http:HttpTestingController;
  const payer={type:'company',name:'Тест',inn:'7707083893',kpp:'773601001',address:'Москва'};
  const choice={id:'saved',companyId:1,profileId:'profile',payer,primary:true,version:3};
  function loaded(rows:any[]=[]){http.expectOne('/api/paper-invoices/companies/1/payers').flush(rows);http.expectOne('/api/paper-invoices/companies/1/automatic').flush({automatic:false,choiceId:null});f.detectChanges();}
  beforeEach(()=>{TestBed.configureTestingModule({imports:[Host],providers:[provideHttpClient(),provideHttpClientTesting()]});f=TestBed.createComponent(Host);http=TestBed.inject(HttpTestingController);f.detectChanges();});
  afterEach(()=>{f.destroy();http.verify();});
  it('empty optional requisites never join or invalidate the company form',async()=>{
    loaded();f.componentInstance.editor.create();f.detectChanges();await f.whenStable();
    expect(Object.keys(f.componentInstance.form.controls)).toEqual(['title']);expect(f.componentInstance.form.valid).toBe(true);
    expect(f.nativeElement.textContent).toContain('Реквизиты необязательны');
  });
  it('lookup failure leaves manual entry available without failing the main form',async()=>{
    loaded();const c=f.componentInstance.editor;c.create();c.innChanged(payer.inn);c.lookup();
    http.expectOne(r=>r.url.endsWith('/lookup')).flush({}, {status:503,statusText:'Unavailable'});f.detectChanges();await f.whenStable();
    expect(c.lookupMessage).toContain('вручную');expect(c.draft?.inn).toBe(payer.inn);expect(f.componentInstance.form.valid).toBe(true);
  });
  it('editing sends the company choice version and keeps the old data on conflict',()=>{
    loaded([choice]);const c=f.componentInstance.editor;c.edit(choice);c.draft!.name='Изменено';c.save();
    const save=http.expectOne('/api/paper-invoices/companies/1/payers/saved');expect(save.request.method).toBe('PUT');expect(save.request.body.version).toBe(3);
    save.flush({detail:'Изменено другим менеджером'},{status:409,statusText:'Conflict'});expect(c.error).toContain('другим менеджером');expect(c.choices[0].payer.name).toBe('Тест');
  });
  it('abandoning deletion performs no mutation and confirmed deletion is versioned',()=>{
    loaded([choice]);const c=f.componentInstance.editor;c.confirmDelete=choice.id;c.confirmDelete=null;http.expectNone(r=>r.method==='DELETE');
    c.remove(choice);const remove=http.expectOne(r=>r.method==='DELETE');expect(remove.request.params.get('version')).toBe('3');remove.flush({});loaded();expect(c.choices.length).toBe(0);
  });
  it('late lookup after cancelling the draft cannot repopulate it',()=>{
    loaded();const c=f.componentInstance.editor;c.create();c.innChanged(payer.inn);c.lookup();const lookup=http.expectOne(r=>r.url.endsWith('/lookup'));c.cancelDraft();lookup.flush({candidates:[payer]});expect(c.draft).toBeNull();
  });
});
