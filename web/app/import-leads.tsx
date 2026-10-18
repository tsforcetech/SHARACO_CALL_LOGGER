'use client';
import {useEffect,useState} from 'react';
import {collection,doc,getDocsFromServer,query,where,limit,runTransaction,serverTimestamp} from 'firebase/firestore';
import {parsePhoneNumberFromString,CountryCode} from 'libphonenumber-js';
import Papa from 'papaparse';
import {firebase} from '../lib/firebase';
type ImportRow={row:number;name:string;phone:string;company:string;remarks:string;error:string};
export function ImportLeads({company,onComplete}:{company:string;onComplete:()=>void}) {
 const [employees,setEmployees]=useState<{uid:string;name:string}[]>([]),[assigned,setAssigned]=useState<string[]>([]),[rows,setRows]=useState<ImportRow[]>([]),[busy,setBusy]=useState(false),[message,setMessage]=useState(''),[country,setCountry]=useState('AE'),[progress,setProgress]=useState(0);
 useEffect(()=>{let cancelled=false;getDocsFromServer(collection(firebase().db,'companies',company,'members')).then(s=>{if(!cancelled)setEmployees(s.docs.filter(d=>d.data().approved).map(d=>({uid:d.id,name:d.data().name||d.id})));}).catch(e=>{if(!cancelled)setMessage(String(e));});return()=>{cancelled=true;};},[company]);
 function download(name:string,content:string){const url=URL.createObjectURL(new Blob([content],{type:'text/csv;charset=utf-8'}));const a=document.createElement('a');a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}
 async function upload(file:File){setBusy(true);setMessage('');setRows([]);try{
  if(file.size>5*1024*1024)throw new Error('Choose a file smaller than 5 MB.');
  let source:Record<string,unknown>[];
  if(file.name.toLowerCase().endsWith('.xlsx')){
   const ExcelJS=await import('exceljs');const workbook=new ExcelJS.Workbook();await workbook.xlsx.load(await file.arrayBuffer());const sheet=workbook.worksheets[0];if(!sheet)throw new Error('The workbook has no worksheet.');
   if(sheet.rowCount>1001)throw new Error('Import up to 1,000 rows at a time.');
   const headers:string[]=[];sheet.getRow(1).eachCell((c,n)=>{headers[n]=c.text.trim();});source=[];
   sheet.eachRow((row,n)=>{if(n===1)return;const out:Record<string,unknown>={};headers.forEach((h,i)=>{if(h)out[h]=row.getCell(i).text;});source.push(out);});
  }else if(file.name.toLowerCase().endsWith('.csv')){const result=Papa.parse<Record<string,unknown>>(await file.text(),{header:true,skipEmptyLines:true,transformHeader:h=>h.trim()});if(result.errors.length)throw new Error(result.errors[0].message);source=result.data;}
  else throw new Error('Choose an Excel .xlsx or CSV file.');
  if(source.length>1000)throw new Error('Import up to 1,000 rows at a time.');
  const seen=new Set<string>();setRows(source.map((r,i)=>{const name=String(r['Customer Name']||'').trim(),raw=String(r['Mobile Number']||'').trim(),number=parsePhoneNumberFromString(raw,country as CountryCode);let error='';
   if(!name||name.length>160)error='Customer Name is required (maximum 160 characters).';else if(!number?.isValid())error='Invalid Mobile Number.';else if(seen.has(number.number))error='Duplicate phone in uploaded file.';
   if(number?.isValid())seen.add(number.number);
   return {row:i+2,name,phone:number?.number||raw,company:String(r.Company||''),remarks:String(r.Remarks||''),error};
  }));
 }catch(e){setMessage(String(e));}finally{setBusy(false);}}
 async function save(){setBusy(true);setProgress(0);setMessage('');let created=0,skipped=0,failed=0;const results=[...rows];const {db,auth}=firebase();try{
  const valid=rows.filter(r=>!r.error);if(!assigned.length)throw new Error('Select one or more employees.');
  for(let i=0;i<valid.length;i++){
   const row=valid[i];try{
    const existing=await getDocsFromServer(query(collection(db,'companies',company,'leads'),where('primaryPhone','==',row.phone),limit(1)));
    if(!existing.empty){skipped++;}else{
     const id=`phone_${row.phone.slice(1)}`,ref=doc(db,'companies',company,'leads',id);
     const result=await runTransaction(db,async tx=>{if((await tx.get(ref)).exists())return false;
      tx.set(ref,{leadId:id,companyId:company,customerName:row.name,primaryPhone:row.phone,companyName:row.company,remarks:row.remarks,assignedTo:assigned[i%assigned.length],campaignId:'',status:'new',priority:'normal',tags:[],terminal:false,doNotCall:false,version:1,claimExpiresAt:0,createdAt:serverTimestamp(),updatedAt:serverTimestamp(),createdBy:auth.currentUser!.uid});return true;});
     if(result)created++;else skipped++;
    }
   }catch(e){failed++;results[rows.indexOf(row)]={...row,error:String(e)};}
   setProgress(i+1);
  }
  setRows(results);setMessage(`Finished: ${created} created, ${skipped} existing numbers skipped, ${failed} failed. Existing lead history was preserved.`);onComplete();
 }catch(e){setMessage(String(e));}finally{setBusy(false);}}
 return <details className="form-card" style={{padding:20,marginBottom:24}}><summary>Import Excel / CSV leads</summary><p>Use the template headers. Existing company phone numbers are skipped. Keep this page open until the import finishes.</p><button className="secondary" onClick={()=>download('CallTrack-Lead-Template.csv','Customer Name,Mobile Number,Company,Remarks\r\n')}>Download template</button><label>Default country<select value={country} disabled={busy} onChange={e=>setCountry(e.target.value)}><option value="AE">United Arab Emirates</option><option value="IN">India</option><option value="SA">Saudi Arabia</option><option value="US">United States</option></select></label><input aria-label="Upload leads" type="file" accept=".csv,.xlsx" disabled={busy} onChange={e=>{if(e.target.files?.[0])void upload(e.target.files[0]);}}/><p>Distribute equally between selected employees:</p><div className="outcomes">{employees.map(e=><button key={e.uid} disabled={busy} className={assigned.includes(e.uid)?'':'secondary'} onClick={()=>setAssigned(a=>a.includes(e.uid)?a.filter(x=>x!==e.uid):[...a,e.uid])}>{e.name}</button>)}</div>{rows.length>0&&<><p>{rows.length} rows · {rows.filter(r=>!r.error).length} ready · {rows.filter(r=>r.error).length} invalid</p><div className="table-scroll"><table><thead><tr><th>Row</th><th>Customer</th><th>Phone</th><th>Validation</th></tr></thead><tbody>{rows.slice(0,20).map(r=><tr key={r.row}><td>{r.row}</td><td>{r.name}</td><td>{r.phone}</td><td>{r.error||'Ready'}</td></tr>)}</tbody></table></div>{rows.length>20&&<p>Showing the first 20 rows.</p>}<button disabled={busy||!assigned.length||!rows.some(r=>!r.error)} onClick={save}>{busy?`Importing · ${progress} processed`:'Confirm import'}</button><button className="secondary" onClick={()=>download('CallTrack-Import-Errors.csv',Papa.unparse(rows.filter(r=>r.error).map(r=>({Row:r.row,'Customer Name':r.name,'Mobile Number':r.phone,Error:r.error})),{escapeFormulae:true}))}>Download invalid rows</button></>}{message&&<p role="status">{message}</p>}</details>;
}
