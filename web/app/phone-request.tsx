'use client';
import {useEffect,useState} from 'react';
import {doc,onSnapshot} from 'firebase/firestore';
import {api,firebase} from '../lib/firebase';
export function PhoneRequest({company,lead}:{company:string;lead:string}) {
 const [request,setRequest]=useState(''),[status,setStatus]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState('');
 useEffect(()=>{setRequest('');setStatus('');setError('');},[company,lead]);
 useEffect(()=>{if(!request)return;let expires=Date.now()+55000;
  const stop=onSnapshot(doc(firebase().db,'companies',company,'callRequests',request),s=>{const data=s.data();if(!data)return;expires=data.expiresAt;setStatus(data.status);},e=>setError(`${e.code}: ${e.message}`));
  const timer=setInterval(()=>{if(Date.now()>expires)setStatus(s=>['CREATED','ACKNOWLEDGED'].includes(s)?'EXPIRED':s);},1000);
  return()=>{stop();clearInterval(timer);};
 },[company,request]);
 async function send(){setBusy(true);setError('');try{const result=await api(company,'call',{leadId:lead});setRequest(result.requestId);setStatus('CREATED');}catch(e){setError(String(e));}finally{setBusy(false);}}
 return <div><button disabled={busy||['CREATED','ACKNOWLEDGED'].includes(status)} onClick={send}>{['CREATED','ACKNOWLEDGED'].includes(status)?'Waiting for your phone…':'Send call to my phone'}</button><p>Enable web calling in the Android app and keep it open. Confirm the number on your phone.</p>{status&&<small>{status==='DIALER_OPENED'?'Phone accepted the request. Call completion still needs verification.':status}</small>}{error&&<div role="alert" className="error">{error}</div>}</div>;
}
