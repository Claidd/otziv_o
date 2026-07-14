import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  AdminGamificationRewardRequest,
  AdminGamificationRewardsApi
} from '../../../core/admin-gamification-rewards.api';
import { GamificationReward, GamificationRewardClaim } from '../../../core/gamification.api';
import { AdminLayoutComponent } from '../../../shared/admin-layout.component';
import { ToastService } from '../../../shared/toast.service';

@Component({
  selector: 'app-gamification-rewards',
  imports: [AdminLayoutComponent, FormsModule],
  template: `
    <app-admin-layout title="Награды" active="gamification-rewards">
      <main class="reward-admin">
        <section class="panel">
          <h1>Каталог наград</h1>
          <p>Здесь можно подготовить варианты наград и обработать заявки. Общие параметры находятся в справочнике «Геймификация».</p>
        </section>

        <section class="panel editor">
          <div class="section-head"><h2>{{ editingId() ? 'Редактирование награды' : 'Новая награда' }}</h2><button type="button" class="ghost" (click)="resetForm()">Очистить</button></div>
          <div class="form-grid">
            <label>Код<input [(ngModel)]="form.code" placeholder="COFFEE_CERTIFICATE"></label>
            <label>Название<input [(ngModel)]="form.title" placeholder="Сертификат на кофе"></label>
            <label>Тип<select [(ngModel)]="form.rewardType"><option value="VIRTUAL">Виртуальная</option><option value="MATERIAL">Материальная</option><option value="PRIVILEGE">Привилегия</option><option value="CERTIFICATE">Сертификат</option></select></label>
            <label>Иконка<input [(ngModel)]="form.icon" placeholder="redeem"></label>
            <label>Цена, жет.<input type="number" min="0" [(ngModel)]="form.tokenCost"></label>
            <label>Уровень от<input type="number" min="1" [(ngModel)]="form.requiredLevel"></label>
            <label>Остаток<input type="number" min="0" [(ngModel)]="form.stockQuantity" placeholder="без ограничения"></label>
            <label>Сортировка<input type="number" [(ngModel)]="form.sortOrder"></label>
            <label class="wide">Описание<textarea [(ngModel)]="form.description" rows="3"></textarea></label>
            <label class="check"><input type="checkbox" [(ngModel)]="form.active"> Активна</label>
          </div>
          <button type="button" (click)="saveReward()" [disabled]="saving()">{{ editingId() ? 'Сохранить' : 'Создать награду' }}</button>
        </section>

        <section class="panel">
          <h2>Созданные награды</h2>
          <div class="reward-grid">
            @for (reward of rewards(); track reward.id) {
              <article [class.off]="!reward.active">
                @if (reward.imageUrl) { <img [src]="reward.imageUrl" [alt]="reward.title"> }
                @else { <span class="material-icons-sharp icon">{{ reward.icon || 'redeem' }}</span> }
                <div><strong>{{ reward.title }}</strong><small>{{ reward.tokenCost }} жет. · уровень {{ reward.requiredLevel }}+</small><p>{{ reward.description }}</p></div>
                <div class="actions"><button type="button" class="ghost" (click)="edit(reward)">Изменить</button><label class="upload">Картинка<input type="file" accept="image/*" (change)="upload(reward, $event)"></label></div>
              </article>
            } @empty { <p>Наград пока нет.</p> }
          </div>
        </section>

        <section class="panel">
          <h2>Заявки сотрудников</h2>
          <div class="claim-list">
            @for (claim of claims(); track claim.id) {
              <article><div><strong>{{ claim.userName }} — {{ claim.rewardTitle }}</strong><small>{{ claim.tokenCost }} жет. · {{ claim.requestedAt }}</small></div><span>{{ claim.status }}</span><button type="button" (click)="setClaim(claim, 'APPROVED')">Одобрить</button><button type="button" (click)="setClaim(claim, 'FULFILLED')">Выдано</button><button type="button" class="ghost" (click)="setClaim(claim, 'REJECTED')">Отклонить</button></article>
            } @empty { <p>Новых заявок нет.</p> }
          </div>
        </section>
      </main>
    </app-admin-layout>
  `,
  styles: [`
    .reward-admin{display:grid;gap:1rem;max-width:82rem;margin:0 auto;padding:1rem}.panel{display:grid;gap:.9rem;border:1px solid rgba(103,116,131,.14);border-radius:.6rem;padding:1rem;background:var(--otziv-white);box-shadow:0 .7rem 1.5rem rgba(132,139,200,.1)}h1,h2,p{margin:0;color:var(--otziv-dark)}p,small{color:var(--otziv-info)}.form-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:.7rem}.form-grid label{display:grid;gap:.25rem;color:var(--otziv-info);font-size:.75rem;font-weight:900}.form-grid .wide{grid-column:span 4}.form-grid .check{display:flex;align-items:center}input,select,textarea{box-sizing:border-box;width:100%;border:1px solid rgba(103,116,131,.2);border-radius:.4rem;padding:.6rem;background:var(--otziv-field-background);font:inherit}input[type=checkbox]{width:auto}button,.upload{border:0;border-radius:.45rem;padding:.65rem .85rem;color:#fff;background:var(--otziv-primary);font:inherit;font-weight:900;cursor:pointer}.ghost{color:var(--otziv-dark);background:rgba(103,116,131,.1)}.section-head,.actions{display:flex;justify-content:space-between;gap:.5rem}.reward-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:.75rem}.reward-grid article{display:grid;grid-template-rows:7rem 1fr auto;gap:.6rem;border:1px solid rgba(103,116,131,.14);border-radius:.5rem;padding:.75rem}.reward-grid article.off{opacity:.55}.reward-grid img{width:100%;height:7rem;object-fit:cover;border-radius:.35rem}.icon{display:grid;place-items:center;color:var(--otziv-warning);font-size:3rem}.reward-grid article div{display:grid;gap:.25rem}.upload{position:relative;overflow:hidden}.upload input{position:absolute;inset:0;opacity:0;cursor:pointer}.claim-list{display:grid;gap:.5rem}.claim-list article{display:grid;grid-template-columns:minmax(0,1fr) auto auto auto auto;align-items:center;gap:.5rem;border-bottom:1px solid rgba(103,116,131,.12);padding:.55rem 0}.claim-list article div{display:grid}@media(max-width:60rem){.form-grid,.reward-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.form-grid .wide{grid-column:span 2}.claim-list article{grid-template-columns:1fr auto}.claim-list article button{grid-row:2}}@media(max-width:38rem){.form-grid,.reward-grid{grid-template-columns:1fr}.form-grid .wide{grid-column:auto}}
  `]
})
export class GamificationRewardsComponent {
  private readonly api = inject(AdminGamificationRewardsApi);
  private readonly toast = inject(ToastService);
  readonly rewards = signal<GamificationReward[]>([]);
  readonly claims = signal<GamificationRewardClaim[]>([]);
  readonly editingId = signal<number | null>(null);
  readonly saving = signal(false);
  form: AdminGamificationRewardRequest = this.emptyForm();

  constructor() { this.load(); }

  load(): void {
    this.api.rewards().subscribe({
      next: (rewards) => {
        this.rewards.set(rewards);
        this.api.claims().subscribe({
          next: (claims) => this.claims.set(claims),
          error: () => this.toast.error('Заявки не загружены', 'Проверьте подключение к серверу')
        });
      },
      error: () => this.toast.error('Награды не загружены', 'Проверьте подключение к серверу')
    });
  }
  edit(reward: GamificationReward): void { this.editingId.set(reward.id); this.form = { code: reward.code, title: reward.title, description: reward.description, rewardType: reward.rewardType, icon: reward.icon, imageUrl: reward.imageUrl, tokenCost: reward.tokenCost, requiredLevel: reward.requiredLevel, stockQuantity: reward.stockQuantity, active: reward.active, sortOrder: reward.sortOrder }; }
  resetForm(): void { this.editingId.set(null); this.form = this.emptyForm(); }
  saveReward(): void { if (!this.form.code.trim() || !this.form.title.trim()) return; this.saving.set(true); const request = this.editingId() ? this.api.update(this.editingId()!, this.form) : this.api.create(this.form); request.subscribe({ next: () => { this.saving.set(false); this.resetForm(); this.load(); this.toast.success('Награда сохранена', 'Она появится в каталоге после включения'); }, error: (err) => { this.saving.set(false); this.toast.error('Не удалось сохранить', err?.error?.message || 'Проверьте поля'); } }); }
  upload(reward: GamificationReward, event: Event): void { const file = (event.target as HTMLInputElement).files?.[0]; if (!file) return; this.api.uploadImage(reward.id, file).subscribe({ next: () => this.load(), error: () => this.toast.error('Картинка не загружена', 'Проверьте файл и хранилище') }); }
  setClaim(claim: GamificationRewardClaim, status: string): void { this.api.updateClaim(claim.id, status).subscribe({ next: () => this.load(), error: () => this.toast.error('Статус не изменён', 'Попробуйте ещё раз') }); }
  private emptyForm(): AdminGamificationRewardRequest { return { code: '', title: '', description: '', rewardType: 'VIRTUAL', icon: 'redeem', imageUrl: null, tokenCost: 1, requiredLevel: 1, stockQuantity: null, active: false, sortOrder: 0 }; }
}
