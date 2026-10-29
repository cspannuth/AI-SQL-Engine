/** Optional real-browser checks. Uses an installed Chromium browser and Node's WebSocket. */
import assert from "node:assert/strict";
import {writeFile, readFile, access} from "node:fs/promises";
import {join} from "node:path";
import {parseExact, csvText} from "../sql_agent/web/values.mjs";

const [port, url, output] = process.argv.slice(2);
assert.equal(parseExact('{"rows":[[9223372036854775807,-9223372036854775808,"9223372036854775807"]]}').rows[0][0].$integer,"9223372036854775807");
assert.equal(parseExact('{"text":"quoted \\"123\\"","value":1.25}').value,1.25);
assert.ok(csvText({columns:[{name:"value"}],rows:[[null],[""],['=SUM(A1)'],[{$integer:"9223372036854775807"}]]}).includes("\"'=SUM(A1)\""));
const target = await (await fetch("http://127.0.0.1:"+port+"/json/new?"+encodeURIComponent(url),{method:"PUT"})).json();
const socket = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((resolve,reject)=>{socket.addEventListener("open",resolve,{once:true});socket.addEventListener("error",reject,{once:true});});
let next=0;
const pending=new Map(), errors=[];
socket.addEventListener("message",event=>{
  const message=JSON.parse(event.data);
  if(message.id && pending.has(message.id)){
    const {resolve,reject}=pending.get(message.id);pending.delete(message.id);
    message.error ? reject(new Error(message.error.message)) : resolve(message.result);
  }
  if(message.method==="Runtime.exceptionThrown") errors.push(message.params.exceptionDetails.text);
});
function command(method,params={}){
  return new Promise((resolve,reject)=>{
    const id=++next;
    const timeout=setTimeout(()=>{pending.delete(id);reject(new Error("Browser command timed out: "+method));},15000);
    pending.set(id,{resolve:value=>{clearTimeout(timeout);resolve(value);},reject:error=>{clearTimeout(timeout);reject(error);}});
    socket.send(JSON.stringify({id,method,params}));
  });
}
async function evaluate(expression){
  const result=await command("Runtime.evaluate",{expression,returnByValue:true,awaitPromise:true});
  if(result.exceptionDetails)throw new Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text);
  return result.result.value;
}
async function wait(expression,label){
  const deadline=Date.now()+15000;
  while(Date.now()<deadline){
    if(await evaluate(expression))return;
    await new Promise(resolve=>setTimeout(resolve,75));
  }
  throw new Error("Browser wait failed: "+label+"\n"+await evaluate("document.body.innerText"));
}
async function click(id){await evaluate("document.getElementById("+JSON.stringify(id)+").click()");}
async function fill(id,value){await evaluate("(()=>{const e=document.getElementById("+JSON.stringify(id)+");e.value="+JSON.stringify(value)+";e.dispatchEvent(new Event('input',{bubbles:true}));})()");}
async function run(sql,expected){
  await fill("sql-input",sql);await click("run-button");
  await wait("!document.getElementById('run-button').disabled && document.getElementById('sql-result').innerText.includes("+JSON.stringify(expected)+")","SQL: "+sql);
}
async function screenshot(name){
  const image=await command("Page.captureScreenshot",{format:"png"});
  await writeFile(join(output,name),Buffer.from(image.data,"base64"));
}
try{
  await command("Runtime.enable");await command("Page.enable");
  await command("Emulation.setDeviceMetricsOverride",{width:1440,height:1000,deviceScaleFactor:1,mobile:false});
  await command("Browser.setDownloadBehavior",{behavior:"allow",downloadPath:output,eventsEnabled:true});
  await wait("document.getElementById('run-button') && !document.getElementById('run-button').disabled","Workspace ready");
  await run("SELECT id,name,price FROM products ORDER BY id DESC;","9223372036854775807");
  assert.equal(await evaluate("document.querySelector('#sql-result script')===null"),true);
  assert.ok((await evaluate("document.getElementById('sql-result').innerText")).includes("<script>alert(1)</script>"));
  await screenshot("workspace-desktop.png");
  await evaluate("document.querySelector('#sql-result .result-actions button').click()");
  const download=join(output,"sql-result.json");
  for(let attempt=0;attempt<80;attempt++){
    try{await access(download);break;}catch{await new Promise(resolve=>setTimeout(resolve,75));}
  }
  assert.ok((await readFile(download,"utf8")).includes("9223372036854775807"));
  await click("begin-button");await wait("document.getElementById('transaction-badge').textContent==='Transaction active'","Begin");
  await run("UPDATE products SET price=99 WHERE id=1;","1 rows affected");
  await click("rollback-button");await wait("document.getElementById('transaction-badge').textContent==='Auto-commit'","Rollback");
  await run("SELECT price FROM products WHERE id=1;","5");
  await evaluate("document.querySelector('[data-page=database]').click()");
  await wait("document.querySelector('#schema-detail h2')","Explorer ready");
  await evaluate("[...document.querySelectorAll('.table-item')].find(e=>e.textContent==='products').click()");
  await wait("document.querySelector('#schema-detail h2').textContent==='products' && !document.querySelector('.schema-action').disabled","Product schema");
  await evaluate("document.querySelector('.schema-action').click()");
  await wait("document.querySelector('#schema-detail .result-panel')","Data preview");
  await screenshot("workspace-explorer.png");
  await evaluate("document.querySelector('[data-page=ask]').click()");
  await fill("question-input","How many products are there?");await click("ask-button");
  await wait("document.querySelector('.chat-turn .result-panel') && !document.getElementById('ask-button').disabled","AI result");
  assert.ok((await evaluate("document.querySelector('.chat-turn .result-panel').innerText")).includes("3"));
  await screenshot("workspace-ask.png");
  await evaluate("document.querySelector('[data-page=activity]').click()");
  await wait("document.querySelector('.history-card')","History");
  await evaluate("document.querySelector('[data-page=settings]').click()");
  assert.ok((await evaluate("document.getElementById('settings-content').innerText")).includes("ui-fixture"));
  await command("Emulation.setDeviceMetricsOverride",{width:390,height:844,deviceScaleFactor:1,mobile:true});
  await evaluate("document.querySelector('[data-page=sql]').click()");
  await screenshot("workspace-mobile.png");
  assert.equal(await evaluate("document.documentElement.scrollWidth<=window.innerWidth"),true);
  assert.deepEqual(errors,[]);
  console.log("Browser interaction checks passed: SQL, exact int64 export, rollback, schema, preview, AI, history, settings, and mobile layout");
}finally{await command("Browser.close").catch(()=>{});socket.close();}
