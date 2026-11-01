import { Component, Input, OnChanges, OnDestroy, ChangeDetectorRef, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';

export interface BillingPayer { type: string; name: string; inn: string; kpp: string; address: string; }
export interface BillingPayerChoice { id: string; companyId: number; profileId: string; payer: BillingPayer; primary: boolean; version: number; lastUsedAt?: string | null; }
@Component({
  selector: 'app-billing-payer-editor', imports: [FormsModule],
  template: `
    <section class="payer-editor" aria-label="Реквизиты плательщиков">
      <p>Реквизиты необязательны для сохранения компании. Они используются при выставлении счёта.</p>
      @if (error) { <p role="alert" class="error">{{ error }}</p> }
      @if (loading) { <p>Загружаем реквизиты…</p> }
      @for (choice of choices; track choice.id) {
        <article>
          <strong>{{ choice.payer.name }}</strong><small>ИНН {{ choice.payer.inn }} @if(choice.payer.kpp) { · КПП {{ choice.payer.kpp }} } @if(choice.primary) { · Основной }</small>
          @if (confirmDelete === choice.id) {
            <p>Удалить эти реквизиты из списка компании? Выпущенные документы и история сохранятся. Автоматический выпуск с этими реквизитами остановится.</p>
            <button type="button" (click)="remove(choice)" [disabled]="busy">Удалить реквизиты</button>
            <button type="button" (click)="confirmDelete = null" [disabled]="busy">Вернуться</button>
          } @else {
            <button type="button" (click)="edit(choice)" [disabled]="busy">Редактировать</button>
            @if(!choice.primary) { <button type="button" (click)="makePrimary(choice)" [disabled]="busy">Сделать основным</button> }
            <button type="button" (click)="confirmDelete = choice.id" [disabled]="busy">Удалить</button>
          }
        </article>
      }
      @if (!loading && !choices.length) { <p>Сохранённых плательщиков пока нет.</p> }
      <button type="button" (click)="create()" [disabled]="busy || loading">Добавить плательщика</button>
      @if (draft) {
        <div class="draft">
          <strong>{{ editing ? 'Изменение реквизитов' : 'Новый плательщик' }}</strong>
          <label>ИНН<input name="payerInn" [ngModelOptions]="{standalone:true}" [ngModel]="draft.inn" (ngModelChange)="innChanged($event)" inputmode="numeric" maxlength="12" [disabled]="busy"></label>
          <button type="button" (click)="lookup()" [disabled]="busy || lookupBusy || ![10,12].includes(draft.inn.length)">{{ lookupBusy ? 'Ищем…' : 'Найти по ИНН' }}</button>
          @if(lookupMessage) { <p class="hint">{{ lookupMessage }}</p> }
          @if(candidates.length > 1) { <label>Организация или подразделение<select [ngModelOptions]="{standalone:true}" [ngModel]="''" (ngModelChange)="chooseCandidate($event)"><option value="">Выберите вариант</option>@for(p of candidates;track $index) {<option [value]="$index">{{p.name}} · КПП {{p.kpp || 'не применяется'}}</option>}</select></label> }
          <label>Тип<select [(ngModel)]="draft.type" [ngModelOptions]="{standalone:true}" [disabled]="busy" (ngModelChange)="typeChanged()"><option value="company">Организация</option><option value="ip">ИП</option></select></label>
          <label>Полное наименование<input [(ngModel)]="draft.name" [ngModelOptions]="{standalone:true}" maxlength="300" [disabled]="busy"></label>
          @if(draft.type==='company') {<label>КПП<input [(ngModel)]="draft.kpp" [ngModelOptions]="{standalone:true}" maxlength="9" [disabled]="busy"></label>}
          <label>Юридический адрес<textarea [(ngModel)]="draft.address" [ngModelOptions]="{standalone:true}" maxlength="1000" [disabled]="busy"></textarea></label>
          <label class="check"><input type="checkbox" [(ngModel)]="primary" [ngModelOptions]="{standalone:true}" [disabled]="busy">Основной плательщик компании</label>
          <button type="button" (click)="save()" [disabled]="busy || lookupBusy">Сохранить реквизиты</button><button type="button" (click)="cancelDraft()" [disabled]="busy">Отмена</button>
        </div>
      }
      <article>
        <label class="check"><input type="checkbox" [(ngModel)]="automatic" [ngModelOptions]="{standalone:true}" [disabled]="busy || loading">Автоматически выставлять бумажные счета новым заказам</label>
        @if(automatic) {<label>Плательщик для автоматического выпуска<select [(ngModel)]="automaticChoice" [ngModelOptions]="{standalone:true}" [disabled]="busy"><option value="">Выберите сохранённые реквизиты</option>@for(c of choices;track c.id){<option [value]="c.id">{{c.payer.name}} · ИНН {{c.payer.inn}}</option>}</select></label>}
        <p class="hint">После выполнения неоплаченного заказа система использует эти реквизиты. Удалённый плательщик требует замены; режим не переключается на другую оплату автоматически.</p>
        <button type="button" (click)="saveAutomatic()" [disabled]="busy || loading">Сохранить автоматическое выставление</button>
      </article>
    </section>
  `,
  styles: [`:host{display:block}.payer-editor{color:inherit}article,.draft{border:1px solid #8894a466;border-radius:12px;padding:14px;margin:12px 0}small{display:block;margin:5px 0;overflow-wrap:anywhere}label{display:block;margin:10px 0}input,select,textarea{display:block;width:100%;box-sizing:border-box;padding:10px;border:1px solid #8894a4;border-radius:8px;background:var(--surface,var(--card-bg,#fff));color:inherit;font:inherit}.check{display:flex;gap:10px;align-items:center}.check input{width:20px}button{border:1px solid #8894a4;border-radius:8px;background:transparent;color:inherit;padding:10px;margin:4px;cursor:pointer}button:disabled{opacity:.5}.error{color:#ce354b}.hint{font-size:13px;opacity:.8}:host-context(body.otziv-dark-theme) input,:host-context(body.otziv-dark-theme) select,:host-context(body.otziv-dark-theme) textarea{background:#1d242b;color:#edf1f5}`]
})
export class BillingPayerEditorComponent implements OnChanges, OnDestroy {
  @Input({required:true}) companyId!: number;
  private readonly http = inject(HttpClient);
  private readonly view = inject(ChangeDetectorRef);
  choices: BillingPayerChoice[] = []; loading = false; busy = false; error = ''; lookupMessage = ''; lookupBusy = false;
  draft: BillingPayer | null = null; editing: BillingPayerChoice | null = null; primary = false; confirmDelete: string | null = null;
  candidates: BillingPayer[] = []; automatic = false; automaticChoice = '';
  private ticket = 0; private lookupTicket = 0;
  private base(): string { return `/api/paper-invoices/companies/${this.companyId}`; }
  ngOnChanges(): void { ++this.ticket; ++this.lookupTicket; this.busy=false;this.draft=null;this.confirmDelete=null;this.load(); }
  private load(): void {
    if(!this.companyId) return;const ticket=++this.ticket;this.loading=true;this.error='';this.view.markForCheck();
    this.http.get<BillingPayerChoice[]>(this.base()+'/payers').subscribe({next:rows=>{if(ticket===this.ticket){this.choices=rows;this.loading=false;this.view.markForCheck();}},error:e=>{if(ticket===this.ticket){this.loading=false;this.fail(e);}}});
    this.http.get<{automatic:boolean;choiceId:string|null}>(this.base()+'/automatic').subscribe({next:p=>{if(ticket===this.ticket){this.automatic=p.automatic;this.automaticChoice=p.choiceId || '';this.view.markForCheck();}},error:e=>{if(ticket===this.ticket)this.fail(e);}});
  }
  create(): void { this.editing=null;this.primary=false;this.confirmDelete=null;this.draft={type:'company',name:'',inn:'',kpp:'',address:''};this.lookupMessage='';this.candidates=[];this.lookupBusy=false;++this.lookupTicket;this.view.markForCheck(); }
  edit(choice:BillingPayerChoice):void {this.editing=choice;this.draft={...choice.payer};this.primary=choice.primary;this.lookupMessage='';this.candidates=[];this.lookupBusy=false;++this.lookupTicket;this.view.markForCheck();}
  cancelDraft():void {this.draft=null;this.editing=null;++this.lookupTicket;this.lookupBusy=false;this.view.markForCheck();}
  innChanged(value:string):void {if(!this.draft)return;++this.lookupTicket;this.lookupBusy=false;this.candidates=[];this.draft={type:'company',name:'',inn:value.replace(/\D/g,''),kpp:'',address:''};this.view.markForCheck();}
  typeChanged():void {if(this.draft?.type==='ip')this.draft.kpp='';}
  lookup():void {
    if(!this.draft || this.busy)return;const ticket=this.ticket,lookup=++this.lookupTicket,inn=this.draft.inn;this.lookupBusy=true;this.lookupMessage='';this.view.markForCheck();
    this.http.get<{candidates:BillingPayer[]}>(this.base()+'/lookup',{params:{inn}}).subscribe({next:r=>{if(ticket!==this.ticket || lookup!==this.lookupTicket || this.draft?.inn!==inn)return;this.lookupBusy=false;this.candidates=r.candidates;if(r.candidates.length===1)this.draft={...r.candidates[0]};else if(!r.candidates.length)this.lookupMessage='По ИНН ничего не найдено. Реквизиты можно заполнить вручную.';this.view.markForCheck();},error:()=>{if(ticket===this.ticket && lookup===this.lookupTicket){this.lookupBusy=false;this.lookupMessage='Поиск недоступен. Реквизиты можно заполнить вручную.';this.view.markForCheck();}}});
  }
  chooseCandidate(index:string):void {if(index!=='' && this.candidates[Number(index)])this.draft={...this.candidates[Number(index)]};}
  save():void {
    if(!this.draft || this.busy)return;this.busy=true;this.error='';const ticket=this.ticket;
    const body={payer:{...this.draft},version:this.editing?.version ?? null,primary:this.primary};
    const call=this.editing?this.http.put(this.base()+'/payers/'+this.editing.id,body):this.http.post(this.base()+'/payers',body);
    call.subscribe({next:()=>{if(ticket!==this.ticket)return;this.busy=false;this.cancelDraft();this.load();},error:e=>{if(ticket===this.ticket)this.fail(e);}});
  }
  makePrimary(choice:BillingPayerChoice):void {this.edit(choice);this.primary=true;this.save();}
  remove(choice:BillingPayerChoice):void {
    if(this.busy)return;this.busy=true;const ticket=this.ticket;
    this.http.delete(this.base()+'/payers/'+choice.id,{params:{version:choice.version}}).subscribe({next:()=>{if(ticket===this.ticket){this.busy=false;this.confirmDelete=null;this.cancelDraft();this.load();}},error:e=>{if(ticket===this.ticket)this.fail(e);}});
  }
  saveAutomatic():void {
    if(this.busy)return;if(this.automatic && !this.automaticChoice){this.error='Выберите плательщика для автоматического выпуска';this.view.markForCheck();return;}
    this.busy=true;const ticket=this.ticket;
    this.http.put(this.base()+'/automatic',{automatic:this.automatic,choiceId:this.automaticChoice || null}).subscribe({next:()=>{if(ticket===this.ticket){this.busy=false;this.load();}},error:e=>{if(ticket===this.ticket)this.fail(e);}});
  }
  private fail(error:any):void {this.busy=false;this.error=error?.error?.detail || error?.error?.message || 'Не удалось сохранить реквизиты. Основные данные компании можно сохранить независимо.';this.view.markForCheck();}
  ngOnDestroy():void {++this.ticket;++this.lookupTicket;}
}
