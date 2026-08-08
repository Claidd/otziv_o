import type { ContractorRole } from '../../../core/admin-users.api';

export type ContractorProfileRoutingTone = 'enabled' | 'disabled' | 'unavailable' | 'pending';

export interface ContractorProfileRoutingPresentation {
  label: string;
  detail: string;
  tone: ContractorProfileRoutingTone;
}

export function contractorProfileRoutingPresentation(
  role: ContractorRole,
  routingEnabled: boolean,
  profileEnabled: boolean,
  globalRoutingLive: boolean,
  savedRoutingEnabled = routingEnabled
): ContractorProfileRoutingPresentation {
  const changed = routingEnabled !== savedRoutingEnabled;

  if (!profileEnabled) {
    if (changed && !routingEnabled) {
      return {
        label: 'Не сохранено',
        detail: 'Сохраните профиль, чтобы окончательно исключить эти реквизиты из новых счетов.',
        tone: 'pending'
      };
    }
    if (routingEnabled) {
      return {
        label: 'Требуется выключить',
        detail: 'Профиль не участвует в расчёте, но персональный допуск остался включён. Выключите его и сохраните профиль.',
        tone: 'pending'
      };
    }
    return {
      label: 'Недоступно',
      detail: 'Сначала включите участие профиля в тестовом расчёте и сохраните профиль.',
      tone: 'unavailable'
    };
  }

  if (changed) {
    return {
      label: 'Не сохранено',
      detail: routingEnabled
        ? 'После сохранения реквизиты смогут участвовать в выборе получателя новых счетов.'
        : disabledRoutingDetail(role),
      tone: 'pending'
    };
  }

  if (!routingEnabled) {
    return {
      label: 'Выключено',
      detail: disabledRoutingDetail(role),
      tone: 'disabled'
    };
  }

  if (!globalRoutingLive) {
    return {
      label: 'Допуск сохранён',
      detail: 'Глобальная выдача реквизитов сейчас не активна. Допуск начнёт действовать только после её включения.',
      tone: 'unavailable'
    };
  }

  return {
    label: 'Включено',
    detail: 'Реквизиты могут попасть в новый счёт, если доступного остатка достаточно для всей суммы счёта.',
    tone: 'enabled'
  };
}

export function canEditContractorProfileRouting(
  authorized: boolean,
  profileEnabled: boolean,
  routingEnabled: boolean,
  saving: boolean
): boolean {
  return authorized && (profileEnabled || routingEnabled) && !saving;
}

function disabledRoutingDetail(role: ContractorRole): string {
  return role === 'SPECIALIST'
    ? 'Специалист пропускается: система проверит менеджера заказа, а затем при необходимости выберет владельца.'
    : 'Менеджер пропускается: для нового счёта будут выбраны реквизиты владельца.';
}
