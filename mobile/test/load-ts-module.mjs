import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';

const projectRoot = path.resolve(fileURLToPath(new URL('..', import.meta.url)));
const moduleCache = new Map();

export function loadTsModule(relativePath) {
  const filename = path.resolve(projectRoot, relativePath);
  if (moduleCache.has(filename)) {
    return moduleCache.get(filename).exports;
  }

  const source = fs.readFileSync(filename, 'utf8');
  const output = ts.transpileModule(source, {
    compilerOptions: {
      module: ts.ModuleKind.CommonJS,
      target: ts.ScriptTarget.ES2022
    },
    fileName: filename
  }).outputText;

  const module = { exports: {} };
  moduleCache.set(filename, module);
  const require = (specifier) => {
    if (specifier.startsWith('.')) {
      const resolved = path.resolve(path.dirname(filename), specifier);
      const withExtension = fs.existsSync(resolved) ? resolved : `${resolved}.ts`;
      const nextRelative = path.relative(projectRoot, withExtension).replaceAll(path.sep, '/');
      return loadTsModule(nextRelative);
    }
    throw new Error(`Test loader cannot import "${specifier}" from ${relativePath}`);
  };
  const wrapper = vm.runInNewContext(
    `(function(exports, require, module, __filename, __dirname) { ${output}\n})`,
    { console }
  );

  wrapper(module.exports, require, module, filename, path.dirname(filename));
  return module.exports;
}
