// Local GET-only provider fixture. Snapshot contains metadata only, never bot credentials or message text.
const http = require('node:http');
const fs = require('node:fs');
const snapshot = JSON.parse(fs.readFileSync('/snapshot/history.json', 'utf8').replace(/^\uFEFF/, ''));
// Start unavailable so each confirmation/cancellation case can be checked before enabling a snapshot.
const controls = new Map(Object.keys(snapshot.chats).map(chat => [chat, 'unavailable']));
let reads = 0, outbound = 0;
http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://fixture');
  res.setHeader('Content-Type', 'application/json');
  if (url.pathname === '/health') return res.end('{"status":"UP"}');
  if (url.pathname === '/test/state') return res.end(JSON.stringify({ reads, outbound, controls: Object.fromEntries(controls) }));
  if (url.pathname === '/test/mode' && req.method === 'POST') {
    let body=''; for await (const chunk of req) body+=chunk;
    const input=JSON.parse(body); controls.set(String(input.chatId),input.mode);
    return res.end('{"ok":true}');
  }
  if (url.pathname !== '/messages' || req.method !== 'GET') {
    outbound++; res.statusCode=405; return res.end('{"code":"fixture.read_only"}');
  }
  reads++;
  const chat=url.searchParams.get('chat_id'), mode=controls.get(chat);
  if (mode==='unavailable') {res.statusCode=503;return res.end('{"code":"fixture.unavailable"}');}
  const data=snapshot.chats[chat];
  if (!data || !data.complete) {res.statusCode=403;return res.end('{"code":"fixture.unknown_chat"}');}
  const upper=Number(url.searchParams.get('from'));
  let messages=data.messages.filter(row=>row.timestamp<=upper).sort((a,b)=>b.timestamp-a.timestamp);
  if (mode==='reply') messages=[{recipient:{chat_id:Number(chat)},timestamp:Math.min(Date.now()-1000,upper),sender:{is_bot:false},body:{mid:'fixture.reply'}},...messages];
  res.end(JSON.stringify({messages:messages.slice(0,100)}));
}).listen(8098,'0.0.0.0');
