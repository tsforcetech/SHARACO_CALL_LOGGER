"use client";
import { useEffect, useState } from 'react';
import { addDoc, collection, limit, onSnapshot, orderBy, query, serverTimestamp, where } from 'firebase/firestore';
import { firebase, Row } from '../lib/firebase';
import { plain } from '../lib/spark';
import { achieved } from '../lib/report-metrics';

const day = (d = new Date()) => `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`;
const millis = (d:string) => new Date(`${d}T00:00:00`).getTime();
const endDay = (d:string) => { const n=new Date(`${d}T00:00:00`); n.setDate(n.getDate()+1); return n.getTime(); };
const metrics:Record<string,string> = { calls:'All calls', outgoing:'Outgoing calls', connected:'Connected calls', minutes:'Talk minutes', interested:'Interested outcomes', converted:'Converted outcomes' };
const number = (r:Row,k:string) => Number(r[k]||0);
export function Reports({company,uid,admin,view}:{company:string;uid:string;admin:boolean;view:string}) {
 const [from,setFrom]=useState(day()),[to,setTo]=useState(day()),[employee,setEmployee]=useState(''),[type,setType]=useState(''),[search,setSearch]=useState('');
 const [calls,setCalls]=useState<Row[]>([]),[attempts,setAttempts]=useState<Row[]>([]),[targets,setTargets]=useState<Row[]>([]),[members,setMembers]=useState<Row[]>([]);
 const [errors,setErrors]=useState<Record<string,string>>({}),[loaded,setLoaded]=useState<Record<string,boolean>>({}),[saving,setSaving]=useState(false),[notice,setNotice]=useState('');
 const [page,setPage]=useState(1);
 const start=millis(from),end=endDay(to),valid=Number.isFinite(start)&&Number.isFinite(end)&&end>start&&end-start<=94*86400000;
 const selected=admin?employee:uid;
 useEffect(()=>{
  setCalls([]);setAttempts([]);setTargets([]);setMembers([]);setErrors({});setLoaded({});setPage(1);
  if(!valid)return;
  const db=firebase().db;
  const listen=(name:string,constraints:any[],setter:(r:Row[])=>void)=>onSnapshot(query(collection(db,'companies',company,name),...constraints,limit(10001)),s=>{
   setter(s.docs.map(d=>({...plain(d.data()),id:d.id})));setLoaded(v=>({...v,[name]:true}));setErrors(v=>({...v,[name]:''}));
  },e=>{setter([]);setErrors(v=>({...v,[name]:`${name}: ${e.code} — ${e.message}`}));});
  const stops=[
   listen('call_logs',[...(selected?[where('uid','==',selected)]:[]),where('startedAt','>=',start),where('startedAt','<',end),orderBy('startedAt','asc')],setCalls),
   listen('crmCallAttempts',[...(selected?[where('employeeUid','==',selected)]:[]),where('createdAt','>=',new Date(start)),where('createdAt','<',new Date(end)),orderBy('createdAt','asc')],setAttempts),
   listen('targetDefinitions',selected?[where('employeeUid','==',selected)]:[],setTargets),
  ];
  if(admin)stops.push(listen('members',[],setMembers));
  return ()=>stops.forEach(stop=>stop());
 },[company,uid,admin,selected,start,end,valid]);
 const name=(id:unknown)=>members.find(m=>m.id===id)?.name|| (id===uid?'You':id);
 const truncated=[calls,attempts,targets,members].some(r=>r.length>=10001);
 const complete=loaded.call_logs&&loaded.crmCallAttempts&&!errors.call_logs&&!errors.crmCallAttempts&&!truncated;
 const summary=(metric:string,c=calls,a=attempts)=>achieved(metric,c,a).toLocaleString(undefined,{maximumFractionDigits:1});
 const filtered=calls.filter(c=>(!type||c.type===type)&&`${c.remoteNumber} ${c.contactName}`.toLowerCase().includes(search.toLowerCase())).sort((a,b)=>number(b,'startedAt')-number(a,'startedAt'));
 const employees=Array.from(new Set([...members.map(m=>m.id),...calls.map(c=>String(c.uid)),...attempts.map(a=>String(a.employeeUid))])).filter(id=>!selected||id===selected);
 async function save(e:React.FormEvent<HTMLFormElement>){
  e.preventDefault();const form=e.currentTarget,d=new FormData(form);setSaving(true);setNotice('');
  try{
   const employeeUid=String(d.get('employee')),period=String(d.get('period')),metric=String(d.get('metric')),value=Number(d.get('value')),startsOn=String(d.get('startsOn'));
   const a=new Date(`${startsOn}T00:00:00`),b=new Date(a);
   if(period==='daily')b.setDate(b.getDate()+1);else if(period==='weekly')b.setDate(b.getDate()+7);else b.setMonth(b.getMonth()+1);
   if(!admin||!members.some(m=>m.id===employeeUid&&m.approved===true)||!metrics[metric]||!Number.isFinite(value)||value<=0||!Number.isFinite(a.getTime()))throw Error('Select an approved employee, metric, start date and positive target.');
   await addDoc(collection(firebase().db,'companies',company,'targetDefinitions'),{name:`${metrics[metric]} target`,employeeUid,metric,value,period,startAt:a.getTime(),endAt:b.getTime(),timeZone:Intl.DateTimeFormat().resolvedOptions().timeZone,createdAt:serverTimestamp(),createdBy:uid});
   setFrom(startsOn);b.setDate(b.getDate()-1);setTo(day(b));setNotice('Target created. Progress updates automatically for this period.');
  }catch(e){setNotice(String(e));}finally{setSaving(false);}
 }
 function exportCalls(){
  const cell=(v:unknown)=>`"${String(v??'').replace(/^[=+@-]/,"'$&").replaceAll('"','""')}"`;
  const data=[['Employee','Number','Contact','Type','Started','Duration seconds'],...filtered.map(c=>[name(c.uid),c.remoteNumber,c.contactName,c.type,new Date(number(c,'startedAt')).toISOString(),c.durationSec])];
  const url=URL.createObjectURL(new Blob(['\ufeff'+data.map(r=>r.map(cell).join(',')).join('\r\n')],{type:'text/csv;charset=utf-8'}));const a=document.createElement('a');a.href=url;a.download=`call-history-${from}-${to}.csv`;a.click();URL.revokeObjectURL(url);
 }
 return <section className="reports">
  <div className="form-card report-filters">
   <label>From<input type="date" value={from} onChange={e=>{setFrom(e.target.value);setPage(1);}}/></label>
   <label>To<input type="date" value={to} onChange={e=>{setTo(e.target.value);setPage(1);}}/></label>
   {admin&&<label>Employee<select value={employee} onChange={e=>{setEmployee(e.target.value);setPage(1);}}><option value="">All employees</option>{members.map(m=><option key={m.id} value={m.id}>{String(m.name||m.id)}</option>)}</select></label>}
   <small>Times: {Intl.DateTimeFormat().resolvedOptions().timeZone}. Up to 93 days per report.</small>
  </div>
  {!valid&&<div className="error">Choose a valid date range of up to 93 days.</div>}
  {Object.values(errors).filter(Boolean).map(e=><div role="alert" className="error" key={e}>{e}</div>)}
  {truncated&&<div className="error">This range exceeds 10,000 records. Narrow the date range or select one employee. Totals are incomplete.</div>}
  <p>Device call history is separate from manually recorded CRM outcomes. Connected means an incoming or outgoing call with recorded duration above zero. Only calls already synced by Android appear here.</p>
  <div className="stats-grid">{Object.entries(metrics).map(([k,label])=><article key={k} className="stat-card"><span>{label}</span><strong>{complete?summary(k):'—'}</strong><small>{complete?'Selected period':'Waiting for complete data'}</small></article>)}</div>
  {view==='targetDefinitions'&&admin&&<form className="form-card report-filters" onSubmit={save}>
   <label>Employee<select name="employee" required><option value="">Select employee</option>{members.filter(m=>m.approved===true).map(m=><option key={m.id} value={m.id}>{String(m.name||m.id)}</option>)}</select></label>
   <label>Metric<select name="metric">{Object.entries(metrics).map(([k,l])=><option key={k} value={k}>{l}</option>)}</select></label>
   <label>Period<select name="period"><option value="daily">Daily (1 day)</option><option value="weekly">Weekly (7 days)</option><option value="monthly">Monthly (1 month)</option></select></label>
   <label>Start date<input type="date" name="startsOn" required defaultValue={day()}/></label>
   <label>Target<input name="value" type="number" min="1" step="1" required/></label><button disabled={saving||!loaded.members}>{saving?'Saving…':'Create target'}</button>
   <small>Each target covers one period starting on the selected date. Create another target for the next period.</small>
  </form>}
  {notice&&<div role="status" className="notice">{notice}</div>}
  <div className="table-card"><div className="table-toolbar"><strong>Target achievement</strong></div><div className="table-scroll"><table><thead><tr><th>Employee</th><th>Target / period</th><th>Achievement</th><th>Remaining</th></tr></thead><tbody>{targets.filter(t=>!t.startAt||number(t,'startAt')<end&&number(t,'endAt')>start).map(t=>{
   const a=number(t,'startAt'),b=number(t,'endAt'),supported=!!metrics[String(t.metric)],covered=complete&&a>0&&b>a&&start<=a&&end>=b&&supported;
   const n=achieved(String(t.metric),calls.filter(c=>c.uid===t.employeeUid&&number(c,'startedAt')>=a&&number(c,'startedAt')<b),attempts.filter(c=>c.employeeUid===t.employeeUid&&number(c,'createdAt')>=a&&number(c,'createdAt')<b));const goal=number(t,'value');
   return <tr key={t.id}><td>{String(name(t.employeeUid))}</td><td>{metrics[String(t.metric)]||String(t.metric)} · {goal}<small>{a?`${new Date(a).toLocaleDateString()} – ${new Date(b-1).toLocaleDateString()}`:'Legacy target: dates not configured'}</small></td><td>{covered?<><strong>{n.toFixed(1)} / {goal} ({goal>0?Math.round(n/goal*100):0}%)</strong><progress max={Math.max(1,goal)} value={Math.min(n,goal)}/></>:a>0?<button className="text-button" onClick={()=>{setFrom(day(new Date(a)));setTo(day(new Date(b-1)));}}>View full target period</button>:'Create a dated target'}</td><td>{covered?Math.max(0,goal-n).toFixed(1):'—'}</td></tr>;
  })}</tbody></table>{loaded.targetDefinitions&&!targets.length&&<p className="empty">No targets assigned. {admin?'Open Targets to create one.':'Your administrator can assign targets.'}</p>}</div></div>
  {(view==='dashboard'||view==='reports')&&<div className="table-card"><div className="table-toolbar"><strong>{admin?'Employee performance':'Your performance'}</strong></div><div className="table-scroll"><table><thead><tr><th>Employee</th>{Object.values(metrics).map(m=><th key={m}>{m}</th>)}<th>Missed</th><th>Average connected minutes</th></tr></thead><tbody>{employees.map(id=>{const c=calls.filter(r=>r.uid===id),a=attempts.filter(r=>r.employeeUid===id),connected=achieved('connected',c,a);return <tr key={id}><td>{String(name(id))}</td>{Object.keys(metrics).map(k=><td key={k}>{complete?summary(k,c,a):'—'}</td>)}<td>{complete?c.filter(r=>r.type==='MISSED').length:'—'}</td><td>{complete&&connected?(achieved('minutes',c,a)/connected).toFixed(1):'—'}</td></tr>;})}</tbody></table></div></div>}
  <div className="table-card"><div className="table-toolbar"><strong>Synced call history · {filtered.length}</strong><label>Call type<select value={type} onChange={e=>{setType(e.target.value);setPage(1);}}><option value="">All types</option>{['INCOMING','OUTGOING','MISSED','REJECTED','BLOCKED','VOICEMAIL','ANSWERED_EXTERNALLY','UNKNOWN','OTHER'].map(t=><option key={t}>{t}</option>)}</select></label><label>Search<input placeholder="Number or contact" value={search} onChange={e=>{setSearch(e.target.value);setPage(1);}}/></label><button className="secondary" disabled={!loaded.call_logs||!!errors.call_logs||truncated} onClick={exportCalls}>Export CSV</button></div>
  <div className="table-scroll"><table><thead><tr><th>Employee</th><th>Contact / number</th><th>Direction / status</th><th>Time</th><th>Duration</th></tr></thead><tbody>{filtered.slice((page-1)*50,page*50).map(c=><tr key={c.id}><td>{String(name(c.uid))}</td><td>{String(c.contactName||'Unknown contact')}<small>{String(c.remoteNumber||'Hidden number')}</small></td><td><span className="badge">{String(c.type)}</span><small>{['INCOMING','OUTGOING','ANSWERED_EXTERNALLY'].includes(String(c.type))?(number(c,'durationSec')>0?'Connected':'No recorded connection'):'Device status'}</small></td><td>{new Date(number(c,'startedAt')).toLocaleString()}</td><td>{Math.floor(number(c,'durationSec')/60)}m {number(c,'durationSec')%60}s</td></tr>)}</tbody></table></div>
  {loaded.call_logs&&!filtered.length&&<p className="empty">No synced calls match this date range and filters.</p>}
  <div className="table-toolbar"><button className="secondary" disabled={page<=1} onClick={()=>setPage(p=>p-1)}>Previous</button><span>Page {page} of {Math.max(1,Math.ceil(filtered.length/50))}</span><button className="secondary" disabled={page*50>=filtered.length} onClick={()=>setPage(p=>p+1)}>Next</button></div></div>
 </section>;
}
