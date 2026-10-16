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
 EditText from,to,employeeFilter;
 Spinner typeFilter;
 Set<String> selectedEmployees=new HashSet<>();boolean allEmployees=true;String selectedType="ALL";
 ArrayList<String> employeeIds=new ArrayList<>(),employeeLabels=new ArrayList<>();boolean[] employeeChecked;TextView employeeSummary;
 ArrayList<String[]> rows=new ArrayList<>();
 boolean loading=false;
 boolean loadAllPages=false;
 boolean reachedEnd=false;
 int fetched=0;
 int incoming=0,outgoing=0,missed=0;
 long totalDuration=0;
 Query reportQuery;
 DocumentSnapshot cursor;
 TextView summary,employeeBreakdown;
 Map<String,long[]> perEmployee=new TreeMap<>();
 int rejected=0,blocked=0,voicemail=0,externallyAnswered=0,other=0;
 void label(String message){UiKit.cardText(this,root,message,null);}
 void button(String title,Runnable task){Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setTextColor(Color.WHITE);GradientDrawable d=new GradientDrawable();d.setColor(UiKit.BRAND);d.setCornerRadius(28);b.setBackground(d);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,8,0,8);root.addView(b,lp);b.setOnClickListener(v->task.run());}
 void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
 @Override public void onCreate(Bundle b){
  super.onCreate(b);cid=getIntent().getStringExtra("company");uid=FirebaseAuth.getInstance().getUid();db=FirebaseFirestore.getInstance();
  if(cid==null||uid==null){finish();return;}
  db.collection("companies").document(cid).collection("members").document(uid).get().addOnSuccessListener(d->{
   String role=d.getString("role");if(!Boolean.TRUE.equals(d.getBoolean("approved"))||(!"owner".equals(role)&&!"admin".equals(role))){toast("Company admin required");finish();return;}render();
  }).addOnFailureListener(e->{toast(e.getLocalizedMessage());finish();});
 }
 void render(){
  ScrollView s=new ScrollView(this);root=new LinearLayout(this);UiKit.screen(this,root);s.addView(root);setContentView(s);
  TextView title=new TextView(this);title.setText("Company Call Reports");UiKit.heading(title);title.setTextColor(Color.rgb(15,58,54));root.addView(title);
  label("Filter calls by date (YYYY-MM-DD), then export results to CSV.");
  from=new EditText(this);from.setHint("From date · YYYY-MM-DD");root.addView(from);
  to=new EditText(this);to.setHint("To date · YYYY-MM-DD");root.addView(to);
  employeeSummary=new TextView(this);employeeSummary.setText("All employees");UiKit.label(employeeSummary);root.addView(employeeSummary);
  button("Select Employees (one or more)",()->selectEmployees());
  db.collection("companies").document(cid).collection("members").get().addOnSuccessListener(members->{
   employeeIds.clear();employeeLabels.clear();
   for(DocumentSnapshot member:members)if(Boolean.TRUE.equals(member.getBoolean("approved"))){
    employeeIds.add(member.getId());
    String sim=member.getString("sim");
    employeeLabels.add((sim==null||sim.isEmpty()?"Employee":sim)+" · "+member.getId().substring(0,Math.min(8,member.getId().length())));
   }
   employeeChecked=new boolean[employeeIds.size()];
  }).addOnFailureListener(e->toast("Unable to load employee filters: "+e.getLocalizedMessage()));
  typeFilter=new Spinner(this);String[] types={"ALL","INCOMING","OUTGOING","MISSED","REJECTED","BLOCKED","VOICEMAIL","ANSWERED_EXTERNALLY","OTHER"};
  ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,types);typeFilter.setAdapter(adapter);root.addView(typeFilter);
  label("Call logs are recorded for every approved employee, even when no customer numbers were assigned. CRM assignments do not limit this report.");
  label("Employee and call type filters apply to loaded pages. Load the entire date range for complete counts.");
  button("Load company calls",()->load());
  button("Load next 500 calls",()->loadNext());
  button("Load entire date range",()->{if(reportQuery==null){toast("Load report first");return;}loadAllPages=true;loadNext();});
  summary=new TextView(this);summary.setTextSize(16);summary.setTextColor(Color.rgb(15,88,80));root.addView(summary);
  employeeBreakdown=new TextView(this);UiKit.label(employeeBreakdown);root.addView(employeeBreakdown);
  button("Export filtered CSV",()->{if(rows.isEmpty()){toast("Load records before exporting");return;}Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.setType("text/csv");i.addCategory(Intent.CATEGORY_OPENABLE);i.putExtra(Intent.EXTRA_TITLE,"sharaco_calls.csv");startActivityForResult(i,SAVE_CSV);});
  button("Back",()->finish());
 }
 void selectEmployees(){
  if(employeeChecked==null){toast("Employee list is still loading");return;}
  String[] names=employeeLabels.toArray(new String[0]);
  boolean[] draft=employeeChecked.clone();
  new android.app.AlertDialog.Builder(this).setTitle("Choose employees")
   .setMultiChoiceItems(names,draft,(dialog,which,checked)->draft[which]=checked)
   .setNeutralButton("All employees",(dialog,which)->{
    allEmployees=true;selectedEmployees.clear();employeeChecked=new boolean[employeeIds.size()];
    employeeSummary.setText("All employees");reportQuery=null;
   }).setNegativeButton("Cancel",null).setPositiveButton("Apply",(dialog,which)->{
    HashSet<String> choice=new HashSet<>();
    for(int i=0;i<draft.length;i++)if(draft[i])choice.add(employeeIds.get(i));
    if(choice.isEmpty()){toast("Choose one or more employees, or use All employees");return;}
    allEmployees=false;selectedEmployees=choice;employeeChecked=draft;
    employeeSummary.setText(choice.size()+" employee(s) selected");reportQuery=null;
   }).show();
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
  selectedType=typeFilter.getSelectedItem().toString();
  loadAllPages=false;reachedEnd=false;rows.clear();fetched=0;incoming=0;outgoing=0;missed=0;rejected=0;blocked=0;voicemail=0;externallyAnswered=0;other=0;totalDuration=0;perEmployee.clear();cursor=null;
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
    String type=d.getString("type");
    if(!allEmployees && !selectedEmployees.contains(d.getString("uid")))continue;
    if(!"ALL".equals(selectedType) && !selectedType.equals(type))continue;
    if("INCOMING".equals(type))incoming++;else if("OUTGOING".equals(type))outgoing++;else if("MISSED".equals(type))missed++;else if("REJECTED".equals(type))rejected++;else if("BLOCKED".equals(type))blocked++;else if("VOICEMAIL".equals(type))voicemail++;else if("ANSWERED_EXTERNALLY".equals(type))externallyAnswered++;else other++;
    Long duration=d.getLong("durationSec");long seconds=duration==null?0:duration;totalDuration+=seconds;
    String employee=d.getString("uid");if(employee==null)employee="Unknown";
    long[] metric=perEmployee.computeIfAbsent(employee,k->new long[5]);metric[0]++;metric[1]+=seconds;
    if("INCOMING".equals(type))metric[2]++;else if("OUTGOING".equals(type))metric[3]++;else if("MISSED".equals(type))metric[4]++;
    rows.add(new String[]{d.getString("uid"),d.getString("sim"),d.getString("remoteNumber"),type,String.valueOf(at),String.valueOf(seconds),d.getString("contactName")});
   }
   if(!s.isEmpty())cursor=s.getDocuments().get(s.size()-1);
   reachedEnd=s.size()<500;
   summary.setText("Matching calls: "+rows.size()+" | Incoming: "+incoming+" | Outgoing: "+outgoing+" | Missed: "+missed+" | Rejected: "+rejected+" | Blocked: "+blocked+" | Voicemail: "+voicemail+" | Answered elsewhere: "+externallyAnswered+" | Other: "+other+" | Total duration: "+(totalDuration/60)+" minutes | Average: "+(rows.isEmpty()?0:totalDuration/rows.size())+" sec"+(s.size()<500?" | End of records":" | More pages available"));
   StringBuilder breakdown=new StringBuilder("EMPLOYEE PERFORMANCE (LOADED CALLS)\\n");
   for(Map.Entry<String,long[]> person:perEmployee.entrySet()){long[] m=person.getValue();breakdown.append(person.getKey()).append(" · Calls ").append(m[0]).append(" · Incoming ").append(m[2]).append(" · Outgoing ").append(m[3]).append(" · Missed ").append(m[4]).append(" · Minutes ").append(m[1]/60).append("\\n");}
   employeeBreakdown.setText(breakdown.toString());
   if(loadAllPages && !reachedEnd){loadNext();}else if(loadAllPages){loadAllPages=false;toast("All available matching calls loaded");}
  }).addOnFailureListener(e->{loading=false;loadAllPages=false;toast("Report error: "+e.getLocalizedMessage());});
 }
 String csv(String s){
  String value=s==null?"":s;
  if(!value.isEmpty() && "=+-@".indexOf(value.charAt(0))>=0)value="'"+value;
  String quote=String.valueOf((char)34);
  return quote+value.replace(quote,quote+quote)+quote;
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
