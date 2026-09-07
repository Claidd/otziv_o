'use strict';

// Runs in a disposable non-privileged container on the application's private
// network. Only its own synthetic log marker is queried; no Docker socket.
function hasLogMessage(stream, marker) {
  // Dozzle v10 emits LogEvent.m in ordinary message events and arrays in
  // logs-backfill. Container metadata can contain our label but is not a log.
  return stream.split(/\r?\n\r?\n/).slice(0, -1).some(frame => {
    const lines = frame.split(/\r?\n/);
    const event = lines.find(line => line.startsWith('event:'))?.slice(6).trim() || 'message';
    if (!['message', 'logs-backfill'].includes(event)) return false;
    try {
      const data = JSON.parse(lines.filter(line => line.startsWith('data:')).map(line => line.slice(5).trimStart()).join('\n'));
      return (Array.isArray(data) ? data : [data]).some(log => typeof log?.m === 'string' && log.m.trim() === marker);
    } catch { return false; }
  });
}

async function probe(consumer, marker, { fetcher = fetch, timeoutMs = 60000 } = {}) {
  if (!['dozzle', 'alloy'].includes(consumer) || !/^OTZIV_DEPLOY_[a-f0-9]{32}$/.test(marker)) throw new Error('invalid_probe');
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), Math.min(5000, Math.max(1, deadline - Date.now())));
    try {
      const url = consumer === 'dozzle'
        ? `http://dozzle:8080/api/labels/dozzle.name:${marker}/logs/stream?stdout=1&stderr=1&levels=unknown&levels=info&levels=debug&levels=warn&levels=error&levels=fatal&levels=trace`
        : `http://loki:3100/loki/api/v1/query_range?query=${encodeURIComponent(`{job="docker",collector="alloy"} |= "${marker}"`)}&limit=20&since=5m`;
      const response = await fetcher(url, { signal: controller.signal, redirect: 'error' });
      if (response.status !== 200) throw new Error('consumer_not_ready');
      let text = '';
      for await (const chunk of response.body) {
        text += Buffer.from(chunk).toString('utf8');
        if (text.length > 262144) throw new Error('probe_response_too_large');
        if (consumer === 'dozzle' && hasLogMessage(text, marker)) return;
      }
      if (consumer === 'alloy') {
        const result = JSON.parse(text);
        if (result.status === 'success' && result.data?.result?.some(stream =>
          stream.values?.some(value => typeof value[1] === 'string' && value[1].includes(marker)))) return;
      }
    } catch { /* Discovery/ingestion is asynchronous; the overall bound is fixed. */ }
    finally { clearTimeout(timer); controller.abort(); }
    await new Promise(resolve => setTimeout(resolve, 250));
  }
  throw new Error(`${consumer}_log_flow_unverified`);
}

module.exports = { probe };
if (require.main === module) {
  const [consumer, marker] = process.argv.slice(2);
  const emit = setInterval(() => console.log(marker), 250);
  probe(consumer, marker).then(() => {
    console.log(JSON.stringify({ result: 'PASS', consumer, syntheticLogFlow: true }));
  }).catch(() => { console.error('Observer deployment log-flow check failed'); process.exitCode = 1; })
    .finally(() => clearInterval(emit));
}
