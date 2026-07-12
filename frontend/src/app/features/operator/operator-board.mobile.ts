import type { OperatorBoardSection } from '../../core/operator.api';

export type OperatorMobileSection = {
  key: OperatorBoardSection;
  label: string;
  icon: string;
  tone: 'green' | 'blue';
};

export const OPERATOR_MOBILE_SECTIONS: readonly OperatorMobileSection[] = [
  { key: 'queue', label: 'К выдаче', icon: 'support_agent', tone: 'green' },
  { key: 'sent', label: 'Отправленные', icon: 'outgoing_mail', tone: 'blue' }
];

export const DEFAULT_OPERATOR_MOBILE_SECTION: OperatorBoardSection = 'queue';
