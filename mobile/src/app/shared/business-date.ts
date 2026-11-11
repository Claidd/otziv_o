export const BUSINESS_TIME_ZONE = 'Asia/Irkutsk';

type BusinessDatePart = 'year' | 'month' | 'day' | 'hour' | 'minute';

const BUSINESS_DATE_TIME_FORMATTER = new Intl.DateTimeFormat('en-CA', {
  timeZone: BUSINESS_TIME_ZONE,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23'
});

function parts(now: Date): Record<BusinessDatePart, string> {
  const values = Object.fromEntries(
    BUSINESS_DATE_TIME_FORMATTER.formatToParts(now)
      .filter((part) => part.type !== 'literal')
      .map((part) => [part.type, part.value])
  ) as Partial<Record<BusinessDatePart, string>>;
  return {
    year: values.year ?? '0000',
    month: values.month ?? '00',
    day: values.day ?? '00',
    hour: values.hour ?? '00',
    minute: values.minute ?? '00'
  };
}

export function businessDateIso(now = new Date()): string {
  const value = parts(now);
  return `${value.year}-${value.month}-${value.day}`;
}

export function businessDateTimeInput(now = new Date()): string {
  const value = parts(now);
  return `${value.year}-${value.month}-${value.day}T${value.hour}:${value.minute}`;
}

/**
 * The business-zone wall clock with seconds and milliseconds, for a server LocalDateTime that the
 * server reads in the business zone. The device's own time zone must not shift it: a manager in
 * another city would record a receipt hours earlier, and one east of Irkutsk would be refused
 * («Время поступления не может быть в будущем»). Zone offsets are whole minutes, so seconds and
 * milliseconds are the same in every zone.
 */
export function businessDateTimeStamp(now = new Date()): string {
  const seconds = String(now.getUTCSeconds()).padStart(2, '0');
  const milliseconds = String(now.getUTCMilliseconds()).padStart(3, '0');
  return `${businessDateTimeInput(now)}:${seconds}.${milliseconds}`;
}

export function millisecondsUntilNextBusinessDay(now = new Date()): number {
  const currentDate = businessDateIso(now);
  const start = now.getTime();
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
