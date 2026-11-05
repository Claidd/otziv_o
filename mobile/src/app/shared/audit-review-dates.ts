/** Calendar arithmetic for the audit report date switch; dates are ISO yyyy-MM-dd. */
export function shiftIsoDate(date: string, days: number): string {
  const [year, month, day] = date.split('-').map(Number);
  return new Date(Date.UTC(year, month - 1, day + days)).toISOString().slice(0, 10);
}

/** The latest report date and the days before it, newest first. */
export function recentIsoDates(latest: string | null, count = 7): string[] {
  return latest ? Array.from({ length: count }, (_, index) => shiftIsoDate(latest, -index)) : [];
}

/** An earlier valid date is kept; the latest date or anything after it means «follow the latest». */
export function earlierIsoDateOrNull(date: string | null, latest: string | null): string | null {
  const value = date && /^\d{4}-\d{2}-\d{2}$/.test(date) ? date : null;
  return value && latest && value < latest ? value : null;
}
