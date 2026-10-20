const WORKER_SECTIONS = new Set(['new', 'correct', 'nagul', 'recovery', 'publish', 'bad', 'all']);

/** Map only the public worker URL associated with the Android app. */
export function workerAppLinkRoute(url: string): string | null {
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    return null;
  }

  if (parsed.protocol !== 'https:' || parsed.host !== 'o-ogo.ru'
    || parsed.username || parsed.password || parsed.pathname !== '/worker') {
    return null;
  }

  const section = parsed.searchParams.get('section')?.trim().toLowerCase();
  return section && WORKER_SECTIONS.has(section)
    ? `/tabs/worker?section=${section}`
    : '/tabs/worker';
}
