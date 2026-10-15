package com.sharaco.callwebhook;

import android.app.Activity;
import android.os.Bundle;
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
 boolean manager(){return "owner".equals(role)||"admin".equals(role);}
 GradientDrawable bg(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(30);return d;}
 void page(String title){
  ScrollView scroll=new ScrollView(this);
  content=new LinearLayout(this);content.setOrientation(1);content.setPadding(24,28,24,28);content.setBackgroundColor(Color.rgb(247,250,250));
  scroll.addView(content);setContentView(scroll);
  TextView t=new TextView(this);t.setText(title);t.setTextColor(Color.rgb(20,52,52));t.setTextSize(25);t.setTypeface(null,Typeface.BOLD);content.addView(t);
  label("Company workspace  /  Follow-ups");
 }
 void label(String s){TextView t=new TextView(this);t.setText(s);t.setTextSize(15);t.setTextColor(Color.rgb(56,74,76));t.setPadding(6,14,6,10);content.addView(t);}
 void button(String text,Runnable action){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextColor(Color.WHITE);b.setBackground(bg(Color.rgb(0,171,143)));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,7,0,7);content.addView(b,lp);b.setOnClickListener(v->action.run());}
 EditText input(String hint){EditText e=new EditText(this);e.setHint(hint);content.addView(e);return e;}
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
  button("All statuses",()->list(null));
  button("Pending",()->list("pending"));
  button("Interested",()->list("interested"));
  button("Follow-up due",()->list("follow_up"));
  button("Won",()->list("won"));
  button("Lost",()->list("lost"));
  button("Return to company",()->finish());
 }
 void list(String filter){
  page(filter==null?"All Assigned Leads":("Leads · "+filter.replace('_',' ')));
  Query q=leads();if(!manager())q=q.whereEqualTo("assignedTo",uid);
  q.limit(300).get().addOnSuccessListener(s->{
   int shown=0;
   for(DocumentSnapshot d:s){
    String status=d.getString("status");if(filter!=null&&!filter.equals(status))continue;
    shown++;
    String person=d.getString("name"),phone=d.getString("phone");
    label((person==null||person.isEmpty()?"Contact":person)+"  •  "+phone+"\n"+(status==null?"pending":status)+"  ·  "+(d.getString("assignedTo")==null?"":d.getString("assignedTo")));
    button("Open lead",()->details(d.getId()));
   }
   if(shown==0)label("No leads in this category");
  }).addOnFailureListener(e->toast("Unable to load leads: "+e.getLocalizedMessage()));
  button("Back to follow-ups",()->home());
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
    if(!date.isEmpty()&&!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}")){next.setError("Use YYYY-MM-DD HH:MM");return;}
    Map<String,Object> updates=new HashMap<>();updates.put("status",state);updates.put("outcome",note);updates.put("nextFollowUp",date);
    updates.put("updatedAt",FieldValue.serverTimestamp());updates.put("lastUpdatedBy",uid);
    d.getReference().update(updates).addOnSuccessListener(v->{toast("Lead updated");details(id);}).addOnFailureListener(e->toast("Save failed: "+e.getLocalizedMessage()));
   });
   button("Back to list",()->list(null));
  }).addOnFailureListener(e->toast("Unable to open lead: "+e.getLocalizedMessage()));
 }
 @Override public void onBackPressed(){finish();}
}
