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
  if (url.pathname==="/keys") return send(200,publicKey.export({format:"jwk"}));
  if (url.pathname==="/health") return send(200,{ok:true});
  if (url.pathname==="/test/state") return send(200,{documents:[...documents.values()],deliveries:[...deliveries.keys()],deliveryDetails:[...deliveries.values()],receipts,calls,acquiringOperations:[...acquiring.operations.values()]});
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
    if (!deliveries.has(body.operationId)) deliveries.set(body.operationId,{messageId:"fixture-"+deliveries.size,filename:body.filename,caption:body.caption,groupId:body.groupId});
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
    const claims={webhookType:"incomingPayment",customerCode:"LOCAL0001",paymentId,purpose:"Оплата по счёту "+doc.number,
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
}).listen(8099,"0.0.0.0");
