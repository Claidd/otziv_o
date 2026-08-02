export const BUSINESS_TIME_ZONE = 'Asia/Irkutsk';

type DatePart = 'year' | 'month' | 'day' | 'hour' | 'minute';

const dateFormatter = new Intl.DateTimeFormat('en-US', {
  timeZone: BUSINESS_TIME_ZONE,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit'
});

const dateTimeFormatter = new Intl.DateTimeFormat('en-US', {
  timeZone: BUSINESS_TIME_ZONE,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23'
});

export function businessDateIso(date = new Date()): string {
  const parts = dateParts(dateFormatter, date);
  return `${parts.year}-${parts.month}-${parts.day}`;
}

export function businessDateTimeInput(date = new Date()): string {
  const parts = dateParts(dateTimeFormatter, date);
  return `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}`;
}

export function millisecondsUntilNextBusinessDay(date = new Date()): number {
  const currentDate = businessDateIso(date);
  const start = date.getTime();
  let low = start;
  let high = start + 36 * 60 * 60 * 1000;
  while (businessDateIso(new Date(high)) === currentDate) {
    high += 12 * 60 * 60 * 1000;
  }
  while (high - low > 1000) {
    const middle = Math.floor((low + high) / 2);
    if (businessDateIso(new Date(middle)) === currentDate) {
      low = middle;
    } else {
      high = middle;
    }
  }
  return Math.max(1000, high - start);
}

function dateParts(formatter: Intl.DateTimeFormat, date: Date): Record<DatePart, string> {
  const result: Record<DatePart, string> = {
    year: '',
    month: '',
    day: '',
    hour: '00',
    minute: '00'
  };
  for (const part of formatter.formatToParts(date)) {
    if (part.type in result) {
      result[part.type as DatePart] = part.value;
    }
  }
  return result;
}
