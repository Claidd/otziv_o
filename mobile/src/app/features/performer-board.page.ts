import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { IonContent, IonRefresher, IonRefresherContent, type RefresherCustomEvent } from '@ionic/angular/standalone';
import { firstValueFrom } from 'rxjs';
import { PerformerBoardApi, type PerformerTask, type PerformerTaskBoard } from '../core/performer-board.api';
import { safeHttpsExternalUrl } from '../shared/external-navigation';
import { MobileConfirmService } from '../shared/mobile-confirm.service';
import { MobileExternalLinkService } from '../shared/mobile-external-link.service';
import { MobileHeaderComponent } from '../shared/mobile-header.component';
import { MobileMediaService } from '../shared/mobile-media.service';

type TaskSection = keyof PerformerTaskBoard;
const EMPTY_BOARD: PerformerTaskBoard = { offers: [], active: [], waitingPublication: [], published: [], paid: [] };
const SECTIONS: Array<{ key: TaskSection; label: string }> = [
  { key: 'offers', label: 'Новые' },
  { key: 'active', label: 'Выгул' },
  { key: 'waitingPublication', label: 'Публикация' },
  { key: 'published', label: 'Проверка' },
  { key: 'paid', label: 'Оплачено' }
];

@Component({
  selector: 'app-performer-board-mobile',
  imports: [FormsModule, IonContent, IonRefresher, IonRefresherContent, MobileHeaderComponent],
  template: `
    <div class="ion-page">
      <app-mobile-header title="Мои задания" />
      <ion-content fullscreen>
        <ion-refresher slot="fixed" (ionRefresh)="refresh($event)"><ion-refresher-content /></ion-refresher>
        <main class="board-page">
          <header class="hero"><div><p class="eyebrow">ИСПОЛНИТЕЛЬ</p><h1>Мои задания</h1></div><button type="button" (click)="load()" [disabled]="loading() || !!saving()" aria-label="Обновить"><span class="material-icons-sharp">refresh</span></button></header>
          @if (notice()) { <p class="alert success" role="status">{{ notice() }}</p> }
          @if (error()) { <p class="alert" role="alert">{{ error() }}</p> }
          <nav class="sections" aria-label="Статусы заданий">
            @for (sectionItem of sections; track sectionItem.key) {
              <button type="button" [class.active]="section() === sectionItem.key" (click)="chooseSection(sectionItem.key)">{{ sectionItem.label }} <strong>{{ board()[sectionItem.key].length }}</strong></button>
            }
          </nav>
          <label class="field search"><span>Поиск по компании, отзыву, ID</span><input type="search" [ngModel]="keyword()" (ngModelChange)="keyword.set($event)" placeholder="Найти задание"></label>
          @if (loading() && !loaded()) { <p class="empty">Загружаю задания…</p> }
          @for (item of visibleTasks(); track item.id) {
            <article class="card">
              <header><div><h2>{{ item.companyTitle || 'Компания' }}</h2><small>{{ item.cityTitle }} · {{ item.platform }} · #{{ item.id }}</small></div><span class="status">{{ item.status }}</span></header>
              @if (item.filialTitle) { <p class="sub">{{ item.filialTitle }}</p> }
              @if (item.instruction) { <p class="instruction">{{ item.instruction }}</p> }
              @if (item.draftText) { <blockquote>{{ item.draftText }}</blockquote> }
              @if (item.externalConfirmStatus && section() === 'published') { <p>Проверка: {{ item.externalConfirmStatus }}</p> }
              @if (section() === 'paid' && item.payoutAmount !== null) { <p>Выплата: <strong>{{ item.payoutAmount }} ₽</strong></p> }
              <div class="actions">
                @if (section() === 'offers') {
                  <button type="button" class="primary" (click)="accept(item)" [disabled]="!!saving() || !item.offerId">Принять</button>
                  <button type="button" (click)="decline(item)" [disabled]="!!saving() || !item.offerId">Отказаться</button>
                }
                @if (section() === 'active') {
                  <button type="button" class="primary" (click)="markWalked(item)" [disabled]="!!saving()">Выгулял</button>
                  <button type="button" (click)="openForm(item, 'problem')" [disabled]="!!saving()">Проблема</button>
                }
                @if (section() === 'waitingPublication') {
                  <button type="button" class="primary" (click)="openForm(item, 'publish')" [disabled]="!!saving() || !canPublish(item)">Опубликовал</button>
                  <button type="button" (click)="openForm(item, 'problem')" [disabled]="!!saving()">Проблема</button>
                }
              </div>
              @if (section() === 'waitingPublication' && !canPublish(item)) { <p class="hint">Публикация доступна после {{ item.publishAvailableAt }}</p> }
              @if (selectedId() === item.id && formMode() === 'publish') {
                <div class="form">
                  <label class="field"><span>Финальный текст</span><textarea rows="5" [ngModel]="finalText()" (ngModelChange)="finalText.set($event)"></textarea></label>
                  <label class="field"><span>Ссылка на публикацию</span><input type="url" [ngModel]="publicationUrl()" (ngModelChange)="publicationUrl.set($event)"></label>
                  <label class="field"><span>Комментарий</span><input type="text" [ngModel]="comment()" (ngModelChange)="comment.set($event)"></label>
                  <label class="upload">Добавить скриншот<input type="file" accept="image/*" (change)="uploadFile(item, $event)" [disabled]="!!saving()"></label>
                  @if (media.nativePhotoPickerAvailable) { <button type="button" (click)="pickScreenshot(item)" [disabled]="!!saving()">Выбрать из галереи</button> }
                  @if (safeLink(item.performerPublicationScreenshotUrl); as url) { <button type="button" (click)="openLink(url)">Открыть скриншот</button> }
                  <button type="button" class="primary" (click)="publish(item)" [disabled]="!!saving() || finalText().trim().length < 10">Отправить на проверку</button>
                </div>
              }
              @if (selectedId() === item.id && formMode() === 'problem') {
                <div class="form"><label class="field"><span>Что не так?</span><textarea rows="3" [ngModel]="comment()" (ngModelChange)="comment.set($event)"></textarea></label><button type="button" class="primary" (click)="reportProblem(item)" [disabled]="!!saving() || comment().trim().length < 5">Передать менеджеру</button></div>
              }
            </article>
          } @empty { @if (loaded()) { <p class="empty">{{ keyword() ? 'По поиску ничего не найдено' : 'В этом разделе пока пусто' }}</p> } }
        </main>
      </ion-content>
    </div>
  `,
  styles: [`
    .board-page{display:grid;gap:.75rem;max-width:860px;margin:auto;padding:1rem .7rem calc(5rem + env(safe-area-inset-bottom));color:var(--otziv-dark)}
    .hero,.card,.empty{border:1px solid rgba(108,155,207,.23);border-radius:1rem;padding:.8rem;background:var(--otziv-white);box-shadow:0 .35rem 1.25rem rgba(62,81,121,.07)}.hero{display:flex;align-items:center;justify-content:space-between}.hero h1{margin:0;font-size:1.35rem}.eyebrow{margin:0;font-size:.65rem;color:var(--otziv-info);font-weight:900}.hero button{min-width:2.3rem}.sections{display:flex;gap:.3rem;overflow-x:auto}.sections button{white-space:nowrap;min-width:fit-content}.sections button.active,.primary{color:#fff;background:var(--otziv-primary);border-color:transparent}.card{display:grid;gap:.55rem}.card header{display:flex;align-items:start;justify-content:space-between;gap:.5rem}.card h2{margin:0;font-size:1rem}.card small,.sub,.hint{font-size:.7rem;color:var(--otziv-info)}.status{font-size:.65rem;font-weight:800}.card p{margin:0;font-size:.78rem;line-height:1.4}.instruction{white-space:pre-wrap}.card blockquote{margin:0;padding:.55rem;border-left:3px solid var(--otziv-primary);border-radius:.3rem;background:var(--otziv-light);white-space:pre-wrap;font-size:.78rem}.actions{display:flex;flex-wrap:wrap;gap:.4rem}.actions>*{flex:1 1 auto}.form{display:grid;gap:.55rem;border-top:1px solid rgba(108,155,207,.23);padding-top:.65rem}.field{display:grid;gap:.22rem;font-size:.7rem;font-weight:800}.field input,.field textarea{box-sizing:border-box;width:100%;border:1px solid rgba(108,155,207,.3);border-radius:.6rem;padding:.55rem;background:var(--otziv-white);color:var(--otziv-dark);font:inherit;font-size:.8rem}.field textarea{resize:vertical}.upload{position:relative;overflow:hidden}.upload input{position:absolute;inset:0;width:100%;height:100%;opacity:0}button,.upload{display:inline-flex;align-items:center;justify-content:center;min-height:2.3rem;border:1px solid rgba(108,155,207,.3);border-radius:.7rem;padding:.45rem .6rem;background:var(--otziv-white);color:var(--otziv-primary);font:inherit;font-size:.72rem;font-weight:800;text-align:center}button:disabled,.upload:has(input:disabled){opacity:.5}.alert{border:1px solid rgba(232,48,103,.32);border-radius:.7rem;padding:.55rem;color:var(--otziv-danger);background:#fff0f4;font-size:.74rem}.alert.success{border-color:rgba(47,159,149,.35);color:#127966;background:#eafaf3}
    :host-context(body.otziv-dark-theme) .hero,:host-context(body.otziv-dark-theme) .card,:host-context(body.otziv-dark-theme) .empty,:host-context(body.otziv-dark-theme) button:not(.primary),:host-context(body.otziv-dark-theme) .field input,:host-context(body.otziv-dark-theme) .field textarea{background:#1f2629}
  `]
})
export class PerformerBoardPage implements OnInit {
  private readonly api = inject(PerformerBoardApi);
  private readonly confirm = inject(MobileConfirmService);
  private readonly external = inject(MobileExternalLinkService);
  readonly media = inject(MobileMediaService);
  readonly sections = SECTIONS;
  readonly board = signal<PerformerTaskBoard>(EMPTY_BOARD);
  readonly section = signal<TaskSection>('offers');
  readonly keyword = signal('');
  readonly loading = signal(false);
  readonly loaded = signal(false);
  readonly saving = signal<string | null>(null);
  readonly error = signal('');
  readonly notice = signal('');
  readonly selectedId = signal<number | null>(null);
  readonly formMode = signal<'publish' | 'problem' | null>(null);
  readonly finalText = signal('');
  readonly publicationUrl = signal('');
  readonly comment = signal('');
  readonly visibleTasks = computed(() => {
    const query = this.keyword().trim().toLocaleLowerCase();
    const tasks = this.board()[this.section()];
    if (!query) return tasks;
    return tasks.filter(item => [item.id, item.orderId, item.reviewId, item.companyTitle, item.filialTitle, item.cityTitle, item.platform, item.draftText, item.instruction]
      .some(value => String(value ?? '').toLocaleLowerCase().includes(query)));
  });

  ngOnInit(): void { void this.load(); }

  async load(): Promise<void> {
    if (this.loading()) return;
    this.loading.set(true);
    this.error.set('');
    try {
      this.board.set(await firstValueFrom(this.api.board()));
      this.loaded.set(true);
    } catch (error) { this.error.set(this.errorMessage(error, 'Не удалось загрузить задания.')); }
    finally { this.loading.set(false); }
  }

  async refresh(event: RefresherCustomEvent): Promise<void> {
    await this.load();
    await event.target.complete();
  }

  chooseSection(section: TaskSection): void { this.section.set(section); this.selectedId.set(null); this.formMode.set(null); }

  async accept(item: PerformerTask): Promise<void> {
    if (!item.offerId) return;
    await this.run(`accept-${item.id}`, () => firstValueFrom(this.api.acceptOffer(item.offerId!)), 'Задание принято.');
  }

  async decline(item: PerformerTask): Promise<void> {
    if (!item.offerId) return;
    const accepted = await this.confirm.confirm({ title: 'Отказаться от задания?', message: `Задание #${item.id} вернётся в очередь.`, confirmText: 'Отказаться' });
    if (accepted) await this.run(`decline-${item.id}`, () => firstValueFrom(this.api.declineOffer(item.offerId!)), 'Отказ зафиксирован.');
  }

  async markWalked(item: PerformerTask): Promise<void> {
    await this.run(`walked-${item.id}`, () => firstValueFrom(this.api.markWalked(item.id)), 'Выгул отмечен.');
  }

  openForm(item: PerformerTask, mode: 'publish' | 'problem'): void {
    this.selectedId.set(item.id);
    this.formMode.set(mode);
    this.finalText.set(item.finalText || item.draftText || '');
    this.publicationUrl.set(item.publicationUrl || '');
    this.comment.set('');
  }

  async publish(item: PerformerTask): Promise<void> {
    const finalText = this.finalText().trim();
    if (finalText.length < 10 || !this.canPublish(item)) return;
    await this.run(`publish-${item.id}`, () => firstValueFrom(this.api.markPublished(item.id, {
      finalText, publicationUrl: this.publicationUrl().trim() || undefined, comment: this.comment().trim() || undefined
    })), 'Публикация отправлена на проверку.');
  }

  async reportProblem(item: PerformerTask): Promise<void> {
    const comment = this.comment().trim();
    if (comment.length < 5) return;
    await this.run(`problem-${item.id}`, () => firstValueFrom(this.api.reportProblem(item.id, comment)), 'Проблема передана менеджеру.');
  }

  async uploadFile(item: PerformerTask, event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (file) await this.uploadScreenshot(item, file);
  }

  async pickScreenshot(item: PerformerTask): Promise<void> {
    const file = await this.media.pickImageFile('performer-publication');
    if (file) await this.uploadScreenshot(item, file);
  }

  canPublish(item: PerformerTask): boolean {
    const at = Date.parse(item.publishAvailableAt || '');
    return !Number.isFinite(at) || at <= Date.now();
  }

  safeLink(value: string): string | null { return safeHttpsExternalUrl(value); }
  async openLink(url: string): Promise<void> { await this.external.open(url); }

  private async uploadScreenshot(item: PerformerTask, file: File): Promise<void> {
    const image = await this.media.prepareImageFile(file, 'performer-publication');
    await this.run(`screenshot-${item.id}`, () => firstValueFrom(this.api.uploadScreenshot(item.id, image)), 'Скриншот загружен.', false);
  }

  private async run(key: string, action: () => Promise<unknown>, success: string, clearForm = true): Promise<void> {
    if (this.saving()) return;
    this.saving.set(key);
    this.error.set('');
    this.notice.set('');
    try {
      await action();
      this.notice.set(success);
      if (clearForm) { this.selectedId.set(null); this.formMode.set(null); }
      this.board.set(await firstValueFrom(this.api.board()));
    } catch (error) { this.error.set(this.errorMessage(error, 'Действие не выполнено.')); }
    finally { this.saving.set(null); }
  }

  private errorMessage(error: unknown, fallback: string): string {
    if (error && typeof error === 'object' && 'error' in error) {
      const body = (error as { error?: { message?: unknown } }).error;
      if (body && typeof body.message === 'string' && body.message.trim()) return body.message;
    }
    return fallback;
  }
}
