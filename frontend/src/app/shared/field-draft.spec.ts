import { describeFieldDraftConflicts, fieldDraftOf, restoreFieldDraft } from './field-draft';

type Form = { title: string; comment: string; statusId: number | null; active: boolean; site?: string };

const server: Form = { title: 'Company', comment: 'note', statusId: 1, active: true };

describe('field drafts', () => {
  it('records nothing when the form equals the server data', () => {
    expect(fieldDraftOf(server, { ...server })).toBeNull();
  });

  it('records only changed fields and their server base values', () => {
    expect(fieldDraftOf(server, { ...server, comment: 'mine', active: false })).toEqual({
      kind: 'field-draft',
      version: 1,
      changes: { comment: 'mine', active: false },
      base: { comment: 'note', active: true }
    });
  });

  it('survives JSON storage of undefined server values', () => {
    const stored = JSON.parse(JSON.stringify(fieldDraftOf<Form>({ ...server, site: undefined }, { ...server, site: 'x.example' })));

    const restored = restoreFieldDraft<Form>({ ...server, site: undefined }, stored);

    expect(restored.draft.site).toBe('x.example');
    expect(restored.conflicts).toEqual([]);
  });

  it('applies changes over fresh data and keeps fields changed by others', () => {
    const stored = JSON.parse(JSON.stringify(fieldDraftOf(server, { ...server, comment: 'mine' })));
    const fresh: Form = { ...server, title: 'Renamed', statusId: 2 };

    const restored = restoreFieldDraft(fresh, stored);

    expect(restored.draft).toEqual({ ...fresh, comment: 'mine' });
    expect(restored.restoredFields).toEqual(['comment']);
    expect(restored.conflicts).toEqual([]);
  });

  it('keeps the unsaved value but reports a field another user changed too', () => {
    const stored = JSON.parse(JSON.stringify(fieldDraftOf(server, { ...server, comment: 'mine', statusId: 3 })));
    const fresh: Form = { ...server, comment: 'theirs', statusId: 2 };

    const restored = restoreFieldDraft(fresh, stored);

    expect(restored.draft).toEqual({ ...fresh, comment: 'mine', statusId: 3 });
    expect(restored.conflicts).toEqual([
      { field: 'comment', serverValue: 'theirs' },
      { field: 'statusId', serverValue: 2 }
    ]);
    expect(describeFieldDraftConflicts(restored.conflicts, { comment: 'Комментарий', statusId: 'Статус' }))
      .toBe('«Комментарий» (сейчас сохранено: «theirs»), «Статус»');
  });

  it('does not report a conflict when the server already holds the drafted value', () => {
    const stored = fieldDraftOf(server, { ...server, comment: 'same' });

    const restored = restoreFieldDraft({ ...server, comment: 'same' }, stored);

    expect(restored.restoredFields).toEqual([]);
    expect(restored.conflicts).toEqual([]);
  });

  it('ignores whole-form legacy drafts and unknown fields', () => {
    expect(restoreFieldDraft(server, { ...server, title: 'Old' }).draft).toEqual(server);
    expect(restoreFieldDraft(server, {
      kind: 'field-draft', version: 1, changes: { removed: 'x' }, base: { removed: 'y' }
    }).draft).toEqual(server);
    expect(restoreFieldDraft(server, null).draft).toEqual(server);
  });
});
