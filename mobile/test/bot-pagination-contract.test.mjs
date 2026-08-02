import assert from 'node:assert/strict';
import test from 'node:test';
import { readFileSync } from 'node:fs';

const read = (relativeUrl) => readFileSync(new URL(relativeUrl, import.meta.url), 'utf8');
const api = read('../src/app/core/api.service.ts');
const dictionaries = read('../src/app/shared/mobile-dictionaries.component.ts');

test('dictionary totals use the dedicated bot count instead of a truncated list', () => {
  assert.match(api, /accounts:\s*this\.http\.get<BotCountResponse>\(this\.apiUrl\('\/api\/admin\/bots\/count'\)\)/);
  assert.match(api, /count:\s*response\.accounts\.count/);
});

test('mobile bot management paginates list data and loads authorized detail before editing', () => {
  assert.match(api, /getAdminBots\(keyword = '', page = 0, size = 50\)/);
  assert.match(api, /params:\s*this\.keywordParams\(keyword\)[\s\S]*\.set\('page', String\(page\)\)[\s\S]*\.set\('size', String\(size\)\)/);
  assert.match(dictionaries, /getAdminBots\(keyword, this\.botPage\(\), this\.botPageSize\(\)\)/);
  assert.match(dictionaries, /getAdminBot\(bot\.id\)/);
});
