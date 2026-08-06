import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const source = readFileSync(
  new URL('../src/app/shared/mobile-review-field-editor.component.ts', import.meta.url),
  'utf8'
);

const sliceBetween = (startMarker, endMarker) => {
  const start = source.indexOf(startMarker);
  const end = source.indexOf(endMarker, start + startMarker.length);
  assert.ok(start >= 0, `missing start marker: ${startMarker}`);
  assert.ok(end > start, `missing end marker after: ${startMarker}`);
  return source.slice(start, end);
};

test('review field editor suppresses preview refocus after modal dismissal', () => {
  assert.match(source, /export class MobileReviewFieldEditorComponent implements OnChanges/);
  assert.match(source, /\(focus\)="requestStart\(\$event\)"/);
  assert.match(source, /\(click\)="requestStart\(\$event\)"/);
  assert.match(source, /\(ngSubmit\)="requestSave\(\$event\)"/);
  assert.match(source, /\(click\)="requestCancel\(\$event\)"/);

  const changesBlock = sliceBetween('ngOnChanges(', '\n  requestStart(');
  assert.match(changesBlock, /previousValue === true/);
  assert.match(changesBlock, /currentValue === false/);
  assert.match(changesBlock, /markEditorDismissed\(\)/);

  const startBlock = sliceBetween('requestStart(', '\n  requestCancel(');
  assert.match(startBlock, /this\.editing \|\| this\.readOnly \|\| this\.disabled/);
  assert.match(startBlock, /isStartSuppressed\(\)/);
  assert.match(startBlock, /preventDefault\(\)/);
  assert.match(startBlock, /blurEventTarget\(event\)/);

  const dismissBlock = sliceBetween('handleDismiss(', '\n  focusEditor(');
  assert.match(dismissBlock, /markEditorDismissed\(\)/);
  assert.match(dismissBlock, /this\.editing && !this\.disabled/);
});
