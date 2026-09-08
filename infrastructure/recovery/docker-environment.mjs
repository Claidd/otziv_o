// Passing `-e NAME` makes Docker read NAME from its client process environment.
// Do not reinterpret a nested executable's `node -e CODE` option as environment.
export function dockerEnvironment(args, baseEnvironment = process.env) {
  const sanitized = [...args], env = { ...baseEnvironment };
  for (let i = 0; i < sanitized.length - 1; i++) if (sanitized[i] === '-e' && /^[A-Z_][A-Z0-9_]*=/.test(sanitized[i + 1])) {
    const split = sanitized[i + 1].indexOf('='), name = sanitized[i + 1].slice(0, split);
    env[name] = sanitized[i + 1].slice(split + 1); sanitized[i + 1] = name;
  }
  return { args: sanitized, env };
}
