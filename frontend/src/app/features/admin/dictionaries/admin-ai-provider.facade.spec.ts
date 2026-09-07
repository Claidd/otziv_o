import { of, Subject } from 'rxjs';
import type { ReputationAiStatus } from '../../../core/reputation-ai.api';
import { AdminAiProviderFacade } from './admin-ai-provider.facade';

const status = (aiProvider: 'openai' | 'deepseek') => ({ aiProvider }) as ReputationAiStatus;

describe('dictionary AI provider tab', () => {
  it('cancels an old read before selecting a provider so it cannot revert the selection', () => {
    const read = new Subject<ReputationAiStatus>();
    const api = { status: vi.fn().mockReturnValue(read), selectProvider: vi.fn().mockReturnValue(of(status('openai'))) };
    const toast = { success: vi.fn(), error: vi.fn() };
    const facade = new AdminAiProviderFacade({ api, toast, isActive: () => true });
    facade.loadAiProviderStatus();
    facade.selectAiProvider('openai');
    expect(read.observed).toBe(false);
    read.next(status('deepseek'));
    expect(facade.aiProviderStatus()?.aiProvider).toBe('openai');
    expect(facade.loading()).toBe(false);
    facade.destroy();
  });

  it('keeps the submitted write alive while rejecting duplicate selection and stale completion', () => {
    const write = new Subject<ReputationAiStatus>();
    const api = { status: vi.fn(), selectProvider: vi.fn().mockReturnValue(write) };
    const toast = { success: vi.fn(), error: vi.fn() };
    let active = true;
    const facade = new AdminAiProviderFacade({ api, toast, isActive: () => active });
    facade.selectAiProvider('openai');
    active = false; facade.deactivate();
    active = true; facade.selectAiProvider('deepseek');
    expect(api.selectProvider).toHaveBeenCalledOnce();
    expect(write.observed).toBe(true);
    write.next(status('openai')); write.complete();
    expect(facade.aiProviderStatus()).toBeNull();
    expect(facade.switchingAiProvider()).toBe(false);
    expect(toast.success).not.toHaveBeenCalled();
    facade.destroy();
  });

  it('does not show a canceled request error on another tab', () => {
    const read = new Subject<ReputationAiStatus>();
    const toast = { success: vi.fn(), error: vi.fn() };
    const facade = new AdminAiProviderFacade({ api: { status: () => read, selectProvider: vi.fn() }, toast, isActive: () => true });
    facade.loadAiProviderStatus();
    facade.deactivate();
    read.error(new Error('old error'));
    expect(facade.aiProviderError()).toBeNull();
    expect(toast.error).not.toHaveBeenCalled();
    facade.destroy();
  });
});
