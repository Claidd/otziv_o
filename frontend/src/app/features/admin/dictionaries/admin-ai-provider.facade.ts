import { signal } from '@angular/core';
import { Subscription } from 'rxjs';
import type { ReputationAiApi, ReputationAiProvider, ReputationAiStatus } from '../../../core/reputation-ai.api';
import type { ToastService } from '../../../shared/toast.service';
import { apiErrorMessage } from '../../../shared/api-error-message';

type ProviderDeps = { api: Pick<ReputationAiApi, 'status' | 'selectProvider'>; toast: Pick<ToastService, 'success' | 'error'>; isActive: () => boolean };

/**
 * The checks of manager control that the backend runs only while DeepSeek is the active provider (it compares the provider
 * name): the no-answer check of unanswered messages, the questions and answer checks of manager reports, and the review
 * of performers' risk explanations.
 */
const DEEPSEEK_ONLY_CHECKS = [
  '— «Проверить: ответ не нужен» в неотвеченных сообщениях: менеджеры получат ошибку и не смогут так закрыть карточку;',
  '— вопросы по отчётам менеджеров и проверка ответов на них;',
  '— оценка пояснений исполнителей по рискам: пояснения уйдут менеджеру без оценки;',
  '— AI-разбор в ежедневных сводках по менеджерам и команде: в сводках останутся только цифры.'
];

export class AdminAiProviderFacade {
  private aiProviderLoadEpoch = 0;
  private generation = 0;
  private read: Subscription | null = null;
  private destroyed = false;
  readonly loading = signal(false);
  constructor(private readonly deps: ProviderDeps) {}

  deactivate(): void {
    this.generation += 1;
    this.aiProviderLoadEpoch += 1;
    this.read?.unsubscribe();
    this.read = null;
    this.loading.set(false);
  }

  destroy(): void { this.destroyed = true; this.deactivate(); }

  private current(generation: number): boolean {
    return !this.destroyed && generation === this.generation && this.deps.isActive();
  }

  readonly aiProviderStatus = signal<ReputationAiStatus | null>(null);

  readonly aiProviderError = signal<string | null>(null);

  readonly switchingAiProvider = signal(false);

  loadAiProviderStatus(): void {
    if (!this.current(this.generation) || this.switchingAiProvider()) return;
    this.read?.unsubscribe();
    const requestId = ++this.aiProviderLoadEpoch;
    this.loading.set(true);
    this.aiProviderError.set(null);
    this.read = this.deps.api.status().subscribe({
      next: (status) => {
        if (requestId !== this.aiProviderLoadEpoch || !this.deps.isActive()) {
          return;
        }
        this.aiProviderStatus.set(status);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        if (requestId !== this.aiProviderLoadEpoch || !this.deps.isActive()) {
          return;
        }
        const message = apiErrorMessage(err, 'Не удалось загрузить настройки AI-провайдера');
        this.aiProviderError.set(message);
        this.loading.set(false);
        this.deps.toast.error('AI-провайдер не загрузился', message);
      }
    });
  }

  selectAiProvider(provider: ReputationAiProvider): void {
    if (!this.current(this.generation) || this.switchingAiProvider() || this.aiProviderStatus()?.aiProvider === provider) {
      return;
    }

    const generation = this.generation;
    // The provider is one setting for the whole system: leaving DeepSeek switches its manager-control checks off.
    const leavesDeepSeek = this.aiProviderStatus()?.aiProvider === 'deepseek';
    if (leavesDeepSeek) {
      // Reserved while the question is open, so a second click cannot start another switch.
      this.switchingAiProvider.set(true);
      const confirmed = window.confirm(this.leaveDeepSeekQuestion(provider));
      this.switchingAiProvider.set(false);
      if (!confirmed || !this.current(generation)) {
        return;
      }
    }

    this.read?.unsubscribe();
    this.aiProviderLoadEpoch += 1;
    this.loading.set(false);
    this.switchingAiProvider.set(true);
    this.aiProviderError.set(null);
    this.deps.api.selectProvider(provider).subscribe({
      next: (status) => {
        this.switchingAiProvider.set(false);
        // The provider is one setting for the whole system: its new status is kept even when the tab was left and
        // opened again meanwhile (the read on return was skipped while the switch ran), and the result is reported.
        if (!this.destroyed) this.aiProviderStatus.set(status);
        this.deps.toast.success(
          'AI-провайдер переключён',
          leavesDeepSeek
            ? `${this.aiProviderDisplayName(provider)}. Проверки контроля менеджеров не работают, пока не выбран DeepSeek.`
            : this.aiProviderDisplayName(provider)
        );
      },
      error: (err: unknown) => {
        this.switchingAiProvider.set(false);
        const message = apiErrorMessage(err, 'Не удалось переключить AI-провайдера');
        if (this.current(generation)) this.aiProviderError.set(message);
        this.deps.toast.error('AI-провайдер не переключён', message);
      }
    });
  }

  private leaveDeepSeekQuestion(provider: ReputationAiProvider): string {
    return [
      `Переключить AI на ${this.aiProviderDisplayName(provider)}?`,
      '',
      'Эти проверки контроля менеджеров работают только с DeepSeek и отключатся:',
      ...DEEPSEEK_ONLY_CHECKS,
      '',
      'Чтобы вернуть их, снова выберите DeepSeek.'
    ].join('\n');
  }

  aiProviderDisplayName(provider: ReputationAiProvider): string {
    return provider === 'deepseek' ? 'DeepSeek' : provider === 'yandexgpt' ? 'YandexGPT' : 'OpenAI';
  }

  aiProviderConfigured(provider: ReputationAiProvider, status: ReputationAiStatus): boolean {
    return provider === 'deepseek'
      ? status.deepSeekConfigured
      : provider === 'yandexgpt'
        ? status.yandexGptConfigured
        : status.openAiConfigured;
  }

  aiProviderModel(provider: ReputationAiProvider, status: ReputationAiStatus): string {
    return provider === 'deepseek'
      ? status.deepSeekModel
      : provider === 'yandexgpt'
        ? status.yandexModel
        : status.openAiModel;
  }

  /** The search of the status in words: «local» is the stub the server uses when no public search is set up. */
  aiSearchProviderLabel(code: string | null | undefined): string {
    const value = (code ?? '').trim().toLowerCase();
    if (value === 'yandex') {
      return 'Яндекс Поиск';
    }
    return !value || value === 'local' ? 'не подключён' : (code ?? '');
  }

}
