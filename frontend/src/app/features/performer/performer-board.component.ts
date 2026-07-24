import { Component, OnInit, computed, effect, inject, signal, untracked } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';
import { apiErrorMessage } from '../../shared/api-error-message';
import { LoadErrorCardComponent } from '../../shared/load-error-card.component';
import { MobileNavIntentService } from '../../shared/mobile/mobile-nav-intent.service';
import { MobileStatusSheetComponent } from '../../shared/mobile/mobile-status-sheet.component';
import {
  MobileStatusItem,
  MobileStatusSliderComponent
} from '../../shared/mobile/mobile-status-slider.component';
import { ToastService } from '../../shared/toast.service';
import { PerformerApi, PerformerAssignment, PerformerBoard } from '../../core/performer.api';

type SectionKey = 'offers' | 'active' | 'waitingPublication' | 'published' | 'paid';

@Component({
  selector: 'app-performer-board',
  imports: [
    AdminLayoutComponent,
    LoadErrorCardComponent,
    MobileStatusSheetComponent,
    MobileStatusSliderComponent,
    ReactiveFormsModule
  ],
  templateUrl: './performer-board.component.html',
  styleUrl: './performer-board.component.scss'
})
export class PerformerBoardComponent implements OnInit {
  private readonly api = inject(PerformerApi);
  private readonly fb = inject(FormBuilder);
  private readonly toast = inject(ToastService);
  private readonly mobileNavIntent = inject(MobileNavIntentService);
  private lastMobileNavIntentStamp = 0;

  readonly loading = signal(false);
  readonly actionSaving = signal<string | null>(null);
  readonly error = signal<string | null>(null);
  readonly board = signal<PerformerBoard>({
    offers: [],
    active: [],
    waitingPublication: [],
    published: [],
    paid: []
  });
  readonly keyword = signal('');
  readonly activeSection = signal<SectionKey>('offers');
  readonly selectedAssignment = signal<PerformerAssignment | null>(null);
  readonly mobileStatusSheetOpen = signal(false);

  readonly publishForm = this.fb.nonNullable.group({
    finalText: ['', [Validators.required, Validators.minLength(10)]],
    publicationUrl: [''],
    comment: ['']
  });

  readonly problemForm = this.fb.nonNullable.group({
    comment: ['', [Validators.required, Validators.minLength(5)]]
  });

  readonly sections = computed(() => [
    { key: 'offers' as const, label: 'Новые', icon: 'fiber_new', count: this.board().offers.length },
    { key: 'active' as const, label: 'Выгул', icon: 'directions_walk', count: this.board().active.length },
    { key: 'waitingPublication' as const, label: 'Публикация', icon: 'published_with_changes', count: this.board().waitingPublication.length },
    { key: 'published' as const, label: 'Ожидание', icon: 'manage_search', count: this.board().published.length },
    { key: 'paid' as const, label: 'Оплаченные', icon: 'payments', count: this.board().paid.length }
  ]);

  readonly mobileStatusItems = computed<MobileStatusItem[]>(() => this.sections().map((section) => ({
    key: section.key,
    label: section.label,
    value: section.count,
    icon: section.icon,
    tone: this.mobileSectionTone(section.key)
  })));

  readonly currentItems = computed(() => this.filterAssignments(this.board()[this.activeSection()]));
  readonly currentSectionLabel = computed(() =>
    this.sections().find((section) => section.key === this.activeSection())?.label ?? 'Карточки'
  );
  readonly title = computed(() => `Исполнитель - ${this.currentSectionLabel()}`);

  constructor() {
    const initialIntent = this.mobileNavIntent.intent();
    if (initialIntent?.tab === 'worker') {
      this.lastMobileNavIntentStamp = initialIntent.stamp;
      if (initialIntent.mode === 'menu') {
        this.mobileStatusSheetOpen.set(true);
      } else {
        this.setSection('offers');
      }
      this.mobileNavIntent.clear(initialIntent.stamp);
    }

    effect(() => {
      const intent = this.mobileNavIntent.intent();
      if (!intent || intent.tab !== 'worker' || intent.stamp === this.lastMobileNavIntentStamp) {
        return;
      }
      this.lastMobileNavIntentStamp = intent.stamp;

      untracked(() => {
        if (intent.mode === 'menu') {
          this.mobileStatusSheetOpen.set(true);
        } else {
          this.mobileStatusSheetOpen.set(false);
          this.setSection('offers');
        }
        this.mobileNavIntent.clear(intent.stamp);
      });
    });
  }

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.board().subscribe({
      next: (board) => {
        this.board.set(board);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(apiErrorMessage(err, 'Не удалось загрузить карточки'));
        this.loading.set(false);
      }
    });
  }

  setSection(section: SectionKey): void {
    this.activeSection.set(section);
    this.selectedAssignment.set(null);
  }

  selectMobileSection(section: string): void {
    if (!this.sections().some((item) => item.key === section)) {
      return;
    }
    this.mobileStatusSheetOpen.set(false);
    this.setSection(section as SectionKey);
  }

  closeMobileStatusSheet(): void {
    this.mobileStatusSheetOpen.set(false);
  }

  setKeyword(value: string): void {
    this.keyword.set(value);
  }

  clearSearch(): void {
    this.keyword.set('');
  }

  accept(item: PerformerAssignment): void {
    if (!item.offerId) {
      return;
    }
    this.runAction(`accept-${item.offerId}`, this.api.acceptOffer(item.offerId), 'Задание принято');
  }

  decline(item: PerformerAssignment): void {
    if (!item.offerId) {
      return;
    }
    this.runAction(`decline-${item.offerId}`, this.api.declineOffer(item.offerId), 'Отказ зафиксирован');
  }

  walked(item: PerformerAssignment): void {
    this.runAction(`walked-${item.id}`, this.api.markWalked(item.id), 'Выгул отмечен');
  }

  openPublish(item: PerformerAssignment): void {
    this.selectedAssignment.set(item);
    this.publishForm.reset({
      finalText: item.finalText || item.draftText,
      publicationUrl: item.publicationUrl || '',
      comment: ''
    });
    this.problemForm.reset({ comment: '' });
  }

  submitPublish(): void {
    const item = this.selectedAssignment();
    if (!item) {
      return;
    }
    if (this.publishForm.invalid) {
      this.publishForm.markAllAsTouched();
      return;
    }
    const raw = this.publishForm.getRawValue();
    this.runAction(
      `published-${item.id}`,
      this.api.markPublished(item.id, {
        finalText: raw.finalText.trim(),
        publicationUrl: raw.publicationUrl.trim() || undefined,
        comment: raw.comment.trim() || undefined
      }),
      'Публикация отправлена на проверку'
    );
  }

  uploadPublicationScreenshot(item: PerformerAssignment, event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) {
      return;
    }
    this.actionSaving.set(`screenshot-${item.id}`);
    this.api.uploadPublicationScreenshot(item.id, file).subscribe({
      next: (assignment) => {
        this.toast.success('Скриншот загружен');
        this.selectedAssignment.set(assignment);
        this.actionSaving.set(null);
        this.load();
      },
      error: (err) => {
        this.toast.error('Скриншот не загружен', apiErrorMessage(err, 'Не удалось загрузить файл'));
        this.actionSaving.set(null);
      }
    });
    input.value = '';
  }

  submitProblem(item: PerformerAssignment): void {
    if (this.problemForm.invalid) {
      this.problemForm.markAllAsTouched();
      return;
    }
    const raw = this.problemForm.getRawValue();
    this.runAction(`problem-${item.id}`, this.api.reportProblem(item.id, { comment: raw.comment.trim() }), 'Проблема передана менеджеру');
  }

  canPublish(item: PerformerAssignment): boolean {
    if (!item.publishAvailableAt) {
      return true;
    }
    return new Date(item.publishAvailableAt).getTime() <= Date.now();
  }

  private mobileSectionTone(section: SectionKey): MobileStatusItem['tone'] {
    return {
      offers: 'yellow',
      active: 'teal',
      waitingPublication: 'violet',
      published: 'green',
      paid: 'blue'
    }[section] as MobileStatusItem['tone'];
  }

  private filterAssignments(items: PerformerAssignment[]): PerformerAssignment[] {
    const keyword = this.normalize(this.keyword());
    if (!keyword) {
      return items;
    }
    return items.filter((item) => this.assignmentSearchText(item).includes(keyword));
  }

  private assignmentSearchText(item: PerformerAssignment): string {
    return [
      item.id,
      item.orderId,
      item.reviewId,
      item.companyTitle,
      item.filialTitle,
      item.cityTitle,
      item.platform,
      item.status,
      item.draftText,
      item.finalText,
      item.instruction,
      item.publicationUrl
    ].map((part) => this.normalize(part)).join(' ');
  }

  private normalize(value: unknown): string {
    return String(value ?? '').trim().toLowerCase();
  }

  private runAction<T>(key: string, request: { subscribe: Function }, success: string): void {
    this.actionSaving.set(key);
    request.subscribe({
      next: () => {
        this.toast.success(success);
        this.actionSaving.set(null);
        this.selectedAssignment.set(null);
        this.load();
      },
      error: (err: unknown) => {
        const message = apiErrorMessage(err, 'Действие не выполнено');
        this.toast.error('Ошибка', message);
        this.actionSaving.set(null);
      }
    });
  }
}
