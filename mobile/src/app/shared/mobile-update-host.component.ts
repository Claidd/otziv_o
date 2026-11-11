import { ChangeDetectionStrategy } from '@angular/core';
import { Component, inject } from '@angular/core';
import { formatUpdateSize } from '../core/mobile-update.helpers';
import { MobileUpdateService } from '../core/mobile-update.service';

@Component({
  changeDetection: ChangeDetectionStrategy.Eager,
  selector: 'app-mobile-update-host',
  standalone: true,
  template: `
    @if (update.visible() && update.release(); as release) {
      <div class="update-backdrop" role="presentation">
        <section class="update-dialog" role="alertdialog" aria-modal="true" aria-labelledby="update-title">
          <!-- Only this part scrolls. The status and the buttons below stay on the screen whatever the length of the notes. -->
          <div class="update-body">
            <div class="update-icon"><span class="material-icons-sharp">system_update</span></div>
            <div class="update-copy">
              <span class="update-kicker">НОВАЯ ВЕРСИЯ</span>
              <h2 id="update-title">Обновление {{ release.versionName }}</h2>
              @if (release.notes) {
                <p class="update-notes">{{ release.notes }}</p>
              }
              <span class="update-meta">{{ size(release.fileSize) }}</span>
            </div>
          </div>

          <div class="update-footer">
            @if (update.state() === 'downloading') {
              <div class="update-progress" role="progressbar" [attr.aria-valuenow]="update.progress()" aria-valuemin="0" aria-valuemax="100">
                <span [style.width.%]="update.progress()"></span>
              </div>
              <strong class="update-status">Загрузка: {{ update.progress() }}%</strong>
            } @else if (update.state() === 'permission') {
              <p class="update-message">Разрешите установку для «Компания О!» и вернитесь в приложение.</p>
            } @else if (update.state() === 'installing') {
              <p class="update-message">Открываем установщик Android…</p>
            } @else if (update.state() === 'error') {
              <p class="update-error">{{ update.error() }}</p>
            }

            <div class="update-actions">
              @if (update.state() === 'available') {
                <button class="update-primary" type="button" (click)="update.start()">
                  <span class="material-icons-sharp">download</span>
                  Обновить
                </button>
                @if (!update.required()) {
                  <button class="update-secondary" type="button" (click)="update.defer()">Позже</button>
                }
              } @else if (update.state() === 'error') {
                <button class="update-primary" type="button" (click)="update.retry()">
                  <span class="material-icons-sharp">refresh</span>
                  Повторить
                </button>
                @if (!update.required()) {
                  <button class="update-secondary" type="button" (click)="update.defer()">Закрыть</button>
                }
              }
            </div>
          </div>
        </section>
      </div>
    }
  `,
  styles: [`
    .update-backdrop {
      position: fixed;
      z-index: 12000;
      inset: 0;
      display: grid;
      /* One definite track, so the window's max-height is measured against the screen and not its own content. */
      grid-template: minmax(0, 1fr) / minmax(0, 1fr);
      place-items: center;
      padding: max(1rem, env(safe-area-inset-top)) max(1rem, env(safe-area-inset-right)) max(1rem, env(safe-area-inset-bottom)) max(1rem, env(safe-area-inset-left));
      background: rgba(24, 27, 35, 0.56);
      backdrop-filter: blur(5px);
    }

    /*
     * The window never grows past the screen: long notes on a small screen or with a large system font
     * used to push the buttons out of reach and the window blocked the whole app (1.0.86, 2026-11-11).
     */
    .update-dialog {
      display: flex;
      flex-direction: column;
      box-sizing: border-box;
      width: min(100%, 25rem);
      max-height: 100%;
      /* Last resort for a screen shorter than the buttons themselves: the whole window scrolls. */
      overflow-y: auto;
      border: 1px solid #dce2ea;
      border-radius: 8px;
      padding: 1.25rem;
      color: #252a38;
      background: #f8fafc;
      box-shadow: 0 1.5rem 4rem rgba(24, 31, 45, 0.24);
    }

    .update-body { flex: 1 1 auto; min-height: 4.5rem; overflow-y: auto; overscroll-behavior: contain; }
    .update-footer { flex: none; }

    .update-icon {
      display: grid;
      width: 3rem;
      height: 3rem;
      place-items: center;
      margin-bottom: 1rem;
      border-radius: 8px;
      color: #fff;
      background: #267a70;
    }

    .update-icon span { font-size: 1.7rem; }
    .update-copy { display: grid; gap: 0.45rem; }
    .update-kicker { color: #267a70; font-size: 0.72rem; font-weight: 900; }
    h2 { margin: 0; font-size: 1.35rem; letter-spacing: 0; }
    .update-notes, .update-message, .update-error { margin: 0.35rem 0 0; line-height: 1.45; }
    .update-meta { color: #6c7585; font-size: 0.8rem; font-weight: 800; }
    .update-progress { height: 0.55rem; overflow: hidden; margin-top: 1.2rem; border-radius: 999px; background: #e1e6ec; }
    .update-progress span { display: block; height: 100%; border-radius: inherit; background: #267a70; transition: width 180ms ease; }
    .update-status { display: block; margin-top: 0.55rem; color: #596273; font-size: 0.8rem; }
    .update-message { margin-top: 1rem; padding: 0.85rem; border: 1px solid #cbd8e4; border-radius: 8px; background: #eef4f8; }
    .update-error { margin-top: 1rem; padding: 0.85rem; border: 1px solid #efb7c3; border-radius: 8px; color: #9b233d; background: #fff0f4; }
    .update-actions { display: grid; gap: 0.6rem; margin-top: 1.2rem; }
    button { min-height: 3rem; border-radius: 8px; font: inherit; font-weight: 900; }
    .update-primary { display: flex; align-items: center; justify-content: center; gap: 0.45rem; border: 0; color: #fff; background: #267a70; }
    .update-secondary { border: 1px solid #cfd6df; color: #4e5868; background: transparent; }

    :host-context(body.otziv-dark-theme) .update-backdrop { background: rgba(0, 0, 0, 0.7); }
    :host-context(body.otziv-dark-theme) .update-dialog { border-color: #35424a; color: #f3f6f7; background: #192126; box-shadow: 0 1.5rem 4rem rgba(0, 0, 0, 0.52); }
    :host-context(body.otziv-dark-theme) .update-meta,
    :host-context(body.otziv-dark-theme) .update-status { color: #aab8c1; }
    :host-context(body.otziv-dark-theme) .update-progress { background: #303b42; }
    :host-context(body.otziv-dark-theme) .update-message { border-color: #38535a; background: #202e33; }
    :host-context(body.otziv-dark-theme) .update-error { border-color: #703647; color: #ffb4c5; background: #351f28; }
    :host-context(body.otziv-dark-theme) .update-secondary { border-color: #425059; color: #d6dfe4; }
  `]
})
export class MobileUpdateHostComponent {
  readonly update = inject(MobileUpdateService);
  readonly size = formatUpdateSize;
}
