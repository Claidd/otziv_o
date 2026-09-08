import { execFileSync } from 'node:child_process';
import { appendFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

export function auditNeeded(paths, event, usableBase = true) {
  if (!usableBase || !['push', 'pull_request'].includes(event)) return true;
  return paths.some(path => /(^|\/)(package(-lock)?\.json|\.npmrc|pom\.xml)$/.test(path)
    || /^(shared\/|backend\/(?:\.mvn|build-support)\/|\.github\/workflows\/|infrastructure\/scripts\/security\/dependency-)/.test(path)
    || path === 'infrastructure/runtime-security/maven-false-positives.xml');
}
export function gatePassed(audit, changes, npm, maven) {
  return changes === 'success' && ((audit === 'true' && npm === 'success' && maven === 'success')
    || (audit === 'false' && npm === 'skipped' && maven === 'skipped'));
}
if (process.argv[1] === fileURLToPath(import.meta.url)) {
  if (process.argv[2] === 'gate') {
    if (!gatePassed(process.env.AUDIT_NEEDED, process.env.CHANGES_RESULT, process.env.NPM_RESULT, process.env.MAVEN_RESULT))
      process.exitCode = 1;
  } else {
    const base = process.env.BASE_REVISION || '';
    const usable = /^[a-f0-9]{40}$/.test(base) && !/^0+$/.test(base);
    const paths = usable ? execFileSync('git', ['diff', '--name-only', base, 'HEAD'], { encoding: 'utf8' }).trim().split(/\r?\n/) : [];
    const value = auditNeeded(paths, process.env.EVENT_NAME, usable);
    if (!process.env.GITHUB_OUTPUT) throw new Error('github_output_missing');
    appendFileSync(process.env.GITHUB_OUTPUT, `audit=${value}\n`);
  }
}
