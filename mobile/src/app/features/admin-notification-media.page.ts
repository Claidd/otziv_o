import { ChangeDetectionStrategy } from '@angular/core';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { IonContent, IonRefresher, IonRefresherContent, type RefresherCustomEvent } from '@ionic/angular';
import { firstValueFrom, forkJoin } from 'rxjs';
import {
  AdminNotificationMediaApi,
  type NotificationMediaAsset,
  type NotificationMediaEvent,
  type NotificationMediaRule,
  type NotificationRecipientType
} from '../core/admin-notification-media.api';
import { safeHttpsExternalUrl } from '../shared/external-navigation';
import { MobileConfirmService } from '../shared/mobile-confirm.service';
import { MobileExternalLinkService } from '../shared/mobile-external-link.service';
import { MobileHeaderComponent } from '../shared/mobile-header.component';
import { MobileMediaService } from '../shared/mobile-media.service';

type Audience = 'ALL' | NotificationRecipientType;
interface RuleDraft { enabled: boolean; probability: string; cooldown: string; }
const IMAGE_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp']);
const MAX_IMAGE_BYTES = 5 * 1024 * 1024;

@Component({
  changeDetection: ChangeDetectionStrategy.Eager,
  selector: 'app-admin-notification-media-mobile',
  imports: [FormsModule, IonContent, IonRefresher, IonRefresherContent, MobileHeaderComponent],
  template: `
    <div class="ion-page">
      <app-mobile-header title="Картинки уведомлений" />
      <ion-content fullscreen>
        <ion-refresher slot="fixed" (ionRefresh)="refresh($event)"><ion-refresher-content /></ion-refresher>
        <main class="media-page">
          <header class="hero">
            <div><p class="eyebrow">TELEGRAM · ADMIN / OWNER</p><h1>Картинки уведомлений</h1><p>Для события можно загрузить несколько картинок. Система выбирает их случайно и не повторяет предыдущую в том же чате.</p></div>
            <button type="button" aria-label="Обновить" (click)="load()" [disabled]="loading() || !!saving()"><span class="material-icons-sharp">refresh</span></button>
          </header>

          @if (notice()) { <p class="alert success" role="status">{{ notice() }}</p> }
          @if (error()) { <p class="alert" role="alert">{{ error() }}</p> }
          @if (loading() && !rules().length) { <p class="empty">Загружаю настройки…</p> }

          <section class="card">
            <div class="segments" aria-label="Получатель уведомления">
              <button type="button" [class.active]="audience() === 'ALL'" (click)="setAudience('ALL')">Все</button>
              <button type="button" [class.active]="audience() === 'MANAGER'" (click)="setAudience('MANAGER')">Менеджеры</button>
              <button type="button" [class.active]="audience() === 'WORKER'" (click)="setAudience('WORKER')">Специалисты</button>
            </div>
            <label class="field"><span>Добавить событие</span><select [ngModel]="newEventCode()" (ngModelChange)="newEventCode.set($event)"><option value="">Выберите функцию отправки</option>@for (event of availableEvents(); track event.code) { <option [value]="event.code">{{ roleLabel(event.recipientType) }} · {{ event.label }}</option> }</select></label>
            <button type="button" class="primary" (click)="createRule()" [disabled]="!newEventCode() || !!saving()">Добавить уведомление</button>
          </section>

          @for (rule of filteredRules(); track rule.id) {
            <article class="card rule" [class.off]="!rule.enabled">
              <header><div><div class="badges"><span>{{ roleLabel(rule.recipientType) }}</span>@if (rule.serious) { <span>Серьёзное событие</span> }<span>{{ rule.images.length }} фото</span></div><h2>{{ rule.eventLabel }}</h2><p>{{ rule.eventDescription }}</p><code>{{ rule.eventCode }}</code></div></header>
              @if (drafts()[rule.id]; as draft) {
                <label class="check"><input type="checkbox" [ngModel]="draft.enabled" (ngModelChange)="setRuleEnabled(rule.id, $event)"> Уведомление {{ draft.enabled ? 'включено' : 'выключено' }}</label>
                <div class="setting-fields">
                  <label class="field"><span>Вероятность картинки, %</span><input type="number" min="0" max="100" step="1" [ngModel]="draft.probability" (ngModelChange)="setRuleNumber(rule.id, 'probability', $event)"></label>
                  <label class="field"><span>Пауза между картинками, мин.</span><input type="number" min="0" max="10080" step="1" [ngModel]="draft.cooldown" (ngModelChange)="setRuleNumber(rule.id, 'cooldown', $event)"></label>
                </div>
              }
              <div class="actions"><button type="button" class="primary" (click)="saveRule(rule)" [disabled]="!!saving()">Сохранить</button><button type="button" (click)="sendTest(rule)" [disabled]="!!saving()">Тест себе</button><button type="button" class="danger" (click)="deleteRule(rule)" [disabled]="!!saving()">Удалить настройку</button></div>

              <div class="images-head"><h3>Варианты изображения</h3><p>JPG, PNG или WEBP, до 5 МБ. Несколько файлов можно выбрать одновременно.</p></div>
              <label class="upload"><span class="material-icons-sharp">cloud_upload</span> Загрузить картинки<input type="file" accept="image/jpeg,image/png,image/webp" multiple (change)="uploadFiles(rule, $event)" [disabled]="!!saving()"></label>
              @if (media.nativePhotoPickerAvailable) { <button type="button" (click)="pickAndUpload(rule)" [disabled]="!!saving()">Выбрать фото из галереи</button> }

              <div class="images">
                @for (asset of rule.images; track asset.id) {
                  <figure [class.asset-off]="!asset.active">
                    @if (safeImage(asset.imageUrl); as url) { <button type="button" class="image-button" (click)="openImage(url)" aria-label="Открыть картинку"><img [src]="url" [alt]="asset.originalFilename || rule.eventLabel"></button> }
                    <figcaption><strong>{{ asset.originalFilename || 'Картинка уведомления' }}</strong><small>{{ asset.active ? 'Участвует в выборе' : 'Временно отключена' }}</small></figcaption>
                    <div class="actions"><button type="button" (click)="toggleAsset(asset)" [disabled]="!!saving()">{{ asset.active ? 'Отключить' : 'Включить' }}</button><label class="upload replace">Заменить<input type="file" accept="image/jpeg,image/png,image/webp" (change)="replaceFile(asset, $event)" [disabled]="!!saving()"></label>@if (media.nativePhotoPickerAvailable) { <button type="button" (click)="pickAndReplace(asset)" [disabled]="!!saving()">Фото из галереи</button> }<button type="button" class="danger" (click)="deleteAsset(asset)" [disabled]="!!saving()">Удалить фото</button></div>
                  </figure>
                } @empty { <p class="empty">Картинок пока нет. Уведомление продолжит отправляться текстом.</p> }
              </div>
            </article>
          } @empty {
            @if (!loading()) { <p class="empty">Для выбранного получателя настройки ещё не созданы.</p> }
          }
        </main>
      </ion-content>
    </div>
  `,
  styles: [`
    .media-page{display:grid;gap:.8rem;max-width:900px;margin:auto;padding:1rem .75rem calc(5rem + env(safe-area-inset-bottom));color:var(--otziv-dark)}
    .hero,.card,.empty{border:1px solid rgba(108,155,207,.23);border-radius:1rem;padding:.8rem;background:var(--otziv-white);box-shadow:0 .35rem 1.25rem rgba(62,81,121,.07)}.hero{display:flex;align-items:start;justify-content:space-between;gap:.7rem}.hero h1{margin:.05rem 0;font-size:1.35rem}.hero p,.card p{margin:.25rem 0;color:var(--otziv-info);font-size:.73rem;line-height:1.4}.eyebrow{font-size:.62rem!important;font-weight:900;letter-spacing:.07em}.card{display:grid;gap:.7rem}.card h2{margin:.15rem 0;font-size:1rem}.card h3{margin:0;font-size:.83rem}.card code{color:var(--otziv-info);font-size:.62rem;overflow-wrap:anywhere}
    button,.upload{display:inline-flex;align-items:center;justify-content:center;gap:.3rem;min-height:2.3rem;border:1px solid rgba(108,155,207,.3);border-radius:.7rem;padding:.45rem .6rem;color:var(--otziv-primary);background:var(--otziv-white);font:inherit;font-size:.72rem;font-weight:800;text-align:center}button:disabled,.upload:has(input:disabled){opacity:.5}.primary,.segments button.active{border-color:transparent;color:#fff;background:var(--otziv-primary)}.danger{color:var(--otziv-danger)}.hero button{min-width:2.3rem}.hero .material-icons-sharp{font-size:1.1rem}.segments{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:.3rem}.segments button{padding:.4rem .2rem;font-size:.67rem}.field{display:grid;gap:.25rem;color:var(--otziv-info);font-size:.7rem;font-weight:800}.field input,.field select{width:100%;box-sizing:border-box;border:1px solid rgba(108,155,207,.3);border-radius:.6rem;padding:.55rem;color:var(--otziv-dark);background:var(--otziv-white);font:inherit;font-size:.8rem}.check{display:flex;align-items:center;gap:.4rem;font-size:.75rem;font-weight:800}.check input{width:1rem;height:1rem;accent-color:var(--otziv-primary)}.setting-fields{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:.5rem}.actions{display:flex;flex-wrap:wrap;gap:.35rem}.actions>*{flex:1 1 auto}.badges{display:flex;flex-wrap:wrap;gap:.25rem}.badges span{border-radius:999px;padding:.2rem .45rem;color:var(--otziv-primary);background:var(--otziv-light);font-size:.6rem;font-weight:900}.rule.off{opacity:.78}.upload{position:relative;cursor:pointer}.upload input{position:absolute;inset:0;width:100%;height:100%;opacity:0;cursor:pointer}.images-head{border-top:1px solid rgba(108,155,207,.22);padding-top:.7rem}.images{display:grid;gap:.5rem}.images figure{display:grid;gap:.45rem;margin:0;border:1px solid rgba(108,155,207,.2);border-radius:.7rem;padding:.55rem}.images figure.asset-off{opacity:.7}.image-button{overflow:hidden;max-height:180px;padding:0;border:0;border-radius:.5rem}.image-button img{display:block;max-width:100%;max-height:180px;object-fit:contain}.images figcaption{display:grid;gap:.1rem;min-width:0;font-size:.72rem;overflow-wrap:anywhere}.images figcaption small{color:var(--otziv-info)}.alert{border:1px solid rgba(232,48,103,.32);border-radius:.7rem;padding:.55rem;color:var(--otziv-danger);background:#fff0f4;font-size:.74rem}.alert.success{border-color:rgba(47,159,149,.35);color:#127966;background:#eafaf3}
    :host-context(body.otziv-dark-theme) .hero,:host-context(body.otziv-dark-theme) .card,:host-context(body.otziv-dark-theme) .empty,:host-context(body.otziv-dark-theme) button:not(.primary):not(.active),:host-context(body.otziv-dark-theme) .upload,:host-context(body.otziv-dark-theme) .field input,:host-context(body.otziv-dark-theme) .field select{background:#1f2629}
  `]
})
export class AdminNotificationMediaPage implements OnInit {
  private readonly api = inject(AdminNotificationMediaApi);
  private readonly confirm = inject(MobileConfirmService);
  private readonly external = inject(MobileExternalLinkService);
  readonly media = inject(MobileMediaService);

  readonly events = signal<NotificationMediaEvent[]>([]);
  readonly rules = signal<NotificationMediaRule[]>([]);
  readonly drafts = signal<Record<number, RuleDraft>>({});
  readonly audience = signal<Audience>('ALL');
  readonly newEventCode = signal('');
  readonly loading = signal(false);
  readonly saving = signal<string | null>(null);
  readonly error = signal<string | null>(null);
  readonly notice = signal<string | null>(null);
  readonly filteredRules = computed(() => this.rules().filter((rule) => this.audience() === 'ALL' || rule.recipientType === this.audience()));
  readonly availableEvents = computed(() => {
    const used = new Set(this.rules().map((rule) => rule.eventCode));
    return this.events().filter((event) => !used.has(event.code) && (this.audience() === 'ALL' || event.recipientType === this.audience()));
  });

  ngOnInit(): void { void this.load(); }

  async refresh(event: RefresherCustomEvent): Promise<void> {
    try { await this.load(); } finally { await event.target.complete(); }
  }

  async load(): Promise<void> {
    if (this.saving()) return;
    this.loading.set(true); this.error.set(null);
    try {
      const result = await firstValueFrom(forkJoin({ events: this.api.events(), rules: this.api.rules() }));
      this.events.set(result.events);
      this.rules.set(result.rules);
      this.drafts.set(Object.fromEntries(result.rules.map((rule) => [rule.id, this.toDraft(rule)])));
    } catch (error) { this.error.set(this.errorMessage(error, 'Настройки уведомлений не загружены.')); }
    finally { this.loading.set(false); }
  }

  setAudience(value: Audience): void { this.audience.set(value); this.newEventCode.set(''); }
  roleLabel(role: NotificationRecipientType): string { return role === 'MANAGER' ? 'Менеджер' : 'Специалист'; }
  setRuleEnabled(id: number, enabled: boolean): void { this.updateDraft(id, { enabled }); }
  setRuleNumber(id: number, field: 'probability' | 'cooldown', value: number | string | null): void { this.updateDraft(id, { [field]: String(value ?? '') }); }
  safeImage(value: string | null | undefined): string | null { return safeHttpsExternalUrl(value); }
  openImage(url: string): void { void this.external.open(this.safeImage(url)); }

  async createRule(): Promise<void> {
    const code = this.newEventCode();
    if (!code || this.saving() || !this.availableEvents().some((event) => event.code === code)) return;
    this.saving.set('create'); this.error.set(null);
    try {
      const rule = await firstValueFrom(this.api.create({ eventCode: code, enabled: true, imageProbabilityPercent: 100, cooldownMinutes: 0 }));
      this.upsert(rule);
      this.newEventCode.set('');
      this.notice.set('Уведомление добавлено. Теперь загрузите картинки.');
    } catch (error) { this.error.set(this.errorMessage(error, 'Не удалось добавить уведомление.')); }
    finally { this.saving.set(null); }
  }

  async saveRule(rule: NotificationMediaRule): Promise<void> {
    const draft = this.drafts()[rule.id];
    if (!draft || this.saving()) return;
    this.saving.set(`rule-${rule.id}`); this.error.set(null);
    try {
      const updated = await firstValueFrom(this.api.update(rule.id, {
        eventCode: rule.eventCode, enabled: draft.enabled,
        imageProbabilityPercent: this.bound(draft.probability, 0, 100),
        cooldownMinutes: this.bound(draft.cooldown, 0, 10080)
      }));
      this.upsert(updated); this.notice.set(`Настройки «${updated.eventLabel}» сохранены.`);
    } catch (error) { this.error.set(this.errorMessage(error, 'Настройки не сохранены.')); }
    finally { this.saving.set(null); }
  }

  async uploadFiles(rule: NotificationMediaRule, event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const files = Array.from(input.files ?? []);
    input.value = '';
    await this.upload(rule, files);
  }

  async pickAndUpload(rule: NotificationMediaRule): Promise<void> {
    if (this.saving()) return;
    const file = await this.media.pickImageFile(`notification-${rule.id}`);
    if (file) await this.upload(rule, [file]);
  }

  async toggleAsset(asset: NotificationMediaAsset): Promise<void> {
    if (this.saving()) return;
    this.saving.set(`asset-${asset.id}`); this.error.set(null);
    try {
      this.upsert(await firstValueFrom(this.api.updateAsset(asset.id, !asset.active, asset.sortOrder)), false);
      this.notice.set(asset.active ? 'Картинка отключена.' : 'Картинка включена.');
    } catch (error) { this.error.set(this.errorMessage(error, 'Картинка не изменена.')); }
    finally { this.saving.set(null); }
  }

  async replaceFile(asset: NotificationMediaAsset, event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (file) await this.replace(asset, file);
  }

  async pickAndReplace(asset: NotificationMediaAsset): Promise<void> {
    if (this.saving()) return;
    const file = await this.media.pickImageFile(`notification-image-${asset.id}`);
    if (file) await this.replace(asset, file);
  }

  async deleteAsset(asset: NotificationMediaAsset): Promise<void> {
    if (this.saving()) return;
    const approved = await this.confirm.confirm({ title: 'Удалить картинку?', message: 'Она будет удалена из настройки и хранилища S3.', confirmText: 'Удалить', danger: true });
    if (!approved) return;
    this.saving.set(`delete-asset-${asset.id}`); this.error.set(null);
    try { this.upsert(await firstValueFrom(this.api.deleteAsset(asset.id)), false); this.notice.set('Картинка удалена.'); }
    catch (error) { this.error.set(this.errorMessage(error, 'Не удалось удалить картинку.')); }
    finally { this.saving.set(null); }
  }

  async deleteRule(rule: NotificationMediaRule): Promise<void> {
    if (this.saving()) return;
    const approved = await this.confirm.confirm({ title: 'Удалить настройку?', message: `«${rule.eventLabel}» и все картинки будут удалены из S3.`, confirmText: 'Удалить всё', danger: true });
    if (!approved) return;
    this.saving.set(`delete-rule-${rule.id}`); this.error.set(null);
    try {
      await firstValueFrom(this.api.deleteRule(rule.id));
      this.rules.update((current) => current.filter((item) => item.id !== rule.id));
      this.drafts.update((current) => { const next = { ...current }; delete next[rule.id]; return next; });
      this.notice.set(`Настройка «${rule.eventLabel}» удалена.`);
    } catch (error) { this.error.set(this.errorMessage(error, 'Не удалось удалить настройку.')); }
    finally { this.saving.set(null); }
  }

  async sendTest(rule: NotificationMediaRule): Promise<void> {
    if (this.saving()) return;
    const approved = await this.confirm.confirm({ title: 'Отправить тест?', message: `Тест уведомления «${rule.eventLabel}» уйдёт в ваш личный Telegram.`, confirmText: 'Отправить' });
    if (!approved) return;
    this.saving.set(`test-${rule.id}`); this.error.set(null);
    try {
      const result = await firstValueFrom(this.api.sendTest(rule.id));
      if (result.sent) this.notice.set(result.message || 'Тест отправлен.');
      else this.error.set(result.message || 'Тест не отправлен.');
    } catch (error) { this.error.set(this.errorMessage(error, 'Тест не отправлен.')); }
    finally { this.saving.set(null); }
  }

  private async upload(rule: NotificationMediaRule, files: File[]): Promise<void> {
    if (!files.length || this.saving() || !this.validImages(files)) return;
    this.saving.set(`upload-${rule.id}`); this.error.set(null);
    try { this.upsert(await firstValueFrom(this.api.uploadImages(rule.id, files)), false); this.notice.set(`Картинок загружено: ${files.length}.`); }
    catch (error) { this.error.set(this.errorMessage(error, 'Картинки не загружены.')); }
    finally { this.saving.set(null); }
  }

  private async replace(asset: NotificationMediaAsset, file: File): Promise<void> {
    if (this.saving() || !this.validImages([file])) return;
    this.saving.set(`replace-${asset.id}`); this.error.set(null);
    try { this.upsert(await firstValueFrom(this.api.replaceAsset(asset.id, file)), false); this.notice.set('Картинка заменена.'); }
    catch (error) { this.error.set(this.errorMessage(error, 'Картинка не заменена.')); }
    finally { this.saving.set(null); }
  }

  private validImages(files: File[]): boolean {
    const invalid = files.find((file) => !IMAGE_TYPES.has(file.type) || file.size > MAX_IMAGE_BYTES || file.size === 0);
    if (!invalid) return true;
    this.error.set(`«${invalid.name}»: нужен JPG, PNG или WEBP до 5 МБ.`);
    return false;
  }

  private upsert(updated: NotificationMediaRule, resetDraft = true): void {
    this.rules.update((current) => {
      const index = current.findIndex((item) => item.id === updated.id);
      const next = [...current];
      if (index >= 0) next[index] = updated; else next.push(updated);
      return next.sort((a, b) => a.eventLabel.localeCompare(b.eventLabel, 'ru'));
    });
    this.drafts.update((current) => ({ ...current, [updated.id]: !resetDraft && current[updated.id] ? current[updated.id] : this.toDraft(updated) }));
  }

  private updateDraft(id: number, patch: Partial<RuleDraft>): void {
    this.drafts.update((current) => current[id] ? { ...current, [id]: { ...current[id], ...patch } } : current);
  }

  private toDraft(rule: NotificationMediaRule): RuleDraft {
    return { enabled: rule.enabled, probability: String(rule.imageProbabilityPercent), cooldown: String(rule.cooldownMinutes) };
  }

  private bound(value: string, min: number, max: number): number {
    const number = Number(value);
    return Number.isFinite(number) ? Math.max(min, Math.min(max, Math.round(number))) : min;
  }

  private errorMessage(error: unknown, fallback: string): string {
    if (error && typeof error === 'object' && 'error' in error) {
      const body = (error as { error?: { detail?: string; message?: string; error?: string } | string }).error;
      if (typeof body === 'string') return body || fallback;
      return body?.detail || body?.message || body?.error || fallback;
    }
    return error instanceof Error ? error.message : fallback;
  }
}
