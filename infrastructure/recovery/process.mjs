import { spawn } from 'node:child_process';
import { pipeline } from 'node:stream/promises';

// Never include child stderr, arguments, or credential-bearing URLs in operator logs.
export function startProcess(executable, args, { env = process.env, timeoutMs = 300_000, maxOutput = 1024 * 1024 } = {}) {
  const child = spawn(executable, args, { env, shell: false, windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
  child.stdin.on('error', () => {}); // pipeline still observes input failures; prevent an unhandled EPIPE on early exit.
  let output = '';
  let bytes = 0;
  let stderrBytes = 0;
  let timedOut = false;
  const timer = setTimeout(() => { timedOut = true; child.kill('SIGKILL'); }, timeoutMs);
  child.stderr.on('data', chunk => { stderrBytes += chunk.length; });
  const completed = new Promise((resolve, reject) => {
    child.once('error', () => { clearTimeout(timer); reject(new Error('subprocess_start_failed')); });
    child.once('close', code => {
      clearTimeout(timer);
      if (timedOut || code !== 0) reject(new Error(timedOut ? 'subprocess_timeout' : 'subprocess_failed'));
      else resolve(output);
    });
  });
  completed.catch(() => {});
  return { child, completed, get stderrBytes() { return stderrBytes; }, collect() {
    // Pipe boundaries can split a multi-byte character. Decode as a stream;
    // callers that pipeline binary dumps without collect() still receive bytes.
    child.stdout.setEncoding('utf8');
    child.stdout.on('data', chunk => {
      bytes += Buffer.byteLength(chunk, 'utf8');
      if (bytes > maxOutput) { timedOut = true; child.kill('SIGKILL'); }
      else output += chunk;
    });
  } };
}

export async function run(executable, args, options = {}) {
  const process = startProcess(executable, args, options);
  process.collect();
  if (options.input) {
    const input = pipeline(options.input, process.child.stdin).catch(error => {
      process.child.kill('SIGKILL');
      throw error;
    });
    const results = await Promise.allSettled([input, process.completed]);
    const failed = results.find(result => result.status === 'rejected');
    if (failed) throw new Error('subprocess_input_failed');
    return results[1].value;
  }
  process.child.stdin.end();
  return process.completed;
}
