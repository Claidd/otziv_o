"use strict";
const path = require("node:path");
const { RemoteSessionFence } = require("./remote-session-fence");
const { configuredRemoteBrowserUrl, resolveRemoteBrowserUrl, requirePeoplesBrowserProfile } = require("./remote-browser");

async function main() {
  const [action, expectedGeneration, expectedBrowserId, actor, evidenceReference] = process.argv.slice(2);
  if (!['inspect', 'reconcile'].includes(action) || action === 'inspect' && process.argv.length !== 3
      || action === 'reconcile' && process.argv.length !== 7) throw new Error('remote_session_admin_usage');
  const required = ['1', 'true', 'yes', 'on'].includes(String(process.env.WHATSAPP_REMOTE_BROWSER_REQUIRED || '').toLowerCase());
  const browserUrl = configuredRemoteBrowserUrl(process.env.WHATSAPP_BROWSER_URL, true);
  const profileId = required ? requirePeoplesBrowserProfile(browserUrl, process.env.WHATSAPP_BROWSER_PROFILE_ID) : undefined;
  const fence = new RemoteSessionFence({ directory: path.join(process.env.AUTH_PATH || '/auth', 'remote-sessions'),
    clientId: process.env.CLIENT_ID || 'whatsapp_default', profileId, browserUrl });
  try {
    const endpoint = await resolveRemoteBrowserUrl(browserUrl);
    const result = action === 'inspect' ? await fence.inspect(endpoint) : await fence.reconcile(endpoint,
      { expectedGeneration, expectedBrowserId, actor, evidenceReference });
    console.log(JSON.stringify(result));
  } finally { fence.release(); }
}
main().catch(error => {
  console.error(JSON.stringify({ result: 'FAIL', code: /^[a-z_]+$/.test(error.message || '') ? error.message : 'remote_session_admin_failed' }));
  process.exitCode = 1;
});
