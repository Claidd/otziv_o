import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import ts from 'typescript';

const featureRoot = new URL('../src/app/features/', import.meta.url);
const facadeFiles = ['manager', 'order-details'].flatMap(folder => fs.readdirSync(new URL(`${folder}/`, featureRoot)).filter(name => name.endsWith('.facade.ts')).map(name => new URL(`${folder}/${name}`, featureRoot)));

test('editor facades stay page-local and use feature clients instead of broad transport or HTTP', () => {
  for (const file of facadeFiles) {
    const source = fs.readFileSync(file, 'utf8'); const tree = ts.createSourceFile(file.pathname, source, ts.ScriptTarget.Latest, true);
    assert.doesNotMatch(source, /providedIn\s*:\s*['"]root['"]/, file.pathname);
    for (const declaration of tree.statements.filter(ts.isImportDeclaration)) {
      if (declaration.importClause?.isTypeOnly) continue;
      const bindings = declaration.importClause?.namedBindings;
      if (!bindings || !ts.isNamedImports(bindings)) continue;
      for (const item of bindings.elements) {
        if (!item.isTypeOnly) assert.ok(!['ApiService', 'HttpClient'].includes(item.name.text), `${file.pathname} imports broad transport ${item.name.text}`);
      }
    }
  }
});

test('feature API transports do not depend on screens or UI state', () => {
  const core = new URL('../src/app/core/', import.meta.url);
  for (const name of fs.readdirSync(core).filter(name => name.endsWith('.api.ts'))) {
    const source = fs.readFileSync(new URL(name, core), 'utf8');
    assert.doesNotMatch(source, /from\s+['"][^'"]*(?:features|@ionic|@capacitor)[^'"]*['"]|\b(?:signal|computed)\s*\(/, name);
    assert.doesNotMatch(source, /\b(?:fetch|localStorage|sessionStorage)\s*[.(]/, name);
  }
});

test('manager control transport and its screen models cannot depend on the compatibility API', () => {
  for (const name of ['manager-control.api.ts', 'manager-control.models.ts']) {
    const source = fs.readFileSync(new URL('../src/app/core/' + name, import.meta.url), 'utf8');
    assert.doesNotMatch(source, /from\s+['"]\.\/api\.service['"]/);
  }
});

test('the compatibility API cannot regain HTTP ownership of migrated manager, order or payment domains', () => {
  const source = fs.readFileSync(new URL('../src/app/core/api.service.ts', import.meta.url), 'utf8');
  const tree = ts.createSourceFile('api.service.ts', source, ts.ScriptTarget.Latest, true);
  const api = tree.statements.find(n => ts.isClassDeclaration(n) && n.name?.text === 'ApiService');
  for (const method of api.members.filter(ts.isMethodDeclaration)) {
    const body = method.body?.getText(tree) ?? '';
    if (!body.includes('this.http.')) continue;
    assert.doesNotMatch(body, /\/api\/(?:manager(?:\/|['"`])|admin\/(?:payments|manager-control|manager-daily-summary)|cabinet\/(?:payment-profile|manual-payment-tasks)|payments\/public)/,
      `Move ${method.name.getText(tree)} to its feature client; keep only a compatibility delegate here`);
  }
});

test('production callers use the owner of every migrated API operation directly', () => {
  const app = new URL('../src/app/', import.meta.url);
  const legacySource = fs.readFileSync(new URL('core/api.service.ts', app), 'utf8');
  const legacyTree = ts.createSourceFile('api.service.ts', legacySource, ts.ScriptTarget.Latest, true);
  const legacyApi = legacyTree.statements.find(n => ts.isClassDeclaration(n) && n.name?.text === 'ApiService');
  const migrated = new Set(legacyApi.members.filter(ts.isMethodDeclaration)
    .filter(n => /^\{\s*return this\.\w+Api\.\w+\(/.test(n.body?.getText(legacyTree) ?? '')).map(n => n.name.getText(legacyTree)));
  for (const name of fs.readdirSync(app, { recursive: true }).filter(n => n.endsWith('.ts') && !n.endsWith('.spec.ts') && !n.endsWith('api.service.ts'))) {
    const source = fs.readFileSync(new URL(name.replaceAll('\\', '/'), app), 'utf8');
    const tree = ts.createSourceFile(name, source, ts.ScriptTarget.Latest, true);
    for (const owner of tree.statements.filter(ts.isClassDeclaration)) {
      const broadFields = new Set();
      for (const member of owner.members) {
        if (ts.isConstructorDeclaration(member)) for (const parameter of member.parameters) {
          if (parameter.type?.getText(tree) === 'ApiService') broadFields.add(parameter.name.getText(tree));
        }
        if (ts.isPropertyDeclaration(member) && member.initializer?.getText(tree) === 'inject(ApiService)') broadFields.add(member.name.getText(tree));
      }
      function check(node) {
        if (ts.isPropertyAccessExpression(node) && ts.isPropertyAccessExpression(node.expression)
          && node.expression.expression.kind === ts.SyntaxKind.ThisKeyword
          && broadFields.has(node.expression.name.text) && migrated.has(node.name.text)) {
          assert.fail(`${name}: ${node.name.text} belongs to an extracted feature API`);
        }
        ts.forEachChild(node, check);
      }
      check(owner);
    }
  }
});

test('public payment contracts belong to the generated shared boundary, not the compatibility facade', () => {
  const feature = fs.readFileSync(new URL('../src/app/core/public-payments.api.ts', import.meta.url), 'utf8');
  assert.doesNotMatch(feature, /from\s+['"]\.\/api\.service['"]/);
  assert.match(feature, /PublicPaymentInitRequestInput/);
  assert.match(feature, /PublicCommonInvoiceResponseOutput/);
  for (const file of ['../src/app/core/api.service.ts', '../../frontend/src/app/core/payments.api.ts']) {
    const source = fs.readFileSync(new URL(file, import.meta.url), 'utf8');
    const tree = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true);
    const names = new Set(['PublicPaymentInitResponse', 'PublicSbpBank', 'PublicCommonInvoice', 'PublicCommonInvoiceOrder']);
    assert.equal(tree.statements.some(node => ts.isInterfaceDeclaration(node) && names.has(node.name.text)), false, file);
    assert.match(source, /export type \{[^}]*PublicCommonInvoice[^}]*\} from '@otziv\/client-common\/public-payments'/);
  }
});
