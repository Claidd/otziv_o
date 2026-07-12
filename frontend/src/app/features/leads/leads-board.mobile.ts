import type { LeadBucketKey } from '../../core/leads.api';

export type LeadMobileBucket = {
  key: LeadBucketKey;
  label: string;
  icon: string;
  tone: 'blue' | 'green' | 'teal' | 'yellow' | 'pink' | 'gray';
};

export const LEAD_MOBILE_BUCKETS: readonly LeadMobileBucket[] = [
  { key: 'newLeads', label: 'Новые', icon: 'fiber_new', tone: 'yellow' },
  { key: 'toWork', label: 'В работу', icon: 'assignment_ind', tone: 'green' },
  { key: 'inWork', label: 'В работе', icon: 'work_history', tone: 'teal' },
  { key: 'send', label: 'Напомнить', icon: 'outgoing_mail', tone: 'pink' },
  { key: 'archive', label: 'Архив', icon: 'archive', tone: 'gray' },
  { key: 'all', label: 'Все', icon: 'dataset', tone: 'blue' }
];

export function defaultLeadMobileBucket(
  canSeeAll: boolean,
  visibleBuckets: readonly LeadMobileBucket[]
): LeadBucketKey {
  return canSeeAll ? 'all' : visibleBuckets[0]?.key ?? 'newLeads';
}
