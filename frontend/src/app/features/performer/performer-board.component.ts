import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';
import { apiErrorMessage } from '../../shared/api-error-message';
import { LoadErrorCardComponent } from '../../shared/load-error-card.component';
import { ToastService } from '../../shared/toast.service';
import { PerformerApi, PerformerAssignment, PerformerBoard } from '../../core/performer.api';

type SectionKey = 'offers' | 'active' | 'waitingPublication' | 'published' | 'paid';

@Component({
  selector: 'app-performer-board',
  imports: [AdminLayoutComponent, LoadErrorCardComponent, ReactiveFormsModule],
  templateUrl: './performer-board.component.html',
  styleUrl: './performer-board.component.scss'
})
export class PerformerBoardComponent implements OnInit {
  private readonly api = inject(PerformerApi);
  private readonly fb = inject(FormBuilder);
  private readonly toast = inject(ToastService);

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
  readonly activeSection = signal<SectionKey>('offers');
  readonly selectedAssignment = signal<PerformerAssignment | null>(null);

  readonly publishForm = this.fb.nonNullable.group({
    finalText: ['', [Validators.required, Validators.minLength(10)]],
    publicationUrl: [''],
    comment: ['']
  });

  readonly problemForm = this.fb.nonNullable.group({
    comment: ['', [Validators.required, Validators.minLength(5)]]
  });

  readonly sections = computed(() => [
    { key: 'offers' as const, label: 'Новые', icon: 'notifications_active', count: this.board().offers.length },
    { key: 'active' as const, label: 'Активные', icon: 'directions_walk', count: this.board().active.length },
    { key: 'waitingPublication' as const, label: 'Публикация', icon: 'schedule', count: this.board().waitingPublication.length },
    { key: 'published' as const, label: 'Опубликованы', icon: 'published_with_changes', count: this.board().published.length },
    { key: 'paid' as const, label: 'Оплачены', icon: 'payments', count: this.board().paid.length }
  ]);

  readonly currentItems = computed(() => this.board()[this.activeSection()]);

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
        this.error.set(apiErrorMessage(err, 'Не удалось загрузить задания'));
        this.loading.set(false);
      }
    });
  }

  setSection(section: SectionKey): void {
    this.activeSection.set(section);
    this.selectedAssignment.set(null);
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
