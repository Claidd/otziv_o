import { createHash } from 'node:crypto';
import { readdir, readFile, mkdir, writeFile } from 'node:fs/promises';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

// Retain exactly which built application bytes were exercised, alongside JUnit/traces.
export default class BuildEvidenceReporter {
  onBegin(_config, suite) {
    this.projects = [...new Set(suite.allTests().map((test) => test.parent.project()?.name).filter(Boolean))];
  }
  async onEnd(result) {
    const repository = fileURLToPath(new URL('../../', import.meta.url));
    const files = [];
    const applications = this.projects.map((project) => project === 'web' ? 'frontend' : 'mobile');
    for (const application of applications) {
      const root = join(repository, application, 'dist', application, 'browser');
      for (const name of (await readdir(root)).filter((name) => /\.(js|css|html)$/.test(name)).sort()) {
        const path = join(root, name);
        const bytes = await readFile(path);
        files.push({ path: relative(repository, path).replaceAll('\\', '/'), sha256: createHash('sha256').update(bytes).digest('hex'), bytes: bytes.length });
      }
    }
    await mkdir('test-results', { recursive: true });
    await writeFile('test-results/build-manifest.json', JSON.stringify({
      completedAt: new Date().toISOString(), status: result.status, node: process.version,
      projects: this.projects,
      browserRunner: JSON.parse(await readFile(new URL('./package-lock.json', import.meta.url), 'utf8')).packages['node_modules/@playwright/test'].version,
      fixturesOnly: true, files
    }, null, 2) + '\n');
  }
}
