/**
 * Field-level form drafts.
 *
 * A draft stores only the fields the user changed together with the server
 * values they were edited from. Restoring it on top of freshly loaded server
 * data therefore never reverts fields the user did not touch. When another
 * user changed one of the edited fields in the meantime, the unsaved value is
 * kept in the form (typed input is never lost) but the field is reported as a
 * conflict so the caller can warn before the user saves over it.
 */
export type FieldDraft<T extends object> = {
  kind: 'field-draft';
  version: 1;
  /** Values the user entered. */
  changes: Partial<T>;
  /** Server values the changed fields were edited from. */
  base: Partial<T>;
};

export type FieldDraftConflict<T extends object> = {
  field: keyof T;
  /** The value another user saved after the draft was started. */
  serverValue: T[keyof T];
};

export type RestoredFieldDraft<T extends object> = {
  draft: T;
  restoredFields: (keyof T)[];
  conflicts: FieldDraftConflict<T>[];
};

/** Returns the changed fields of draft relative to source, or null when nothing changed. */
export function fieldDraftOf<T extends object>(source: T, draft: T): FieldDraft<T> | null {
  const changes: Partial<T> = {};
  const base: Partial<T> = {};
  let changed = false;
  for (const field of Object.keys(source) as (keyof T)[]) {
    if (!sameDraftValue(source[field], draft[field])) {
      changes[field] = normalized(draft[field]) as T[keyof T];
      base[field] = normalized(source[field]) as T[keyof T];
      changed = true;
    }
  }
  return changed ? { kind: 'field-draft', version: 1, changes, base } : null;
}

/**
 * Applies a stored draft on top of fresh server data. Only fields the user
 * changed are applied; a changed field whose server value moved away from the
 * stored base is applied too, but listed in conflicts. Older whole-form drafts
 * carry no base values and are ignored rather than trusted.
 */
export function restoreFieldDraft<T extends object>(source: T, stored: unknown): RestoredFieldDraft<T> {
  const draft = { ...source };
  const restoredFields: (keyof T)[] = [];
  const conflicts: FieldDraftConflict<T>[] = [];
  if (!isFieldDraft<T>(stored)) {
    return { draft, restoredFields, conflicts };
  }

  for (const name of Object.keys(stored.changes)) {
    if (!Object.prototype.hasOwnProperty.call(source, name)) {
      continue;
    }
    const field = name as keyof T;
    const draftValue = stored.changes[field];
    if (sameDraftValue(draftValue, source[field])) {
      // The server already holds the value the user wanted.
      continue;
    }
    if (!sameDraftValue(stored.base[field], source[field])) {
      conflicts.push({ field, serverValue: source[field] });
    }
    draft[field] = draftValue as T[keyof T];
    restoredFields.push(field);
  }
  return { draft, restoredFields, conflicts };
}

export function isFieldDraft<T extends object>(value: unknown): value is FieldDraft<T> {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const candidate = value as Partial<FieldDraft<T>>;
  return candidate.kind === 'field-draft'
    && candidate.version === 1
    && isPlainRecord(candidate.changes)
    && isPlainRecord(candidate.base);
}

/** Human-readable conflict summary, e.g. «Комментарий» (сейчас сохранено: «…»). */
export function describeFieldDraftConflicts<T extends object>(
  conflicts: FieldDraftConflict<T>[],
  labels: Partial<Record<keyof T, string>>
): string {
  return conflicts
    .map(({ field, serverValue }) => {
      const label = labels[field] ?? String(field);
      const preview = valuePreview(serverValue);
      return preview ? `«${label}» (сейчас сохранено: ${preview})` : `«${label}»`;
    })
    .join(', ');
}

function valuePreview(value: unknown): string {
  if (typeof value === 'string') {
    const text = value.replace(/\s+/g, ' ').trim();
    if (!text) {
      return 'пусто';
    }
    return `«${text.length > 80 ? `${text.slice(0, 80)}…` : text}»`;
  }
  if (typeof value === 'boolean') {
    return value ? 'включено' : 'выключено';
  }
  // Identifiers (status, manager, specialist…) mean nothing to the reader.
  return '';
}

function sameDraftValue(left: unknown, right: unknown): boolean {
  const a = normalized(left);
  const b = normalized(right);
  if (Object.is(a, b)) {
    return true;
  }
  if (typeof a === 'object' && a !== null && typeof b === 'object' && b !== null) {
    return JSON.stringify(a) === JSON.stringify(b);
  }
  return false;
}

/** JSON storage drops undefined; treat it as null on both sides. */
function normalized(value: unknown): unknown {
  return value === undefined ? null : value;
}

function isPlainRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
