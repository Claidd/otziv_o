export interface MobilePageLike {
  number?: number;
  pageNumber?: number;
  totalPages?: number;
  first?: boolean;
  last?: boolean;
}

export type MobileSortDirection = 'desc' | 'asc';
export type MobileTone = 'blue' | 'green' | 'yellow' | 'red' | 'teal' | 'violet' | 'pink' | 'gray';

const MOBILE_TONES: readonly MobileTone[] = ['blue', 'green', 'yellow', 'red', 'teal', 'violet', 'pink', 'gray'];

export function mobilePageIndex(page: MobilePageLike, fallbackPageNumber: number): number {
  return (page.number ?? page.pageNumber ?? fallbackPageNumber) + 1;
}

export function mobilePageTotal(page: MobilePageLike): number {
  return page.totalPages || 1;
}

export function mobilePageLabel(page: MobilePageLike, fallbackPageNumber: number): string {
  return `${mobilePageIndex(page, fallbackPageNumber)} / ${mobilePageTotal(page)}`;
}

export function mobilePageIsFirst(page: MobilePageLike, fallbackPageNumber: number): boolean {
  return page.first ?? fallbackPageNumber <= 0;
}

export function mobilePageIsLast(page: MobilePageLike, fallbackPageNumber: number): boolean {
  return page.last ?? mobilePageIndex(page, fallbackPageNumber) >= mobilePageTotal(page);
}

export function mobileSortTitle(
  direction: MobileSortDirection,
  labels: { desc: string; asc: string } = { desc: 'Сначала новые', asc: 'Сначала старые' }
): string {
  return direction === 'desc' ? labels.desc : labels.asc;
}

export function mobileToneFromClass(toneClass: string, fallback: MobileTone = 'blue'): MobileTone {
  const tone = toneClass.replace(/^tone-/, '') as MobileTone;
  return MOBILE_TONES.includes(tone) ? tone : fallback;
}
