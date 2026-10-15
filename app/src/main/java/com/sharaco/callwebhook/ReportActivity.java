package com.sharaco.callwebhook;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.widget.*;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.io.OutputStream;

public class ReportActivity extends Activity {
 private static final int SAVE_CSV=824;
 String cid,uid;
 FirebaseFirestore db;
 LinearLayout root;
 EditText from,to;
 ArrayList<String[]> rows=new ArrayList<>();
 boolean loading=false;
 boolean loadAllPages=false;
 boolean reachedEnd=false;
 int fetched=0;
 int incoming=0,outgoing=0,missed=0;
 long totalDuration=0;
 Query reportQuery;
 DocumentSnapshot cursor;
 TextView summary;
 void label(String message){TextView t=new TextView(this);t.setText(message);t.setTextColor(Color.rgb(45,69,72));t.setTextSize(16);t.setPadding(0,14,0,9);root.addView(t);}
 void button(String title,Runnable task){Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setTextColor(Color.WHITE);GradientDrawable d=new GradientDrawable();d.setColor(Color.rgb(0,171,143));d.setCornerRadius(28);b.setBackground(d);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,8,0,8);root.addView(b,lp);b.setOnClickListener(v->task.run());}
 void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
 @Override public void onCreate(Bundle b){
  super.onCreate(b);cid=getIntent().getStringExtra("company");uid=FirebaseAuth.getInstance().getUid();db=FirebaseFirestore.getInstance();
  if(cid==null||uid==null){finish();return;}
  db.collection("companies").document(cid).collection("members").document(uid).get().addOnSuccessListener(d->{
   String role=d.getString("role");if(!Boolean.TRUE.equals(d.getBoolean("approved"))||(!"owner".equals(role)&&!"admin".equals(role))){toast("Company admin required");finish();return;}render();
  }).addOnFailureListener(e->{toast(e.getLocalizedMessage());finish();});
 }
 void render(){
  ScrollView s=new ScrollView(this);root=new LinearLayout(this);root.setOrientation(1);root.setPadding(24,32,24,32);root.setBackgroundColor(Color.rgb(247,250,250));s.addView(root);setContentView(s);
  TextView title=new TextView(this);title.setText("Company Call Reports");title.setTextSize(25);title.setTypeface(null,Typeface.BOLD);title.setTextColor(Color.rgb(15,58,54));root.addView(title);
  label("Filter calls by date (YYYY-MM-DD), then export results to CSV.");
  from=new EditText(this);from.setHint("From date · YYYY-MM-DD");root.addView(from);
  to=new EditText(this);to.setHint("To date · YYYY-MM-DD");root.addView(to);
  button("Load company calls",()->load());
  button("Load next 500 calls",()->loadNext());
  button("Load entire date range",()->{if(reportQuery==null){toast("Load report first");return;}loadAllPages=true;loadNext();});
  summary=new TextView(this);summary.setTextSize(16);summary.setTextColor(Color.rgb(15,88,80));root.addView(summary);
  button("Export filtered CSV",()->{if(rows.isEmpty()){toast("Load records before exporting");return;}Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.setType("text/csv");i.addCategory(Intent.CATEGORY_OPENABLE);i.putExtra(Intent.EXTRA_TITLE,"sharaco_calls.csv");startActivityForResult(i,SAVE_CSV);});
  button("Back",()->finish());
 }
 Long dateMs(String s){
  if(s.isEmpty())return null;
  try{SimpleDateFormat df=new SimpleDateFormat("yyyy-MM-dd",Locale.US);df.setLenient(false);return df.parse(s).getTime();}
  catch(Exception e){return -1L;}
 }
 void load(){
  Long begin=dateMs(from.getText().toString().trim()),end=dateMs(to.getText().toString().trim());
  if((begin!=null&&begin<0)||(end!=null&&end<0)){toast("Use valid dates in YYYY-MM-DD format");return;}
  if(begin!=null&&end!=null&&begin>end){toast("From date must be before To date");return;}
  if(loading)return;
  loadAllPages=false;reachedEnd=false;rows.clear();fetched=0;incoming=0;outgoing=0;missed=0;totalDuration=0;cursor=null;
  reportQuery=db.collection("companies").document(cid).collection("call_logs").orderBy("startedAt",Query.Direction.DESCENDING);
  if(begin!=null)reportQuery=reportQuery.whereGreaterThanOrEqualTo("startedAt",begin);
  if(end!=null)reportQuery=reportQuery.whereLessThan("startedAt",end+86400000L);
  summary.setText("Loading filtered calls...");
  loadNext();
 }
 void loadNext(){
  if(loading)return;
  if(reportQuery==null){toast("Select Load company calls first");return;}
  if(reachedEnd){loadAllPages=false;toast("All records loaded");return;}
  loading=true;
  Query q=reportQuery.limit(500);if(cursor!=null)q=q.startAfter(cursor);
  q.get().addOnSuccessListener(s->{
   loading=false;fetched+=s.size();
   for(DocumentSnapshot d:s){
    Long at=d.getLong("startedAt");if(at==null)continue;
    String type=d.getString("type");if("INCOMING".equals(type))incoming++;if("OUTGOING".equals(type))outgoing++;if("MISSED".equals(type))missed++;
    Long duration=d.getLong("durationSec");long seconds=duration==null?0:duration;totalDuration+=seconds;
    rows.add(new String[]{d.getString("uid"),d.getString("sim"),d.getString("remoteNumber"),type,String.valueOf(at),String.valueOf(seconds),d.getString("contactName")});
   }
   if(!s.isEmpty())cursor=s.getDocuments().get(s.size()-1);
   reachedEnd=s.size()<500;
   summary.setText("Loaded: "+rows.size()+" calls | Incoming "+incoming+" | Outgoing "+outgoing+" | Missed "+missed+" | Duration "+(totalDuration/60)+" minutes"+(s.size()<500?" | End of records":" | More pages available"));
   if(loadAllPages && !reachedEnd){loadNext();}else if(loadAllPages){loadAllPages=false;toast("All available matching calls loaded");}
  }).addOnFailureListener(e->{loading=false;loadAllPages=false;toast("Report error: "+e.getLocalizedMessage());});
 }
 String csv(String s){
  String value=s==null?"":s;
  if(!value.isEmpty() && "=+-@".indexOf(value.charAt(0))>=0)value="'"+value;
  return "\\"" + value.replace("\\"","\\"\\"") + "\\"";
 }
 @Override protected void onActivityResult(int request,int result,Intent data){
  super.onActivityResult(request,result,data);
  if(request!=SAVE_CSV||result!=RESULT_OK||data==null||data.getData()==null)return;
  try(OutputStream out=getContentResolver().openOutputStream(data.getData())){
   if(out==null)throw new IllegalStateException("File cannot be opened");
   out.write("employeeUid,sim,remoteNumber,type,startedAtMs,durationSeconds,contactName\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
   for(String[] row:rows){StringBuilder line=new StringBuilder();for(int i=0;i<row.length;i++){if(i>0)line.append(',');line.append(csv(row[i]));}line.append('\n');out.write(line.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
   toast("Exported "+rows.size()+" calls");
  }catch(Exception e){toast("CSV export error: "+e.getLocalizedMessage());}
 }
}
