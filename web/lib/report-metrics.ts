type RecordData = Record<string, unknown>;
export function achieved(metric:string,calls:RecordData[],attempts:RecordData[]) {
  if(metric==='interested'||metric==='converted') return attempts.filter(a=>a.dispositionId===metric).length;
  if(metric==='minutes') return calls.reduce((n,c)=>n+Number(c.durationSec||0),0)/60;
  if(metric==='connected') return calls.filter(c=>['INCOMING','OUTGOING','ANSWERED_EXTERNALLY'].includes(String(c.type))&&Number(c.durationSec)>0).length;
  if(metric==='outgoing') return calls.filter(c=>c.type==='OUTGOING').length;
  return calls.length;
}
