import { NgClass } from '@angular/common';
import { Component, EventEmitter, HostBinding, Input, Output } from '@angular/core';

export type MobileReviewCardLayout = 'worker' | 'details';
export type MobileReviewPhotoMode = 'none' | 'link' | 'button' | 'file';

@Component({
  selector: 'app-mobile-review-card-shell',
  standalone: true,
  imports: [NgClass],
  template: `
    <article
      class="mobile-review-card mobile-review-card-shell"
      [ngClass]="toneClass"
      [class.mobile-review-card-shell--active]="active"
      [class.mobile-review-card-shell--published]="published"
      [class.mobile-review-card-shell--expanded-text]="expandedText"
      [attr.data-card-index]="cardIndex"
      [attr.data-review-id]="reviewId"
    >
      <header>
        @if (titleHref) {
          <a [href]="titleHref" target="_blank" rel="noopener" [attr.aria-label]="title" (click)="guardLink($event, titleHref)">
            {{ title }}
          </a>
        } @else {
          <strong [attr.aria-label]="title">{{ title }}</strong>
        }

        <div class="card-id-line">
          @switch (photoMode) {
            @case ('link') {
              <a class="review-card-badge photo" [href]="photoUrl || '#'" target="_blank" rel="noopener" aria-label="Открыть фото отзыва" (click)="guardLink($event, photoUrl)">
                <span class="material-icons-sharp">photo_camera</span>
              </a>
            }
            @case ('button') {
              <button class="review-card-badge photo danger" type="button" (click)="photoAction.emit($event)" aria-label="Нужно фото">
                <span class="material-icons-sharp">photo_camera</span>
              </button>
            }
            @case ('file') {
              <label
                class="review-card-badge photo danger"
                [class.loading]="photoLoading"
                aria-label="Загрузить фото отзыва"
                (click)="photoAction.emit($event)"
              >
                <span class="material-icons-sharp">{{ photoLoading ? 'hourglass_top' : 'photo_camera' }}</span>
                <input type="file" accept="image/*" (change)="photoFileChange.emit($event)" [disabled]="photoLoading">
              </label>
            }
          }

          <small>{{ idLabel }}</small>

          @if (badBadge) {
            <em class="task-badge bad">{{ badBadge }}</em>
          }
          @if (recoveryBadge) {
            <em class="task-badge recovery">{{ recoveryBadge }}</em>
          }
          @if (noteVisible) {
            <button class="review-card-badge note" type="button" (click)="noteClick.emit()" aria-label="Заметки отзыва">
              <span aria-hidden="true">!</span>
            </button>
          }
        </div>
      </header>

      <ng-content />

      <footer>
        <span>{{ footerLeft }}</span>
        @if (footerRightAsButton) {
          <button class="review-edit-link" type="button" (click)="footerRightClick.emit()" [disabled]="footerRightDisabled" [attr.aria-label]="footerRightTitle || footerRight">
            {{ footerRight }}
          </button>
        } @else {
          <span [attr.aria-label]="footerRightTitle || footerRight">{{ footerRight }}</span>
        }
      </footer>
    </article>
  `,
  styles: [`
    :host {
      display: grid;
      flex: 0 0 var(--otziv-board-card-width, min(15.4rem, 76vw));
      min-width: 0;
      min-height: 100%;
      height: 100%;
      scroll-snap-align: center;
    }

    :host(.layout-details) {
      flex-basis: var(--otziv-detail-card-width, min(15.4rem, 79vw));
      scroll-snap-align: start;
    }

    :host-context(.worker-list--expanded),
    :host-context(.review-mobile-strip--expanded) {
      flex: none;
      width: 100%;
      min-height: auto;
      height: auto;
      scroll-snap-align: none;
    }

    .mobile-review-card-shell {
      display: grid;
      min-width: 0;
      height: 100%;
      max-height: 100%;
      align-content: start;
      gap: var(--otziv-card-gap, 0.34rem);
      border: 1px solid rgba(103, 116, 131, 0.2);
      border-radius: 0.9rem;
      padding: var(--otziv-card-padding, 0.68rem);
      overflow: hidden;
      background: linear-gradient(180deg, var(--otziv-white) 0%, var(--otziv-white) 58%, var(--otziv-muted-surface) 100%);
      box-shadow: 0 0.55rem 1.2rem rgba(132, 139, 200, 0.14);
    }

    :host(.layout-details) .mobile-review-card-shell {
      grid-template-rows: auto auto auto auto auto auto auto;
      align-content: start;
      gap: var(--otziv-card-gap, 0.36rem);
      padding: var(--otziv-card-padding, 0.52rem);
      overflow-x: hidden;
      overflow-y: auto;
      overscroll-behavior: contain;
      scrollbar-width: none;
      background: linear-gradient(180deg, var(--otziv-tone-walk-surface) 0%, var(--otziv-white) 42%, var(--otziv-white) 100%);
    }

    :host(.layout-details) .mobile-review-card-shell::-webkit-scrollbar {
      display: none;
    }

    :host-context(.worker-list--expanded) .mobile-review-card-shell,
    :host-context(.review-mobile-strip--expanded) .mobile-review-card-shell {
      height: auto;
      max-height: none;
    }

    .mobile-review-card-shell--active {
      border-color: rgba(244, 197, 66, 0.72);
      box-shadow: 0 0 0 0.14rem rgba(244, 197, 66, 0.18);
    }

    .mobile-review-card-shell--published,
    .mobile-review-card-shell.tone-success {
      border-color: var(--otziv-tone-success-border);
      background: linear-gradient(180deg, var(--otziv-tone-success-surface) 0%, var(--otziv-white) 54%, var(--otziv-white) 100%);
    }

    .mobile-review-card-shell.tone-wait,
    .mobile-review-card-shell.waiting-client {
      border-color: var(--otziv-tone-wait-border);
      background: linear-gradient(180deg, var(--otziv-tone-wait-surface) 0%, var(--otziv-white) 54%, var(--otziv-white) 100%);
    }

    .mobile-review-card-shell.tone-walk {
      border-color: var(--otziv-tone-walk-border);
      background: linear-gradient(180deg, var(--otziv-tone-walk-surface) 0%, var(--otziv-white) 54%, var(--otziv-white) 100%);
    }

    .mobile-review-card-shell.tone-correction {
      border-color: var(--otziv-tone-correction-border);
      background: linear-gradient(180deg, var(--otziv-tone-correction-surface) 0%, var(--otziv-white) 54%, var(--otziv-white) 100%);
    }

    .mobile-review-card-shell.tone-publication {
      border-color: var(--otziv-tone-publication-border);
      background: linear-gradient(180deg, var(--otziv-tone-publication-surface) 0%, var(--otziv-white) 54%, var(--otziv-white) 100%);
    }

    .mobile-review-card-shell.tone-bad {
      border-color: var(--otziv-tone-bad-border);
      background: linear-gradient(180deg, var(--otziv-tone-bad-surface) 0%, var(--otziv-white) 54%, var(--otziv-white) 100%);
    }

    .mobile-review-card-shell.tone-recovery {
      border-color: rgba(244, 197, 66, 0.7);
      background: linear-gradient(180deg, #fff8d8 0%, var(--otziv-white) 54%, var(--otziv-white) 100%);
    }

    header {
      display: grid;
      grid-template-columns: minmax(0, 1fr) max-content;
      align-items: start;
      gap: 0.42rem;
    }

    :host(.layout-details) header,
    footer {
      display: flex;
      min-width: 0;
      align-items: center;
      justify-content: space-between;
      gap: 0.45rem;
    }

    header a,
    header strong {
      display: -webkit-box;
      min-width: 0;
      overflow: hidden;
      color: var(--otziv-dark);
      font-family: var(--otziv-card-title-font);
      font-size: 0.98rem;
      font-weight: 1000;
      line-height: 1.06;
      text-decoration: none;
      overflow-wrap: anywhere;
      -webkit-box-orient: vertical;
      -webkit-line-clamp: 2;
    }

    :host(.layout-details) header a,
    :host(.layout-details) header strong {
      display: block;
      font-size: 1rem;
      line-height: 1.1;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .card-id-line {
      display: inline-grid;
      flex: 0 0 auto;
      grid-auto-flow: column;
      grid-auto-columns: max-content;
      align-items: center;
      justify-content: flex-end;
      gap: 0.18rem;
      color: var(--otziv-info);
      font-family: var(--otziv-font-family);
      font-size: var(--otziv-unified-subtitle-size, 0.56rem);
      font-weight: 900;
      line-height: 1;
      white-space: nowrap;
    }

    .review-card-badge {
      position: relative;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 1.24rem;
      height: 1.24rem;
      min-width: 1.24rem;
      align-self: center;
      border: 0;
      border-radius: 999px;
      padding: 0;
      color: #4d3900;
      background: #f4c542;
      font: inherit;
      text-decoration: none;
    }

    .review-card-badge.note {
      display: inline-grid;
      flex: 0 0 var(--otziv-note-alert-size, 1.06rem);
      width: var(--otziv-note-alert-size, 1.06rem);
      height: var(--otziv-note-alert-size, 1.06rem);
      min-width: var(--otziv-note-alert-size, 1.06rem);
      min-height: var(--otziv-note-alert-size, 1.06rem);
      place-items: center;
      align-self: center;
      border: 1px solid rgba(218, 168, 36, 0.36);
      color: #6a5100;
      background: #f4c542;
      font: 1000 0.64rem/1 var(--otziv-card-title-font);
    }

    :host(.layout-details) .review-card-badge {
      width: 1.28rem;
      height: 1.28rem;
      min-width: 1.28rem;
    }

    .review-card-badge.photo {
      width: 1.18rem;
      height: 1.18rem;
      min-width: 1.18rem;
      color: #ffffff;
      background: var(--otziv-success);
    }

    .review-card-badge.danger {
      background: var(--otziv-danger);
    }

    .review-card-badge.loading {
      opacity: 0.72;
    }

    .review-card-badge input {
      position: absolute;
      width: 1px;
      height: 1px;
      opacity: 0;
      pointer-events: none;
    }

    .review-card-badge .material-icons-sharp {
      display: block;
      font-size: 0.84rem;
      line-height: 1;
    }

    .review-card-badge.photo .material-icons-sharp {
      font-size: 0.76rem;
    }

    .task-badge {
      border-radius: 999px;
      padding: 0.14rem 0.35rem;
      font-size: 0.58rem;
      font-style: normal;
      font-weight: 900;
    }

    .task-badge.bad {
      color: var(--otziv-danger);
      background: rgba(255, 0, 96, 0.1);
    }

    .task-badge.recovery {
      color: #8a6400;
      background: rgba(255, 248, 216, 0.92);
    }

    footer {
      margin-top: 0;
    }

    footer span,
    footer button {
      min-width: 0;
      color: var(--otziv-info);
      background: transparent;
      border: 0;
      padding: 0;
      font-family: var(--otziv-font-family);
      font-size: 0.68rem;
      font-weight: 900;
      text-align: left;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    footer button {
      text-align: right;
      text-decoration: underline;
    }

    footer button:disabled {
      opacity: 0.72;
    }

    :host-context(body.otziv-dark-theme) .mobile-review-card-shell {
      background: linear-gradient(180deg, rgba(32, 38, 44, 0.98) 0%, rgba(24, 29, 34, 0.98) 100%);
    }
  `]
})
export class MobileReviewCardShellComponent {
  @Input() layout: MobileReviewCardLayout = 'worker';
  @Input() title = '';
  @Input() titleHref = '';
  @Input() idLabel = '';
  @Input() toneClass = '';
  @Input() active = false;
  @Input() published = false;
  @Input() expandedText = false;
  @Input() cardIndex: number | string | null = null;
  @Input() reviewId: number | string | null = null;
  @Input() photoMode: MobileReviewPhotoMode = 'none';
  @Input() photoUrl = '';
  @Input() photoLoading = false;
  @Input() noteVisible = false;
  @Input() badBadge = '';
  @Input() recoveryBadge = '';
  @Input() footerLeft = '';
  @Input() footerRight = '';
  @Input() footerRightTitle = '';
  @Input() footerRightAsButton = true;
  @Input() footerRightDisabled = false;

  @Output() photoAction = new EventEmitter<Event>();
  @Output() photoFileChange = new EventEmitter<Event>();
  @Output() noteClick = new EventEmitter<void>();
  @Output() footerRightClick = new EventEmitter<void>();

  @HostBinding('class.layout-details')
  get isDetailsLayout(): boolean {
    return this.layout === 'details';
  }

  @HostBinding('class.expanded-text')
  get hasExpandedText(): boolean {
    return this.expandedText;
  }

  guardLink(event: MouseEvent, href: string | null | undefined): void {
    if (!href || href === '#') {
      event.preventDefault();
    }
  }
}
