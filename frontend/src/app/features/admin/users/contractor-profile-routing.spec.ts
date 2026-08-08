import { describe, expect, it } from 'vitest';
import {
  canEditContractorProfileRouting,
  contractorProfileRoutingPresentation
} from './contractor-profile-routing';

describe('contractor profile routing settings', () => {
  it('explains the specialist-to-manager-to-owner fallback when disabled', () => {
    const state = contractorProfileRoutingPresentation('SPECIALIST', false, true, true);

    expect(state.label).toBe('Выключено');
    expect(state.tone).toBe('disabled');
    expect(state.detail).toContain('проверит менеджера заказа');
    expect(state.detail).toContain('выберет владельца');
  });

  it('explains the direct manager-to-owner fallback when disabled', () => {
    const state = contractorProfileRoutingPresentation('MANAGER', false, true, true);

    expect(state.label).toBe('Выключено');
    expect(state.detail).toContain('Менеджер пропускается');
    expect(state.detail).toContain('реквизиты владельца');
  });

  it('marks an edited switch as unsaved without claiming it is already effective', () => {
    const state = contractorProfileRoutingPresentation('SPECIALIST', false, true, true, true);

    expect(state.label).toBe('Не сохранено');
    expect(state.tone).toBe('pending');
    expect(state.detail).toContain('проверит менеджера заказа');
  });

  it('distinguishes saved personal permission from disabled global routing', () => {
    const state = contractorProfileRoutingPresentation('MANAGER', true, true, false);

    expect(state.label).toBe('Допуск сохранён');
    expect(state.tone).toBe('unavailable');
    expect(state.detail).toContain('Глобальная выдача реквизитов сейчас не активна');
  });

  it('keeps routing unavailable until the profile participates in test calculation', () => {
    const state = contractorProfileRoutingPresentation('SPECIALIST', false, false, true);

    expect(state.label).toBe('Недоступно');
    expect(state.detail).toContain('включите участие профиля в тестовом расчёте');
  });

  it('allows an authorized profile editor only for an enabled profile while no save is running', () => {
    expect(canEditContractorProfileRouting(true, true, false, false)).toBe(true);
    expect(canEditContractorProfileRouting(false, true, false, false)).toBe(false);
    expect(canEditContractorProfileRouting(true, false, false, false)).toBe(false);
    expect(canEditContractorProfileRouting(true, true, false, true)).toBe(false);
  });

  it('allows a stale personal permission to be switched off even when the base profile is disabled', () => {
    expect(canEditContractorProfileRouting(true, false, true, false)).toBe(true);

    const state = contractorProfileRoutingPresentation('SPECIALIST', true, false, true);
    expect(state.label).toBe('Требуется выключить');
    expect(state.detail).toContain('Выключите его и сохраните профиль');
  });
});
