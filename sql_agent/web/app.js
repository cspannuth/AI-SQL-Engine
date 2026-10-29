import {parseExact, displayValue, csvText} from "./values.mjs";

const $ = id => document.getElementById(id);
const state = {token:"", status:null, tables:[], selected:null, page:null, busy:false, conversation:crypto.randomUUID()};
const titles = {sql:"SQL console", database:"Database explorer", ask:"Ask AI", activity:"Activity", settings:"Settings"};
function node(tag, className="", text) {
  const element = document.createElement(tag);
  if (className) element.className = className;
  if (text !== undefined) element.textContent = text;
  return element;
}
function icon(name) {
  const svg = document.createElementNS("http://www.w3.org/2000/svg","svg");
  const use = document.createElementNS(svg.namespaceURI,"use");
  use.setAttribute("href","#i-"+name); svg.append(use); svg.setAttribute("aria-hidden","true"); return svg;
}
function button(text, handler, className="button secondary") {
  const element = node("button",className,text); element.type="button";
  element.addEventListener("click",handler); return element;
}
function badge(text, style="neutral") { return node("span","badge "+style,text); }
function notice(text, style="") { return node("div","notice "+style,text); }
function empty(host, heading, text, name="table") {
  const panel=node("div","panel empty-state"); panel.append(icon(name),node("h3","",heading),node("p","",text));
  host.replaceChildren(panel);
}
function globalError(error) {
  $("global-notice").textContent=error ? error.message || String(error) : "";
  $("global-notice").classList.toggle("hidden",!error);
}
async function api(path, body) {
  let response;
  try {
    response=await fetch(path,body===undefined ? {cache:"no-store"} : {
      method:"POST",headers:{"Content-Type":"application/json","X-Workspace-Token":state.token},body:JSON.stringify(body),cache:"no-store"
    });
  } catch {
    throw new Error("The local server did not respond. A submitted statement may have executed; inspect state before retrying.");
  }
  const raw=await response.text(), value=parseExact(raw);
  if (!response.ok || !value.ok) {
    const error=new Error(value.error?.message || "Workspace request failed"); error.code=value.error?.code; throw error;
  }
  return {data:value.data,raw};
}
function controls() {
  const running=state.busy || state.status?.busy, unavailable=running || !state.status?.connected;
  document.querySelectorAll(".action").forEach(element=>{element.disabled=!!unavailable;});
  $("begin-button").disabled=!!unavailable || !!state.status?.transactionActive;
  $("commit-button").disabled=$("rollback-button").disabled=!!unavailable || !state.status?.transactionActive;
  $("ask-button").disabled=!!unavailable || !state.status?.model.ready || !!state.status?.transactionActive;
  document.querySelectorAll(".schema-action,.table-item").forEach(element=>{element.disabled=!!unavailable || !!state.status?.transactionActive;});
  $("refresh-tables").disabled=!!unavailable || !!state.status?.transactionActive;
  $("workspace-state").textContent=running ? "Working…" : state.status?.connected ? "Connected to the local engine" : "Engine disconnected";
  $("workspace-state").classList.toggle("busy-indicator",!!running);
}
function busy(value) { state.busy=value; controls(); }
async function refreshStatus() {
  const {data}=await api("/api/status"); state.status=data; state.token=data.token;
  $("database-name").textContent=data.database;
  $("connection-label").textContent=data.connected ? "Local database connected" : "Engine disconnected";
  $("connection-dot").classList.toggle("offline",!data.connected);
  $("transaction-badge").className="badge "+(data.transactionActive ? "warning" : "neutral");
  $("transaction-badge").textContent=data.transactionActive ? "Transaction active" : "Auto-commit";
  $("transaction-description").textContent=data.transactionActive ? "Changes are pending. All tabs share this transaction." : "Statements commit automatically.";
  $("model-badge").className="badge "+(data.model.ready ? "success" : "neutral");
  $("model-badge").textContent=data.model.ready ? "AI ready" : "Model setup";
  $("ai-notice").classList.toggle("hidden",data.model.ready && !data.transactionActive);
  $("ai-notice").textContent=data.transactionActive ? "Commit or roll back the SQL console transaction before using AI." : data.model.note;
  if (state.page==="settings") renderSettings();
  controls();
}
async function syncStatus() {
  try { await refreshStatus(); }
  catch(error) { if(state.status) state.status.connected=false; controls(); globalError(error); }
}
function navigate(page,updateHash=true) {
  if (!titles[page]) page="sql"; state.page=page;
  document.querySelectorAll(".page").forEach(element=>element.classList.toggle("hidden",element.id!=="page-"+page));
  document.querySelectorAll("[data-page]").forEach(element=>{
    element.classList.toggle("active",element.dataset.page===page);
    element.setAttribute("aria-current",element.dataset.page===page ? "page" : "false");
  });
  $("breadcrumb-current").textContent=titles[page];
  if(updateHash) location.hash=page;
  if(page==="settings") renderSettings();
  if(page==="activity") refreshHistory().catch(globalError);
  if(page==="database" && !state.selected && state.tables.length && !state.status?.transactionActive) selectTable(state.tables[0]);
}
function sqlText() {
  const editor=$("sql-input");
  return editor.selectionEnd>editor.selectionStart ? editor.value.slice(editor.selectionStart,editor.selectionEnd) : editor.value;
}
function editorPosition() {
  const editor=$("sql-input"), prior=editor.value.slice(0,editor.selectionStart).split("\n");
  $("editor-position").textContent="Ln "+prior.length+", Col "+(prior.at(-1).length+1);
  $("line-numbers").textContent=editor.value.split("\n").map((_,index)=>index+1).join("\n");
}
function errorResult(host,error) {
  const panel=node("div","panel"); panel.append(notice((error.code ? error.code+": " : "")+error.message,"error")); host.replaceChildren(panel);
}
function download(name,content,type) {
  const url=URL.createObjectURL(new Blob([content],{type})), link=node("a"); link.href=url; link.download=name; link.click();
  setTimeout(()=>URL.revokeObjectURL(url),1000);
}
function renderResult(host,result,raw) {
  const panel=node("div","panel result-panel"), header=node("div","result-header"), title=node("div","result-title");
  title.append(icon("table"),node("span","","Results"),badge(result.complete ? "Complete" : "Partial result",result.complete ? "success" : "warning"));
  const actions=node("div","result-actions");
  actions.append(button("JSON",()=>download("sql-result.json",raw,"application/json"),"button small secondary"));
  if(result.columns.length) {
    const csv=button("CSV",()=>download("sql-result.csv",csvText(result),"text/csv;charset=utf-8"),"button small secondary");
    csv.title="CSV uses spreadsheet-safe text. JSON preserves original values and types."; actions.append(csv);
  }
  header.append(title,actions); panel.append(header);
  const meta=node("div","result-meta");
  meta.append(node("span","",result.returnedRows+" rows returned"),node("span","",result.elapsedMillis+" ms"),node("span","",result.scannedRows+" rows examined"));
  if(result.affectedRows!==undefined) meta.append(node("span","",result.affectedRows+" rows affected"));
  panel.append(meta);
  if(!result.complete) panel.append(notice("Returned data is incomplete ("+(result.truncationReason || "output limit")+"). Totals from these rows may not represent the full population.","warning"));
  const tabs=node("div","result-tabs"), tableView=node("div"), jsonView=node("pre","json-view hidden",raw);
  const tableTab=button("Table",()=>switchView(false),"tab active"), jsonTab=button("JSON",()=>switchView(true),"tab");
  tabs.append(tableTab,jsonTab); panel.append(tabs,tableView,jsonView);
  function switchView(json) {
    jsonView.classList.toggle("hidden",!json); tableView.classList.toggle("hidden",json);
    tableTab.classList.toggle("active",!json); jsonTab.classList.toggle("active",json);
  }
  let page=0;
  function renderRows() {
    tableView.replaceChildren();
    if(!result.columns.length) {tableView.append(node("div","result-message","Statement completed successfully."));return;}
    if(!result.rows.length) {
      const message=node("div","empty-state");
      message.append(icon("table"),node("h3","","No rows returned"),node("p","","The statement succeeded and returned an empty result."));
      tableView.append(message);return;
    }
    const scroller=node("div","table-scroll"), table=node("table"), head=node("thead"), tr=node("tr");
    tr.append(node("th","row-number","#"));
    result.columns.forEach(column=>{const th=node("th","",column.name);th.append(node("small","",column.type));tr.append(th);});
    head.append(tr);table.append(head);const body=node("tbody");
    result.rows.slice(page*50,(page+1)*50).forEach((row,index)=>{
      const line=node("tr");line.append(node("td","row-number",page*50+index+1));
      row.forEach(value=>{
        const cell=node("td");
        if(value===null) cell.append(node("span","sql-null","NULL"));
        else if(value==="") cell.append(node("span","empty-string",'""'));
        else cell.textContent=displayValue(value);
        cell.title=displayValue(value);line.append(cell);
      });body.append(line);
    });
    table.append(body);scroller.append(table);tableView.append(scroller);
    const footer=node("div","result-footer"), pager=node("div","pager");
    footer.append(node("span","",result.resultId ? "Result "+result.resultId : "Captured "+new Date(result.capturedAt).toLocaleTimeString()));
    const previous=button("←",()=>{page--;renderRows();},"button small secondary"), next=button("→",()=>{page++;renderRows();},"button small secondary");
    previous.setAttribute("aria-label","Previous result page");next.setAttribute("aria-label","Next result page");
    previous.disabled=page===0;next.disabled=(page+1)*50>=result.rows.length;
    pager.append(previous,node("span","",(page*50+1)+"–"+Math.min((page+1)*50,result.rows.length)+" of "+result.rows.length),next);
    footer.append(pager);tableView.append(footer);
  }
  renderRows();host.replaceChildren(panel);
}
async function runSQL(explain=false,explicitSQL) {
  const sql=explicitSQL || sqlText();
  if(!sql.trim()) {errorResult($("sql-result"),new Error("Enter a SQL statement first."));return;}
  busy(true);globalError(null);let succeeded=false;
  try {
    const {data,raw}=await api(explain ? "/api/plan" : "/api/sql",{sql,parameters:explicitSQL ? "[]" : $("parameters-input").value});
    renderResult($("sql-result"),data,raw);succeeded=true;
  } catch(error) {errorResult($("sql-result"),error);}
  finally {await syncStatus();busy(false);}
  if(succeeded && !state.status?.transactionActive && state.status?.connected) {
    try {await refreshTables();}catch(error){globalError(new Error("Statement succeeded. Schema refresh: "+error.message));}
  }
}
async function refreshTables() {
  const {data}=await api("/api/tables");state.tables=data;renderTables();renderSuggestions();
  if(state.selected && !data.includes(state.selected)) {
    state.selected=null;empty($("schema-detail"),"Choose a table","Select a table to inspect its schema.","database");
  }
}
function renderTables() {
  $("table-count").textContent=state.tables.length;$("table-list").replaceChildren();
  const search=$("table-search").value.toLowerCase();
  state.tables.filter(name=>name.toLowerCase().includes(search)).forEach(name=>{
    const item=button("",()=>selectTable(name),"table-item action"+(state.selected===name ? " selected" : ""));
    item.append(icon("table"),node("span","",name));$("table-list").append(item);
  });
  if(!$("table-list").children.length) $("table-list").append(node("div","empty-tables",state.tables.length ? "No matching tables." : "No tables yet. Create one in the SQL console."));
  controls();
}
async function selectTable(name) {
  state.selected=name;renderTables();busy(true);
  try {const {data,raw}=await api("/api/schema?table="+encodeURIComponent(name));renderSchema(data,raw);}
  catch(error){errorResult($("schema-detail"),error);}finally{busy(false);}
}
function renderSchema(schema,raw) {
  const detail=$("schema-detail"), panel=node("div","panel"), heading=node("div","schema-heading"), description=node("div");
  description.append(node("h2","",schema.name),node("p","",schema.columns.length+" columns · "+(schema.rowIdTable ? "Row ID table" : "Legacy physical key")));
  const rows=node("div","schema-section");
  const preview=button("Browse 50 rows",async()=>{
    busy(true);try{const result=await api("/api/preview",{table:schema.name});renderResult(rows,result.data,result.raw);}
    catch(error){errorResult(rows,error);}finally{busy(false);}
  },"button primary action schema-action");
  heading.append(description,preview);panel.append(heading);
  const scroll=node("div","table-scroll"), table=node("table"), head=node("thead"), headers=node("tr");
  ["Column","Type","Nullable","Default","Key"].forEach(text=>headers.append(node("th","",text)));
  head.append(headers);table.append(head);const body=node("tbody");
  schema.columns.forEach(column=>{
    const line=node("tr");
    line.append(node("td","",column.name),node("td","",column.type+(["CHAR","VARCHAR"].includes(column.type) ? "("+column.byteLength+")" : "")),
      node("td","",column.nullable ? "Yes" : "No"),node("td","",column.hasDefault ? column.defaultSql ?? displayValue(column.defaultValue) : "—"));
    const keys=node("td");if(column.primaryKey)keys.append(node("span","key-tag","PRIMARY"));if(column.unique)keys.append(node("span","key-tag","UNIQUE"));
    line.append(keys);body.append(line);
  });
  table.append(body);scroll.append(table);panel.append(scroll);detail.replaceChildren(panel);
  function section(title,lines) {
    if(!lines.length)return;const wrapper=node("div","schema-section"), content=node("div","panel");
    wrapper.append(node("h3","",title));lines.forEach(text=>content.append(node("div","metadata-row",text)));wrapper.append(content);detail.append(wrapper);
  }
  section("Relationships",schema.foreignKeys.map(key=>key.columns.join(", ")+" → "+key.parentTable+"("+(key.parentColumns.length ? key.parentColumns.join(", ") : "primary key")+") · DELETE "+key.onDelete+" · UPDATE "+key.onUpdate));
  section("Indexes",schema.indexes.map(index=>index.columns.join(", ")+" · "+(index.unique ? "Unique" : "Non-unique")+" · "+index.origin+" · "+index.name));
  section("CHECK constraints",schema.checks);
  const json=node("details","panel schema-json");json.append(node("summary","","View complete schema metadata"),node("pre","json-view",raw));detail.append(json,rows);controls();
}
function renderSuggestions() {
  $("question-suggestions").replaceChildren();
  state.tables.slice(0,3).forEach(table=>{
    const question='How many rows are in "'+table+'"?';
    $("question-suggestions").append(button(question,()=>{$("question-input").value=question;$("question-input").focus();},"suggestion"));
  });
}
async function ask(event) {
  event.preventDefault();const question=$("question-input").value.trim();if(!question)return;
  busy(true);globalError(null);$("ask-welcome").classList.add("hidden");
  const turn=node("div","chat-turn"),user=node("div","chat-question");
  user.append(node("span","avatar","YOU"),node("span","",question));turn.append(user);
  const answer=node("div","panel chat-answer"),header=node("div","chat-answer-header");
  header.append(icon("spark"),node("span","","AI SQL Engine"));answer.append(header);
  const text=node("div","answer-text","Drafting a query and checking the result…");answer.append(text);turn.append(answer);$("conversation").append(turn);
  try {
    const {data,raw}=await api("/api/ask",{question,conversation:state.conversation,answer:$("answer-mode").value});
    text.textContent=data.answer || "No answer was returned.";
    header.append(badge(data.status,data.status==="error" ? "error" : data.status==="complete" ? "success" : "warning"));
    if(data.assumptions?.length)answer.append(notice("Assumptions: "+data.assumptions.join("; ")));
    if(data.answer_note)answer.append(notice(data.answer_note));
    if(data.result?.sql){const details=node("details","answer-details");details.append(node("summary","","Executed SQL"),node("pre","",data.result.sql));answer.append(details);}
    answer.append(node("div","usage",(data.usage?.calls ?? 0)+" model calls · "+displayValue(data.usage?.input_tokens ?? "unreported")+" input tokens · "+displayValue(data.usage?.output_tokens ?? "unreported")+" output tokens · "+data.elapsed_ms+" ms"));
    answer.append(button("Export response",()=>download("answer.json",raw,"application/json"),"button small ghost"));
    if(data.result && data.status!=="error"){const result=node("div");turn.append(result);renderResult(result,data.result,raw);}
    if($("question-input").value.trim()===question)$("question-input").value="";
  }catch(error){text.textContent=error.message;header.append(badge("Failed","error"));}
  finally{await syncStatus();busy(false);}turn.scrollIntoView({behavior:"smooth",block:"start"});
}
async function refreshHistory() {
  const {data}=await api("/api/history"),host=$("history-list");host.replaceChildren();
  if(!data.length){empty(host,"A fresh workspace","Executed statements and questions will appear here.","clock");return;}
  data.forEach(entry=>{
    const card=node("div","panel history-card"),heading=node("div","history-heading"),kind=node("div","history-kind");
    kind.append(icon(entry.kind==="question" ? "spark" : entry.kind==="plan" ? "table" : "code"),node("span","",entry.kind==="question" ? "AI question" : entry.kind==="plan" ? "Query plan" : "SQL statement"));
    heading.append(kind,badge(entry.status,entry.status==="error" ? "error" : entry.status==="complete" ? "success" : "warning"));card.append(heading,node("pre","history-text",entry.text));
    if(entry.error)card.append(notice(entry.error,"error"));
    const footer=node("div","history-footer");footer.append(node("span","",new Date(entry.time*1000).toLocaleTimeString()+" · "+entry.elapsedMillis+" ms"));
    if(!entry.textTruncated)footer.append(button(entry.kind==="question" ? "Load question" : "Load SQL",()=>{
      if(entry.kind==="question"){$("question-input").value=entry.text;navigate("ask");}
      else{$("sql-input").value=entry.text;$("parameters-input").value=entry.parametersJson || "[]";editorPosition();navigate("sql");}
    },"button small secondary"));
    card.append(footer);host.append(card);
  });
}
function renderSettings() {
  if(!state.status)return;const host=$("settings-content"),info=state.status;host.replaceChildren();
  function card(title,entries){
    const panel=node("div","panel settings-card");panel.append(node("h2","",title));
    entries.forEach(([label,value])=>{const row=node("div","setting");row.append(node("span","",label),node("strong","",value ?? "—"));panel.append(row);});host.append(panel);
  }
  card("Database connection",[["Database",info.database],["Connection",info.connected ? "Connected" : "Disconnected"],["Transaction",info.transactionActive ? "Pending changes" : "Auto-commit"],["Access","Local owner · read and write"]]);
  card("AI connection",[["Model",info.model.name],["Explanation model",info.model.answerModel],["Provider",info.model.provider],["Status",info.model.note],["Dictionary",info.dictionary || "Schema context only"]]);
  card("Execution limits",Object.entries(info.limits).map(([name,value])=>[name.replace(/([A-Z])/g," $1").replace(/^./,c=>c.toUpperCase()),displayValue(value)]));
  card("Workspace behavior",[["Database session","Shared across browser tabs"],["AI SQL access","Read only"],["History","Last 50 operations · memory only"],["Chart rendering","Next analytics milestone"]]);
}
document.querySelectorAll("[data-page]").forEach(element=>element.addEventListener("click",()=>navigate(element.dataset.page)));
$("model-badge").addEventListener("click",()=>navigate("settings"));
$("run-button").addEventListener("click",()=>runSQL());$("explain-button").addEventListener("click",()=>runSQL(true));
for(const [id,sql] of [["begin-button","BEGIN;"],["commit-button","COMMIT;"],["rollback-button","ROLLBACK;"]])$(id).addEventListener("click",()=>runSQL(false,sql));
for(const event of ["input","click","keyup"])$("sql-input").addEventListener(event,editorPosition);
$("sql-input").addEventListener("scroll",()=>{$("line-numbers").scrollTop=$("sql-input").scrollTop;});
$("sql-input").addEventListener("keydown",event=>{
  if((event.ctrlKey || event.metaKey) && event.key==="Enter" && !$("run-button").disabled){event.preventDefault();runSQL();}
});
$("table-search").addEventListener("input",renderTables);
$("refresh-tables").addEventListener("click",async()=>{
  busy(true);try{await refreshTables();if(state.selected)await selectTable(state.selected);}catch(error){globalError(error);}finally{busy(false);}
});
$("refresh-history").addEventListener("click",()=>refreshHistory().catch(globalError));$("question-form").addEventListener("submit",ask);
$("clear-conversation").addEventListener("click",async()=>{
  busy(true);try{await api("/api/conversation/clear",{conversation:state.conversation});document.querySelectorAll(".chat-turn").forEach(turn=>turn.remove());$("ask-welcome").classList.remove("hidden");state.conversation=crypto.randomUUID();}
  catch(error){globalError(error);}finally{busy(false);}
});
window.addEventListener("hashchange",()=>{const page=location.hash.slice(1);if(state.page!==page)navigate(page,false);});
empty($("sql-result"),"Your results will appear here","Run a SQL statement to view typed rows, execution details, and export options.");
empty($("schema-detail"),"Choose a table","Select a table to inspect its schema and browse rows.","database");editorPosition();
async function start(){
  try{await refreshStatus();await refreshTables();navigate(location.hash.slice(1) || "sql",false);setInterval(()=>{if(!document.hidden)syncStatus();},5000);}
  catch(error){globalError(error);controls();}
}
start();
