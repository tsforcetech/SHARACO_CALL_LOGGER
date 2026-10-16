package com.sharaco.callwebhook;

import android.app.Activity;
import android.os.Bundle;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.*;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class CrmActivity extends Activity {
 FirebaseFirestore db;
 String company,uid,role;
 LinearLayout content;
 final Set<String> selectedAgents=new HashSet<>();boolean filterAgents=false;
 boolean manager(){return "owner".equals(role)||"admin".equals(role);}
 GradientDrawable bg(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(30);return d;}
 void page(String title){
  ScrollView scroll=new ScrollView(this);
  content=new LinearLayout(this);UiKit.screen(this,content);
  scroll.addView(content);setContentView(scroll);
  TextView t=new TextView(this);t.setText(title);t.setTextColor(Color.rgb(21,39,47));UiKit.heading(t);content.addView(t);
  label("Company workspace  /  Follow-ups");
 }
 void label(String s){UiKit.cardText(this,content,s,null);}
 void button(String text,Runnable action){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextColor(Color.WHITE);UiKit.action(this,b,false);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,7,0,7);content.addView(b,lp);b.setOnClickListener(v->action.run());}
 EditText input(String hint){EditText e=new EditText(this);e.setHint(hint);UiKit.input(this,e);content.addView(e);return e;}
 void toast(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
 CollectionReference leads(){return db.collection("companies").document(company).collection("assigned_numbers");}
 @Override public void onCreate(Bundle b){
  super.onCreate(b);db=FirebaseFirestore.getInstance();uid=FirebaseAuth.getInstance().getUid();company=getIntent().getStringExtra("company");
  if(uid==null||company==null||company.isEmpty()){finish();return;}
  db.collection("companies").document(company).collection("members").document(uid).get().addOnSuccessListener(d->{
   if(!Boolean.TRUE.equals(d.getBoolean("approved"))){toast("Company access denied");finish();return;}
   role=d.getString("role");home();
  }).addOnFailureListener(e->{toast("Unable to verify company membership");finish();});
 }
 void home(){
  page("Lead Follow-ups");
  if(manager())button("Filter Employees (one or more)",()->selectAgents());
  button("All statuses",()->list(null));
  button("Pending",()->list("pending"));
  button("Interested",()->list("interested"));
  button("Follow-up due today",()->dueList());
  label("Reminders are optional and checked periodically; Android may delay delivery.");
  button("Enable due follow-up reminders",()->{if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},45);}else{FollowUpWorker.schedule(this,company,uid);toast("Follow-up reminders enabled");}});
  button("Disable follow-up reminders",()->{FollowUpWorker.stop(this);toast("Follow-up reminders disabled");});
  button("All follow-ups",()->list("follow_up"));
  button("Won",()->list("won"));
  button("Lost",()->list("lost"));
  button("Return to company",()->finish());
 }
 void selectAgents(){
  if(!manager())return;
  db.collection("companies").document(company).collection("members").get().addOnSuccessListener(members->{
   ArrayList<String> ids=new ArrayList<>(),labels=new ArrayList<>();
   for(DocumentSnapshot d:members)if(Boolean.TRUE.equals(d.getBoolean("approved"))){
    ids.add(d.getId());String phone=d.getString("sim");
    labels.add((phone==null||phone.isEmpty()?"Employee":phone)+" · "+d.getId().substring(0,Math.min(8,d.getId().length())));
   }
   boolean[] checks=new boolean[ids.size()];
   for(int i=0;i<ids.size();i++)checks[i]=filterAgents&&selectedAgents.contains(ids.get(i));
   new android.app.AlertDialog.Builder(this).setTitle("Choose team members")
    .setMultiChoiceItems(labels.toArray(new String[0]),checks,(dialog,index,checked)->checks[index]=checked)
    .setNeutralButton("All employees",(dialog,which)->{filterAgents=false;selectedAgents.clear();home();})
    .setNegativeButton("Cancel",null)
    .setPositiveButton("Apply",(dialog,which)->{
     HashSet<String> selection=new HashSet<>();for(int i=0;i<ids.size();i++)if(checks[i])selection.add(ids.get(i));
     if(selection.isEmpty()){toast("Select one or more employees or All employees");return;}
     selectedAgents.clear();selectedAgents.addAll(selection);filterAgents=true;home();
     label(selectedAgents.size()+" selected employees");
    }).show();
  }).addOnFailureListener(e->toast("Unable to load employees: "+e.getLocalizedMessage()));
 }
 void list(String filter){
  page(filter==null?"All Assigned Leads":("Leads · "+filter.replace('_',' ')));
  DataTable table=new DataTable(this,content,new String[]{"Customer","Phone","CRM Status","Assigned To","Actions"},new int[]{150,150,135,155,220});
  Query q=leads();if(!manager())q=q.whereEqualTo("assignedTo",uid);
  q.limit(300).get().addOnSuccessListener(s=>{
   int shown=0;
   for(DocumentSnapshot d:s){
    String status=d.getString("status");if(filter!=null&&!filter.equals(status))continue;
    if(manager()&&filterAgents&&!selectedAgents.contains(d.getString("assignedTo")))continue;
    shown++;
    String person=d.getString("name"),phone=d.getString("phone");
    table.addActions(new String[]{person,phone,status,d.getString("assignedTo")},
     "Call",()->dialContact(phone),"Details",()->details(d.getId()));
   }
   if(shown==0)label("No leads in this category");
  }).addOnFailureListener(e->toast("Unable to load leads: "+e.getLocalizedMessage()));
  button("Back to follow-ups",()->home());
 }
 void dueList(){
  page("Due Follow-ups");
  DataTable table=new DataTable(this,content,new String[]{"Customer","Phone","Status","Follow-up Due","Actions"},new int[]{150,150,130,175,220});
  Query q=leads();if(!manager())q=q.whereEqualTo("assignedTo",uid);
  q.limit(300).get().addOnSuccessListener(s->{
   String now=new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US).format(new Date());
   int count=0;
   for(DocumentSnapshot d:s){
    String when=d.getString("nextFollowUp"),status=d.getString("status");
    if(when==null||when.isEmpty()||when.compareTo(now)>0||"won".equals(status)||"lost".equals(status))continue;
    if(manager()&&filterAgents&&!selectedAgents.contains(d.getString("assignedTo")))continue;
    count++;
    String number=d.getString("phone");
    table.addActions(new String[]{d.getString("name"),number,status,when},
     "Call",()->dialContact(number),"Details",()->details(d.getId()));
   }
   if(count==0)label("No currently due follow-ups in the first 300 assigned leads.");
  }).addOnFailureListener(e->toast("Unable to load due follow-ups: "+e.getLocalizedMessage()));
  button("Back",()->home());
 }
 void dialContact(String number){
  if(number==null||!number.matches("\\+[0-9]{8,15}")){toast("Invalid phone number");return;}
  startActivity(new android.content.Intent(android.content.Intent.ACTION_DIAL,android.net.Uri.parse("tel:"+number)));
 }
 void details(String id){
  leads().document(id).get().addOnSuccessListener(d->{
   if(!d.exists()){toast("Lead not found");home();return;}
   if(!manager()&&!uid.equals(d.getString("assignedTo"))){toast("Access denied");home();return;}
   page("Lead Details");
   label("Name: "+d.getString("name"));label("Phone: "+d.getString("phone"));
   label("Current status: "+d.getString("status"));
   label("Original notes: "+d.getString("notes"));
   String current=d.getString("outcome");EditText remarks=input("Call remarks / next action");
   if(current!=null)remarks.setText(current);
   EditText next=input("Next follow-up (e.g. 2026-10-20 14:00)");
   String old=d.getString("nextFollowUp");if(old!=null)next.setText(old);
   String[] statuses={"pending","contacted","interested","follow_up","won","lost"};
   Spinner status=new Spinner(this);
   status.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,statuses));content.addView(status);
   String oldStatus=d.getString("status");for(int i=0;i<statuses.length;i++)if(statuses[i].equals(oldStatus))status.setSelection(i);
   button("Save Follow-up",()->{
    String state=(String)status.getSelectedItem(),note=remarks.getText().toString().trim(),date=next.getText().toString().trim();
    if(note.length()>1000){remarks.setError("Maximum 1000 characters");return;}
    if(!date.isEmpty()){try{SimpleDateFormat fmt=new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US);fmt.setLenient(false);if(!date.equals(fmt.format(fmt.parse(date))))throw new IllegalArgumentException();}catch(Exception invalid){next.setError("Enter a valid date and time: YYYY-MM-DD HH:mm");return;}}
    Map<String,Object> updates=new HashMap<>();updates.put("status",state);updates.put("outcome",note);updates.put("nextFollowUp",date);
    updates.put("updatedAt",FieldValue.serverTimestamp());updates.put("lastUpdatedBy",uid);
    d.getReference().update(updates).addOnSuccessListener(v->{toast("Lead updated");details(id);}).addOnFailureListener(e->toast("Save failed: "+e.getLocalizedMessage()));
   });
   button("Back to list",()->list(null));
  }).addOnFailureListener(e->toast("Unable to open lead: "+e.getLocalizedMessage()));
 }
 @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);if(request==45&&grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED){FollowUpWorker.schedule(this,company,uid);toast("Follow-up reminders enabled");}}
 @Override public void onBackPressed(){finish();}
}
