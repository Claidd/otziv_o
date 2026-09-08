import { signal } from '@angular/core';
import { Subscription } from 'rxjs';
import type { ReputationAiApi, ReputationAiProvider, ReputationAiStatus } from '../../../core/reputation-ai.api';
import type { ToastService } from '../../../shared/toast.service';
import { apiErrorMessage } from '../../../shared/api-error-message';

type ProviderDeps = { api: Pick<ReputationAiApi, 'status' | 'selectProvider'>; toast: Pick<ToastService, 'success' | 'error'>; isActive: () => boolean };

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

    this.read?.unsubscribe();
    this.aiProviderLoadEpoch += 1;
    this.loading.set(false);
    const generation = this.generation;
    this.switchingAiProvider.set(true);
    this.aiProviderError.set(null);
    this.deps.api.selectProvider(provider).subscribe({
      next: (status) => {
        this.switchingAiProvider.set(false);
        if (!this.current(generation)) return;
        this.aiProviderStatus.set(status);
        this.deps.toast.success('AI-провайдер переключён', this.aiProviderDisplayName(provider));
      },
      error: (err: unknown) => {
        this.switchingAiProvider.set(false);
        if (!this.current(generation)) return;
        const message = apiErrorMessage(err, 'Не удалось переключить AI-провайдера');
        this.aiProviderError.set(message);
        this.deps.toast.error('AI-провайдер не переключён', message);
      }
    });
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

}
