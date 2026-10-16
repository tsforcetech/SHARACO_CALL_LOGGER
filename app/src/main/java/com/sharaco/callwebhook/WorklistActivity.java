package com.sharaco.callwebhook;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.*;
import android.view.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.*;
import java.io.*;
import java.util.*;

public class WorklistActivity extends Activity {
 private static final int PICK_CSV=710;
 private FirebaseFirestore db;
 private String company, uid, role;
 private LinearLayout layout;
 private final ArrayList<String[]> csvRows=new ArrayList<>();
 private final ArrayList<String> memberIds=new ArrayList<>();
 private final ArrayList<String> memberLabels=new ArrayList<>();
 private Spinner assignee;
 private int added=0,failed=0; private boolean worklistHome=true;
 @Override public void onCreate(Bundle b){super.onCreate(b);db=FirebaseFirestore.getInstance();uid=FirebaseAuth.getInstance().getUid();company=getIntent().getStringExtra("company");if(uid==null||company==null){finish();return;}
 db.collection("companies").document(company).collection("members").document(uid).get().addOnSuccessListener(d->{
 if(!Boolean.TRUE.equals(d.getBoolean("approved"))){finish();return;}role=d.getString("role");showHome();
 }).addOnFailureListener(e->finish());}
 void page(String title){
  worklistHome=title.equals("Assigned Number Worklist");
  layout=new LinearLayout(this);UiKit.screen(this,layout);
  ScrollView sc=new ScrollView(this);sc.setFillViewport(true);sc.addView(layout);setContentView(sc);
  if(!worklistHome)btn("‹  Back",()->onBackPressed());
  TextView heading=new TextView(this);heading.setText(title);heading.setTextSize(23);heading.setTextColor(Color.rgb(16,48,48));heading.setTypeface(null,Typeface.BOLD);heading.setPadding(4,16,4,18);layout.addView(heading);
}
 void label(String v){UiKit.cardText(this,layout,v,null);}
 EditText edit(String v){EditText t=new EditText(this);t.setHint(v);layout.addView(t);return t;}
 void btn(String v,Runnable action){
 Button b=new Button(this);b.setText(v);b.setAllCaps(false);b.setTextSize(15);b.setTextColor(v.startsWith("‹")?Color.rgb(21,83,77):Color.WHITE);
 GradientDrawable bg=new GradientDrawable();UiKit.action(this,b,v.startsWith("‹"));
 LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,6,0,8);layout.addView(b,lp);b.setOnClickListener(x->action.run());
}
 void msg(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
 CollectionReference numbers(){return db.collection("companies").document(company).collection("assigned_numbers");}
 boolean manager(){return "owner".equals(role)||"admin".equals(role);}
 @Override public void onBackPressed(){if(worklistHome)finish();else showHome();}
 void showHome(){page("Assigned Number Worklist");btn("Pending calls · tap to dial",()->pendingList());btn("My assigned numbers",()->myList());
 if(manager()){btn("Add / import numbers",()->addScreen());btn("All assignments and progress",()->allList());}
 btn("Back",()->finish());}
 void loadMembers(Runnable after){memberIds.clear();memberLabels.clear();
 db.collection("companies").document(company).collection("members").get().addOnSuccessListener(s->{
 for(DocumentSnapshot d:s){if(Boolean.TRUE.equals(d.getBoolean("approved"))){memberIds.add(d.getId());memberLabels.add((d.getString("sim")==null?"":d.getString("sim"))+" | "+d.getId());}}
 after.run();
 }).addOnFailureListener(e->msg(e.getMessage()));}
 void selector(){assignee=new Spinner(this);ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,memberLabels);assignee.setAdapter(adapter);layout.addView(assignee);}
 String chosen(){int i=assignee.getSelectedItemPosition();return i>=0&&i<memberIds.size()?memberIds.get(i):"";}
 String normalize(String raw){String n=raw==null?"":raw.replaceAll("[^+0-9]","");return n.matches("\\+[0-9]{8,15}")?n:"";}
 void addScreen(){page("Add Numbers");label("Provide international numbers with +countrycode");loadMembers(()->{selector();EditText name=edit("Contact name");EditText phone=edit("+971501234567");EditText note=edit("Optional notes");
 btn("Assign number",()->{String to=chosen(),n=normalize(phone.getText().toString());if(to.isEmpty()||n.isEmpty()){msg("Select employee and enter valid number");return;}
 save(n,name.getText().toString().trim(),note.getText().toString().trim(),to,()->{msg("Assigned");phone.setText("");name.setText("");});});
 btn("Import CSV file",()->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"text/csv","text/comma-separated-values","text/plain","application/vnd.ms-excel"});startActivityForResult(i,PICK_CSV);});
 label("CSV columns: phone,name,notes. First header row is optional. All imported numbers go to the selected employee.");
 btn("Back",()->showHome());});}
 void save(String number,String name,String note,String to,Runnable success){
 Map<String,Object> m=new HashMap<>();m.put("phone",number);m.put("name",name);m.put("notes",note);m.put("assignedTo",to);m.put("status","pending");m.put("createdBy",uid);m.put("createdAt",FieldValue.serverTimestamp());m.put("updatedAt",FieldValue.serverTimestamp());
 numbers().add(m).addOnSuccessListener(d->success.run()).addOnFailureListener(e->{failed++;msg(e.getMessage());});}
 @Override protected void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);if(req!=PICK_CSV||result!=RESULT_OK||data==null||data.getData()==null)return;
 String to=chosen();if(to.isEmpty()){msg("Select employee first");return;}csvRows.clear();try(BufferedReader reader=new BufferedReader(new InputStreamReader(getContentResolver().openInputStream(data.getData())))){
 String line;boolean first=true;while((line=reader.readLine())!=null&&csvRows.size()<500){List<String> cols=parseCSV(line);if(cols.isEmpty())continue;String number=normalize(cols.get(0));if(first&&cols.get(0).toLowerCase(Locale.ROOT).contains("phone")){first=false;continue;}first=false;
 if(!number.isEmpty())csvRows.add(new String[]{number,cols.size()>1?cols.get(1):"",cols.size()>2?cols.get(2):""});}
 }catch(Exception e){msg("Unable to read CSV: "+e.getMessage());return;}
 label("Valid rows loaded: "+csvRows.size());btn("Confirm import to selected employee",()->importRows(to,0));
 }
 List<String> parseCSV(String line){List<String> out=new ArrayList<>();StringBuilder b=new StringBuilder();boolean quoted=false;for(int i=0;i<line.length();i++){char c=line.charAt(i);if(c=='"'){if(quoted&&i+1<line.length()&&line.charAt(i+1)=='"'){b.append('"');i++;}else quoted=!quoted;}else if(c==','&&!quoted){out.add(b.toString().trim());b.setLength(0);}else b.append(c);}out.add(b.toString().trim());return out;}
 void importRows(String to,int index){if(index>=csvRows.size()){msg("Import complete: "+added+" saved, "+failed+" failed");added=0;failed=0;csvRows.clear();showHome();return;}
 String[] row=csvRows.get(index);Map<String,Object> m=new HashMap<>();m.put("phone",row[0]);m.put("name",row[1]);m.put("notes",row[2]);m.put("assignedTo",to);m.put("status","pending");m.put("createdBy",uid);m.put("createdAt",FieldValue.serverTimestamp());m.put("updatedAt",FieldValue.serverTimestamp());numbers().add(m).addOnSuccessListener(d->{added++;importRows(to,index+1);}).addOnFailureListener(e->{failed++;importRows(to,index+1);});
 // Failed writes remain visible as errors and are not automatically retried.
 }
 void pendingList(){
  page("Pending Customer Calls");
  numbers().whereEqualTo("assignedTo",uid).limit(300).get().addOnSuccessListener(result->{
   int count=0;
   for(DocumentSnapshot d:result){
    String status=d.getString("status");
    if(!"pending".equals(status)&&!"interested".equals(status)&&!"follow_up".equals(status))continue;
    count++;
    String number=d.getString("phone");if(number==null)continue;
    label((d.getString("name")==null?"Customer":d.getString("name"))+" · "+number+" · "+status);
    btn("Call "+number,()->dial(number));
    btn("Open customer",()->detail(d.getId(),true));
   }
   if(count==0)label("No pending calls in the first 300 assigned customers.");
   else label("Pending contacts: "+count+" (first 300 assignments)");
  }).addOnFailureListener(e->msg("Cannot load pending calls: "+e.getLocalizedMessage()));
  btn("Back",()->showHome());
 }
 void dial(String number){
  if(number==null||!number.matches("\\+[0-9]{8,15}")){msg("Invalid phone number");return;}
  startActivity(new Intent(Intent.ACTION_DIAL,Uri.parse("tel:"+number)));
 }
 void myList(){page("My Numbers");numbers().whereEqualTo("assignedTo",uid).limit(300).get().addOnSuccessListener(s->{
 int done=0;for(DocumentSnapshot d:s)if("contacted".equals(d.getString("status")))done++;label("Assigned: "+s.size()+" | Contacted: "+done+" | Pending: "+(s.size()-done));
 for(DocumentSnapshot d:s)oneNumber(d,true);
 }).addOnFailureListener(e->msg(e.getMessage()));btn("Back",()->showHome());}
 void allList(){page("Company Assignments");numbers().limit(300).get().addOnSuccessListener(s->{
 int done=0;for(DocumentSnapshot d:s)if("contacted".equals(d.getString("status")))done++;label("Total: "+s.size()+" | Contacted: "+done+" | Pending: "+(s.size()-done));
 for(DocumentSnapshot d:s)oneNumber(d,false);
 }).addOnFailureListener(e->msg(e.getMessage()));btn("Back",()->showHome());}
 void oneNumber(DocumentSnapshot d,boolean employee){label((d.getString("name")==null?"":d.getString("name")+" | ")+d.getString("phone")+" | "+d.getString("status"));if(!employee)label("Assigned UID: "+d.getString("assignedTo"));
 if(employee && d.getString("phone")!=null)btn("Call "+d.getString("phone"),()->dial(d.getString("phone")));
 btn("View number",()->detail(d.getId(),employee));}
 void detail(String id,boolean employee){numbers().document(id).get().addOnSuccessListener(d->{if(!d.exists()){msg("Not found");return;}page("Number Detail");label("Name: "+d.getString("name"));label("Number: "+d.getString("phone"));label("Status: "+d.getString("status"));label("Notes: "+d.getString("notes"));
 btn("Call with normal SIM dialer",()->{Intent i=new Intent(Intent.ACTION_DIAL,Uri.parse("tel:"+d.getString("phone")));startActivity(i);});
 if(uid.equals(d.getString("assignedTo"))){EditText outcome=edit("Call outcome / remarks");btn("Mark Contacted",()->{Map<String,Object> changes=new HashMap<>();changes.put("status","contacted");changes.put("contactedBy",uid);changes.put("contactedAt",FieldValue.serverTimestamp());changes.put("outcome",outcome.getText().toString().trim());changes.put("updatedAt",FieldValue.serverTimestamp());d.getReference().update(changes).addOnSuccessListener(v->detail(id,employee)).addOnFailureListener(e->msg(e.getMessage()));});}
 btn("Back to list",()->{if(employee)myList();else allList();});
 }).addOnFailureListener(e->msg(e.getMessage()));}
}
