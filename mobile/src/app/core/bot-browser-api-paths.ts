export interface BotBrowserApiPaths {
  metadata: string;
  open: string;
  close: string;
}

export function botBrowserApiPaths(botId: number): BotBrowserApiPaths {
  const base = `/api/bots/${botId}/browser`;
  return {
    metadata: `${base}/metadata`,
    open: `${base}/open`,
    close: `${base}/close`
  };
}

export function botBrowserSessionHeartbeatPath(botId: number, sessionId: string): string {
  return `${botBrowserApiPaths(botId).open.replace(/\/open$/, '')}/sessions/${encodeURIComponent(sessionId)}/heartbeat`;
}

export function botBrowserSessionClosePath(botId: number, sessionId: string): string {
  return `${botBrowserApiPaths(botId).open.replace(/\/open$/, '')}/sessions/${encodeURIComponent(sessionId)}/close`;
}
