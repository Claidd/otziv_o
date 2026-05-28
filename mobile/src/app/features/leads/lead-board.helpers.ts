import type { LeadBucketKey, LeadItem } from '../../core/api.service';
import { normalizePhoneDigits } from '../../shared/phone-format';

export type LeadMutation = 'send' | 'resend' | 'archive' | 'new' | 'toWork';

export type LeadBucket = {
  key: LeadBucketKey;
  label: string;
  short: string;
  icon: string;
  tone: 'blue' | 'green' | 'teal' | 'yellow' | 'pink' | 'gray';
  adminOnly?: boolean;
};

export type LeadContactLink = {
  key: 'wa' | 'email' | 'site' | 'vk' | 'tg';
  label: string;
  url: string;
  title: string;
};

export type LeadCommentSaveState = 'idle' | 'saving' | 'saved' | 'error';

export const LEAD_BUCKETS: readonly LeadBucket[] = [
  { key: 'newLeads', label: 'Новые', short: 'новые', icon: 'fiber_new', tone: 'yellow' },
  { key: 'toWork', label: 'В работу', short: 'в работу', icon: 'assignment_ind', tone: 'green' },
  { key: 'inWork', label: 'В работе', short: 'в работе', icon: 'work_history', tone: 'teal' },
  { key: 'send', label: 'Напомнить', short: 'напомнить', icon: 'send', tone: 'pink' },
  { key: 'archive', label: 'Архив', short: 'архив', icon: 'archive', tone: 'gray' },
  { key: 'all', label: 'Все', short: 'все', icon: 'dataset', tone: 'blue', adminOnly: true }
];

const LEAD_ACTION_LABELS: Record<LeadMutation, string> = {
  send: 'отправил',
  resend: 'напомнил',
  archive: 'архив',
  new: 'новый',
  toWork: 'передать'
};

const LEAD_ACTION_ICONS: Record<LeadMutation, string> = {
  send: 'send',
  resend: 'notifications_active',
  archive: 'archive',
  new: 'fiber_new',
  toWork: 'swap_horiz'
};

export function leadActionLabel(action: LeadMutation): string {
  return LEAD_ACTION_LABELS[action];
}

export function leadActionIcon(action: LeadMutation): string {
  return LEAD_ACTION_ICONS[action];
}

export function leadStatusActions(lead: LeadItem, canMoveToWork: boolean): LeadMutation[] {
  if (lead.lidStatus === 'Новый') {
    return ['send'];
  }

  return canMoveToWork ? ['toWork', 'resend', 'archive'] : ['resend', 'archive'];
}

export function leadTitle(lead: LeadItem): string {
  return lead.companyName?.trim() || lead.lidStatus || 'Без статуса';
}

export function leadCategoryLine(lead: LeadItem): string {
  return joinLeadParts(lead.companyType, lead.industries);
}

export function leadAddressLine(lead: LeadItem): string {
  return joinLeadParts(lead.region, lead.address);
}

export function leadSocialLine(lead: LeadItem): string {
  return joinLeadParts(firstMultiValue(lead.vkUrl), firstMultiValue(lead.telegramUrl));
}

export function leadContactLinks(lead: LeadItem): LeadContactLink[] {
  const whatsapp = firstMultiValue(lead.whatsappPhones) || lead.telephoneLead;
  const email = firstMultiValue(lead.emails);
  const website = firstMultiValue(lead.websites);
  const vk = firstMultiValue(lead.vkUrl);
  const telegram = firstMultiValue(lead.telegramUrl);

  return [
    whatsapp ? { key: 'wa', label: 'ватсап', url: whatsappLink(whatsapp), title: whatsapp } : null,
    email ? { key: 'email', label: 'почта', url: emailLink(email), title: email } : null,
    website ? { key: 'site', label: 'сайт', url: externalUrl(website), title: website } : null,
    vk ? { key: 'vk', label: 'ВК', url: vkLink(vk), title: vk } : null,
    telegram ? { key: 'tg', label: 'ТГ', url: telegramLink(telegram), title: telegram } : null
  ].filter((link): link is LeadContactLink => link !== null);
}

export function leadTone(lead: LeadItem): 'default' | 'new' | 'work' | 'send' {
  const status = (lead.lidStatus ?? '').trim().toLocaleLowerCase('ru-RU');
  if (status.includes('нов')) {
    return 'new';
  }
  if (status.includes('работ')) {
    return 'work';
  }
  if (status.includes('напом') || status.includes('отправ')) {
    return 'send';
  }
  return 'default';
}

export function normalizedLeadPhone(lead: LeadItem): string {
  return normalizePhoneDigits(lead.telephoneLead);
}

function joinLeadParts(...values: Array<string | undefined | null>): string {
  return values
    .map((value) => value?.trim())
    .filter(Boolean)
    .join(' • ');
}

function firstMultiValue(value?: string | null): string {
  return value
    ?.split(/[,;\r\n]+/)
    .map((part) => part.trim())
    .find(Boolean) ?? '';
}

function emailLink(value: string): string {
  const trimmed = value.trim();
  return trimmed.toLocaleLowerCase('en-US').startsWith('mailto:')
    ? trimmed
    : `mailto:${trimmed}`;
}

function externalUrl(value: string): string {
  const trimmed = value.trim();
  if (/^[a-z][a-z\d+.-]*:/i.test(trimmed)) {
    return trimmed;
  }
  return `https://${trimmed}`;
}

function vkLink(value: string): string {
  const trimmed = value.trim();
  if (/^[a-z][a-z\d+.-]*:/i.test(trimmed) || /^(www\.|vk\.com\/)/i.test(trimmed) || trimmed.includes('.')) {
    return externalUrl(trimmed);
  }
  return `https://vk.com/${trimmed.replace(/^@+|^\/+/g, '')}`;
}

function telegramLink(value: string): string {
  const trimmed = value.trim();
  if (trimmed.startsWith('@')) {
    return `https://t.me/${trimmed.slice(1)}`;
  }
  if (/^[a-z][a-z\d_]{4,31}$/i.test(trimmed)) {
    return `https://t.me/${trimmed}`;
  }
  return externalUrl(trimmed);
}

function whatsappLink(value: string): string {
  const trimmed = value.trim();
  if (/^[a-z][a-z\d+.-]*:/i.test(trimmed) || /^(wa\.me|api\.whatsapp\.com|whatsapp\.com)\//i.test(trimmed)) {
    return externalUrl(trimmed);
  }

  const phone = normalizePhoneDigits(trimmed);
  return phone ? `https://wa.me/${phone}` : externalUrl(trimmed);
}
