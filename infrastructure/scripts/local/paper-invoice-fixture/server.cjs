"use strict";
// Local-only deterministic bank, DaData and delivery boundaries. No external network or real credentials.
const http = require("node:http");
const crypto = require("node:crypto");
const { privateKey, publicKey } = crypto.generateKeyPairSync("rsa", { modulusLength: 2048 });
const documents = new Map(), deliveries = new Map(), receipts = [], calls = [];
let deliveryFailure = false;
let deliveryFailureSuffix = '';
const acquiring = {merchantId:"LOCAL-MERCHANT",cashbox:"CloudKassir",operations:new Map()};
const account = "40802810000000006542/044525104";
// Local payment sandbox: T-Bank acquiring for the two shop terminals. The keys are fixed non-secret markers;
// the application refuses to start in the sandbox profile with any other terminal.
// The public marker every sandbox terminal uses where a real shop has its password: requests are signed with it.
const SANDBOX_MARKER = "local-sandbox-only";
const TBANK_TERMINALS = new Set(["LocalSandboxPrimary", "LocalSandboxSecondary"]);
const PORT = Number(process.env.FIXTURE_PORT) || 8099;
// The site opens a payment address only on the real bank hosts over https, so the link carries the bank host
// with a path that exists nowhere; the same payment is opened locally at /pay/<id> of this fixture.
const TBANK_PUBLIC_URL = "https://securepay.tinkoff.ru/otziv-local-fixture";
const tbank = new Map();
let tbankSequence = 0;
function tbankToken(values) {
  const signed = {...values, Password: SANDBOX_MARKER};
  return crypto.createHash("sha256").update(Object.keys(signed).sort().map(k => String(signed[k])).join("")).digest("hex");
}
async function notifyTbank(payment) {
  const values = {TerminalKey: payment.terminal, OrderId: payment.orderId, Success: "true", Status: payment.status,
    PaymentId: payment.id, ErrorCode: "0", Amount: String(payment.amount)};
  try {
    const response = await fetch("http://app:8080/api/payments/tbank/webhook", {method: "POST",
      headers: {"Content-Type": "application/json"}, body: JSON.stringify({...values, Token: tbankToken(values)})});
    return {status: response.status, response: await response.text()};
  } catch (error) {
    return {status: 0, response: String(error && error.message || error)};
  }
}
async function settleTbank(id, status, webhook) {
  const payment = tbank.get(String(id));
  if (!payment) return null;
  if (!["NEW", "FORM_SHOWED"].includes(payment.status)) return {payment, skipped: "Ссылка уже в состоянии " + payment.status};
  payment.status = status;
  payment.settledAt = new Date().toISOString();
  return {payment, webhook: webhook === false ? null : await notifyTbank(payment)};
}
const esc = value => String(value ?? "").replace(/[&<>"']/g, c => ({"&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"}[c]));
const rub = kopecks => (Number(kopecks) / 100).toLocaleString("ru-RU", {minimumFractionDigits: 2}) + " ₽";
function page(title, body) {
  return `<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(title)}</title><style>
body{font:15px/1.45 system-ui,sans-serif;margin:0;padding:24px 16px;background:#14171c;color:#e7e9ee}
main{max-width:1040px;margin:0 auto}h1{font-size:22px;margin:0 0 4px}h2{font-size:17px;margin:28px 0 8px}
p.note{color:#9aa3b2;margin:0 0 12px}table{width:100%;border-collapse:collapse;background:#1c2027;border-radius:10px;overflow:hidden}
th,td{padding:8px 10px;text-align:left;border-bottom:1px solid #2a303a;vertical-align:top}th{color:#9aa3b2;font-weight:600;font-size:13px}
td.text{white-space:pre-wrap;word-break:break-word}button{font:inherit;padding:6px 12px;border-radius:8px;border:1px solid #3a4250;background:#2a303a;color:#e7e9ee;cursor:pointer;margin:0 6px 4px 0}
button.pay{background:#1f6f4a;border-color:#2b8a5d}button:disabled{opacity:.5;cursor:default}.tag{display:inline-block;padding:1px 8px;border-radius:999px;background:#2a303a;font-size:12px}
.tag.ok{background:#1f6f4a}.tag.bad{background:#7a2b2b}.empty{color:#9aa3b2;padding:14px}.wrap{overflow-x:auto}a{color:#8fb4ff}
</style></head><body><main>${body}</main><script>
async function act(path,data,button){button.disabled=true;const r=await fetch(path,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(data)});
const t=await r.text();if(!r.ok){alert('Не выполнено: '+t);button.disabled=false;return;}location.reload();}
</script></body></html>`;
}
function statusTag(status) {
  const kind = ["CONFIRMED", "APPROVED"].includes(status) ? " ok" : ["REJECTED", "CANCELED", "DEADLINE_EXPIRED", "EXPIRED"].includes(status) ? " bad" : "";
  return `<span class="tag${kind}">${esc(status)}</span>`;
}
function dashboard() {
  const links = [...tbank.values()].reverse().map(p => `<tr><td><a href="/pay/${esc(p.id)}">${esc(p.id)}</a></td><td>${esc(p.orderId)}</td><td>${rub(p.amount)}</td><td>${statusTag(p.status)}</td>
<td>${["NEW", "FORM_SHOWED"].includes(p.status) ? `<button class="pay" onclick="act('/test/pay-tbank',{paymentId:'${esc(p.id)}'},this)">Оплатить</button>
<button onclick="act('/test/tbank-status',{paymentId:'${esc(p.id)}',status:'REJECTED'},this)">Отклонить</button>
<button onclick="act('/test/tbank-status',{paymentId:'${esc(p.id)}',status:'DEADLINE_EXPIRED'},this)">Срок истёк</button>` : ""}</td></tr>`).join("");
  const tochka = [...acquiring.operations.values()].reverse().map(o => `<tr><td>${esc(o.operationId)}</td><td>${esc(o.paymentLinkId)}</td><td>${rub(Math.round(Number(o.amount) * 100))}</td><td>${statusTag(o.status)}</td>
<td>${o.status === "CREATED" ? `<button class="pay" onclick="act('/test/pay-acquiring',{operationId:'${esc(o.operationId)}'},this)">Оплатить</button>` : ""}</td></tr>`).join("");
  const papers = [...documents.values()].filter(d => d.path.endsWith("/bills")).reverse().map(d => `<tr><td>${esc(d.number)}</td><td>${rub(Math.round(Number(d.amount) * 100))}</td><td>${d.deleted ? statusTag("CANCELED") : statusTag("ISSUED")}</td>
<td>${d.deleted ? "" : `<button class="pay" onclick="act('/test/pay',{number:'${esc(d.number)}'},this)">Оплатить по счёту</button>`}</td></tr>`).join("");
  const messages = [...deliveries.entries()].reverse().map(([operation, d]) => `<tr><td>${esc(d.at || "")}</td><td>${esc(d.title || "")}<br><small>${esc(d.groupId || "")}</small></td>
<td class="text">${esc(d.caption || "")}${d.filename ? `\n[файл: ${esc(d.filename)}]` : ""}</td><td><small>${esc(operation)}</small></td></tr>`).join("");
  return page("Песочница оплат — локальный стенд", `<h1>Песочница оплат</h1>
<p class="note">Локальный стенд. Настоящих банков и клиентов здесь нет: ссылки выдаёт банк-заглушка, сообщения клиентам не уходят, а складываются в список ниже.</p>
<h2>Ссылки Т‑Банка</h2><div class="wrap"><table><tr><th>Платёж</th><th>Заказ в банке</th><th>Сумма</th><th>Состояние</th><th>Действие клиента</th></tr>
${links || '<tr><td colspan="5" class="empty">Ссылок пока нет. Выдайте ссылку банка в заказе или общем счёте.</td></tr>'}</table></div>
<h2>Ссылки Точки</h2><div class="wrap"><table><tr><th>Операция</th><th>Ссылка</th><th>Сумма</th><th>Состояние</th><th>Действие клиента</th></tr>
${tochka || '<tr><td colspan="5" class="empty">Ссылок пока нет.</td></tr>'}</table></div>
<h2>Счета и акты Точки</h2><div class="wrap"><table><tr><th>Номер счёта</th><th>Сумма</th><th>Состояние</th><th>Действие клиента</th></tr>
${papers || '<tr><td colspan="4" class="empty">Бумажных счетов пока нет.</td></tr>'}</table></div>
<h2>Сообщения клиентам</h2><p class="note">Что получил бы клиент. Новые сверху.</p>
<div class="wrap"><table><tr><th>Время</th><th>Кому</th><th>Текст</th><th>Операция</th></tr>
${messages || '<tr><td colspan="4" class="empty">Сообщений пока нет.</td></tr>'}</table></div>`);
}
function jwt(claims) {
  const h = Buffer.from(JSON.stringify({alg:"RS256",typ:"JWT"})).toString("base64url");
  const p = Buffer.from(JSON.stringify(claims)).toString("base64url");
  return h+"."+p+"."+crypto.sign("RSA-SHA256",Buffer.from(h+"."+p),privateKey).toString("base64url");
}
function pdf(number) {
  const stream="BT /F1 18 Tf 40 100 Td ("+number+") Tj ET";
  const parts=["%PDF-1.4\n"];
  const objects=[
    "<< /Type /Catalog /Pages 2 0 R >>",
    "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 400 180] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
    "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    "<< /Length "+Buffer.byteLength(stream)+" >>\nstream\n"+stream+"\nendstream"
  ], offsets=[0];
  objects.forEach((o,i)=>{offsets.push(Buffer.byteLength(parts.join("")));parts.push((i+1)+" 0 obj\n"+o+"\nendobj\n");});
  const start=Buffer.byteLength(parts.join(""));
  parts.push("xref\n0 6\n0000000000 65535 f \n");
  offsets.slice(1).forEach(n=>parts.push(String(n).padStart(10,"0")+" 00000 n \n"));
  parts.push("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n"+start+"\n%%EOF\n");
  return Buffer.from(parts.join(""));
}
http.createServer(async (req,res)=>{
  let input=""; for await (const part of req) input+=part;
  let body={}; try {body=JSON.parse(input||"{}");} catch {}
  const url=new URL(req.url,"http://fixture");
  const send=(code,data)=>{res.writeHead(code,{"Content-Type":"application/json"});res.end(JSON.stringify(data));};
  const html=(code,text)=>{res.writeHead(code,{"Content-Type":"text/html; charset=utf-8","Cache-Control":"no-store"});res.end(text);};
  if (req.method==="GET" && url.pathname==="/") return html(200,dashboard());
  if (req.method==="GET" && url.pathname.startsWith("/pay/")) {
    const payment=tbank.get(url.pathname.split("/").at(-1));
    if(!payment) return html(404,page("Ссылка не найдена",`<h1>Ссылка не найдена</h1><p class="note">Банк-заглушка перезапускался и не помнит эту ссылку. Выдайте ссылку заново.</p>`));
    const open=["NEW","FORM_SHOWED"].includes(payment.status);
    return html(200,page("Оплата — локальный стенд",`<h1>Оплата ${rub(payment.amount)}</h1>
<p class="note">Так выглядит переход клиента по ссылке банка. Это заглушка локального стенда: деньги не списываются.</p>
<p>${esc(payment.description||"")}</p><p>Состояние: ${statusTag(payment.status)}</p>
${open?`<button class="pay" onclick="act('/test/pay-tbank',{paymentId:'${esc(payment.id)}'},this)">Оплатить</button>
<button onclick="act('/test/tbank-status',{paymentId:'${esc(payment.id)}',status:'REJECTED'},this)">Отказаться</button>`:""}
<p><a href="/">Все ссылки и сообщения</a></p>`));
  }
  if (url.pathname.startsWith("/tbank/v2/")) {
    const operation=url.pathname.split("/").at(-1), terminal=body.TerminalKey;
    const refuse=(message,code)=>send(200,{Success:false,ErrorCode:code||"9999",Message:message,TerminalKey:terminal});
    if(!TBANK_TERMINALS.has(terminal)) return refuse("Терминал не относится к локальной песочнице","202");
    const {Token:received,...unsigned}=body;
    const flat=Object.fromEntries(Object.entries(unsigned).filter(([,value])=>value!==null && typeof value!=="object"));
    if(received!==tbankToken(flat)) return refuse("Неверная подпись запроса","204");
    calls.push({method:"POST",path:url.pathname,paymentId:body.PaymentId,orderId:body.OrderId});
    if(operation==="GetQrBankList") return send(200,{Success:true,ErrorCode:"0",TerminalKey:terminal,BankList:[]});
    if(operation==="Init") {
      if(!(Number(body.Amount)>0) || !body.OrderId) return refuse("Init без суммы или номера заказа","9");
      if([...tbank.values()].some(p=>p.terminal===terminal && p.orderId===String(body.OrderId))) return refuse("Заказ с таким OrderId уже существует","8");
      const id=String(Date.now())+String(++tbankSequence%100).padStart(2,"0");
      const payment={id,terminal,orderId:String(body.OrderId),amount:Number(body.Amount),status:"NEW",description:body.Description||"",createdAt:new Date().toISOString()};
      tbank.set(id,payment);
      return send(200,{Success:true,ErrorCode:"0",TerminalKey:terminal,Status:"NEW",PaymentId:id,OrderId:payment.orderId,Amount:payment.amount,PaymentURL:TBANK_PUBLIC_URL+"/"+id});
    }
    const payment=tbank.get(String(body.PaymentId));
    if(!payment || payment.terminal!==terminal) return refuse("Платёж не найден в локальной песочнице","255");
    if(operation==="GetState") return send(200,{Success:true,ErrorCode:"0",TerminalKey:terminal,Status:payment.status,PaymentId:payment.id,OrderId:payment.orderId,
      Amount:["CANCELED","DEADLINE_EXPIRED","REJECTED"].includes(payment.status)?0:payment.amount});
    if(operation==="GetQr") return ["NEW","FORM_SHOWED"].includes(payment.status)
      ? send(200,{Success:true,ErrorCode:"0",TerminalKey:terminal,PaymentId:payment.id,Data:"https://qr.nspk.ru/LOCALSANDBOX"+payment.id+"?type=02&bank=100000000004&sum="+payment.amount+"&cur=RUB&crc=0000"})
      : refuse("Платёж уже завершён","9");
    if(operation==="Cancel") {
      if(["NEW","FORM_SHOWED"].includes(payment.status)) payment.status="CANCELED";
      else if(payment.status==="CONFIRMED") payment.status="REFUNDED";
      return send(200,{Success:true,ErrorCode:"0",TerminalKey:terminal,Status:payment.status,PaymentId:payment.id,OrderId:payment.orderId,OriginalAmount:payment.amount,NewAmount:0});
    }
    return refuse("Операция не поддерживается локальной песочницей","9");
  }
  if (url.pathname==="/test/pay-tbank" || url.pathname==="/test/tbank-status") {
    const status=url.pathname==="/test/pay-tbank"?"CONFIRMED":String(body.status||"");
    if(!["CONFIRMED","REJECTED","DEADLINE_EXPIRED"].includes(status)) return send(400,{message:"Состояние не поддерживается"});
    const result=await settleTbank(body.paymentId,status,body.webhook);
    if(!result) return send(404,{message:"Платёж не найден"});
    return send(200,{paymentId:result.payment.id,status:result.payment.status,skipped:result.skipped||null,webhook:result.webhook||null});
  }
  if (url.pathname==="/keys") return send(200,publicKey.export({format:"jwk"}));
  if (url.pathname==="/health") return send(200,{ok:true});
  if (url.pathname==="/test/state") return send(200,{documents:[...documents.values()],deliveries:[...deliveries.keys()],deliveryDetails:[...deliveries.values()],receipts,calls,acquiringOperations:[...acquiring.operations.values()],tbankPayments:[...tbank.values()]});
  if (url.pathname==="/delivery-receipt") return send(200,deliveries.get(body.operationId)||{});
  if (url.pathname==="/test/acquiring") {
    const restored=body.operations||[];
    if(restored.some(o=>o.customerCode!=='LOCAL0001' || !o.operationId || !o.paymentLinkId)) return send(400,{message:'only local operations may be restored'});
    acquiring.merchantId=body.merchantId;acquiring.cashbox=body.cashbox;
    for(const operation of restored) acquiring.operations.set(operation.operationId,{...operation,paymentLink:'https://merch.securepaytb.ru/otziv-local-fixture/'+operation.operationId});
    return send(200,{ok:true});
  }
  if (url.pathname.endsWith('/acquiring/v1.0/retailers')) return send(200,{Data:{Retailer:[{status:"REG",isActive:true,merchantId:acquiring.merchantId,cashbox:acquiring.cashbox,paymentModes:["card","sbp"]}]}});
  if (url.pathname.endsWith('/acquiring/v1.0/payments_with_receipt')) {
    const data=body.Data,id=crypto.randomUUID();
    const operation={...data,status:"CREATED",operationId:id,paymentLink:"https://merch.securepaytb.ru/otziv-local-fixture/"+id};
    acquiring.operations.set(id,operation);calls.push({method:"POST",path:url.pathname,paymentLinkId:data.paymentLinkId});
    return send(200,{Data:operation});
  }
  if (url.pathname.endsWith('/acquiring/v1.0/payments')) return send(200,{Data:{Operation:[...acquiring.operations.values()]},Meta:{totalPages:1}});
  if (/\/acquiring\/v1.0\/payments\/[A-Za-z0-9-]+$/.test(url.pathname) && req.method==='GET') {
    const operation=acquiring.operations.get(url.pathname.split('/').at(-1));
    return operation ? send(200,{Data:{Operation:[operation]}}) : send(404,{message:'missing operation'});
  }
  if (url.pathname==='/test/pay-acquiring') {
    const operation=acquiring.operations.get(body.operationId);
    if(!operation) return send(404,{message:'missing local operation'});
    operation.status='APPROVED';operation.paymentType=body.paymentType||operation.paymentMode?.[0]||'sbp';
    const claims={...operation,webhookType:'acquiringInternetPayment'};
    const response=await fetch('http://app:8080/api/payments/tochka/webhook',{method:'POST',headers:{'Content-Type':'text/plain'},body:jwt(claims)});
    return send(200,{operationId:operation.operationId,status:response.status,response:await response.text()});
  }
  if (url.pathname==="/lookup") {
    const inn=body.query;
    return send(200,{suggestions:inn==="7707083893"?[{data:{type:"LEGAL",inn,kpp:"773601001",
      name:{full_with_opf:"ООО Тестовый заказчик"},address:{value:"Москва, тестовый адрес"},state:{status:"ACTIVE"}}}]:[]});
  }
  if (url.pathname==="/open-banking/v1.0/accounts")
    return send(200,{Data:{Account:[{customerCode:"LOCAL0001",accountId:account,status:"Enabled",currency:"RUB",accountType:"Business"}]}});
  if (url.pathname.endsWith('/payment-status')) {
    const doc=documents.get(url.pathname.split('/').at(-2));
    if(!doc || doc.deleted) return send(404,{message:'missing'});
    calls.push({method:'GET',path:url.pathname});
    const paid=receipts.some(r=>r.purpose==='Оплата по счёту '+doc.number && r.SidePayer.inn===doc.payer && r.SideRecipient.amount===String(doc.amount));
    return send(200,{Data:{paymentStatus:paid?'payment_paid':'payment_waiting'}});
  }
  if(req.method==='DELETE' && /^\/invoice\/v1.0\/(bills|closing-documents)\/LOCAL0001\/[A-Za-z0-9-]+$/.test(url.pathname)) {
    const doc=documents.get(url.pathname.split('/').at(-1));
    calls.push({method:'DELETE',path:url.pathname});
    if(!doc || doc.deleted) return send(404,{message:'missing'});
    doc.deleted=true;return send(200,{Data:{result:true}});
  }
  if (req.method==="POST" && ["/invoice/v1.0/bills","/invoice/v1.0/closing-documents"].includes(url.pathname)) {
    const d=body.Data, content=d?.Content?.Invoice||d?.Content?.Act;
    if (!d?.SecondSide?.taxCode || !content?.number || !content?.Positions?.length) return send(400,{message:"invalid document"});
    const id=crypto.randomUUID(); const document={id,path:url.pathname,number:content.number,amount:content.totalAmount,payer:d.SecondSide.taxCode};
    documents.set(id,document); calls.push({method:"POST",path:url.pathname,number:content.number});
    return send(200,{Data:{documentId:id}});
  }
  if (url.pathname.endsWith("/file")) {
    const id=url.pathname.split("/").at(-2), document=documents.get(id);
    if (!document || document.deleted) return send(404,{message:"missing"});
    res.writeHead(200,{"Content-Type":"application/pdf"});return res.end(pdf(document.number));
  }
  if (url.pathname==="/delivery") {
    calls.push({method:'POST',path:'/delivery',operationId:body.operationId});
    if(deliveryFailure && (!deliveryFailureSuffix || body.operationId.endsWith(deliveryFailureSuffix)))
      return send(200,{errorCode:'gateway_not_ready',errorMessage:'Локальный шлюз временно недоступен'});
    // Match the real file gateway contract, so empty PDF captions cannot pass local QA.
    if (body.data && (!body.caption || !body.caption.trim())) return send(400,{code:"invalid_request"});
    if (!deliveries.has(body.operationId)) deliveries.set(body.operationId,{messageId:"fixture-"+deliveries.size,filename:body.filename,caption:body.caption,groupId:body.groupId,title:body.title,at:new Date().toLocaleString("ru-RU",{timeZone:"Asia/Irkutsk"})});
    return send(200,deliveries.get(body.operationId));
  }
  if(url.pathname==='/test/delivery-failure' && req.method==='POST') {
    deliveryFailure=body.enabled===true;deliveryFailureSuffix=[':message',':invoice',':act'].includes(body.suffix)?body.suffix:'';
    return send(200,{enabled:deliveryFailure,suffix:deliveryFailureSuffix});
  }
  if (url.pathname==="/test/pay") {
    const doc=[...documents.values()].find(d=>d.number===body.number);
    if (!doc) return send(404,{error:"document missing"});
    const amount=String(body.amount??doc.amount), paymentId=body.paymentId||crypto.randomUUID();
    // "typedNumber" is what the payer wrote by hand instead of the issued number (wrong layout, long dashes).
    const claims={webhookType:"incomingPayment",customerCode:"LOCAL0001",paymentId,purpose:"Оплата по счёту "+(body.typedNumber||doc.number),
      SideRecipient:{account:account.split("/")[0],bankCode:"044525104",currency:"RUB",amount},
      SidePayer:{inn:body.inn||doc.payer,account:"40702810000000000001",bankCode:"044525104",currency:"RUB",amount}};
    receipts.push(claims);
    if (body.webhook===false) return send(200,{paymentId,webhook:false});
    const response=await fetch("http://app:8080/api/payments/tochka/webhook",{method:"POST",headers:{"Content-Type":"text/plain"},body:jwt(claims)});
    return send(200,{paymentId,status:response.status,response:await response.text()});
  }
  if (url.pathname==="/open-banking/v1.0/statements" && req.method==="POST")
    return send(200,{Data:{Statement:{statementId:"local-statement"}}});
  if (url.pathname.includes("/statements/")) return send(200,{Data:{Statement:[{accountId:account,statementId:"local-statement",status:"Ready",
    Transaction:receipts.map(r=>({paymentId:r.paymentId,creditDebitIndicator:"Credit",status:"Booked",transactionTypeCode:"Платежное поручение",
      description:r.purpose,Amount:{amount:r.SideRecipient.amount,currency:"RUB"},DebtorParty:{inn:r.SidePayer.inn}}))}]},Meta:{totalPages:1}});
  send(404,{message:"Unknown fixture path"});
}).listen(PORT,"0.0.0.0");
