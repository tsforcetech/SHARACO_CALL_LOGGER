package com.sharaco.callwebhook;

import android.Manifest;
import android.app.Activity;
import android.os.Bundle;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.widget.*;
import android.view.*;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.GoogleAuthProvider;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import com.google.firebase.firestore.*;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import java.util.*;

public class MainActivity extends Activity {
 LinearLayout panel; FirebaseAuth auth;FirebaseFirestore db;CallStore store;
 String company="",sim="",role="";
 String currentScreen="";
 String section="";
 static final int GOOGLE_REQUEST=712;
 boolean signingIn=false;
 int teal=Color.rgb(0,171,143);
 GradientDrawable rounded(int color,int r){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(r);return d;}
 void label(String s){TextView v=new TextView(this);v.setText(s);v.setTextColor(Color.rgb(32,48,53));v.setTextSize(16);v.setPadding(16,14,16,10);panel.addView(v);}
 EditText input(String hint){EditText v=new EditText(this);v.setHint(hint);panel.addView(v);return v;}
 void button(String s,Runnable job){
  Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(15);
  boolean back=s.startsWith("‹")||s.equals("Back")||s.equals("Sign out")||s.equals("Change company");
  b.setTextColor(back?Color.rgb(22,79,74):Color.WHITE);
  b.setBackground(rounded(back?Color.rgb(228,243,239):teal,28));b.setMinHeight(56);
  LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,-2);bp.setMargins(0,7,0,7);
  panel.addView(b,bp);b.setOnClickListener(v->job.run());
}
 void sectionTitle(String text){
  TextView t=new TextView(this);t.setText(text.toUpperCase(java.util.Locale.ROOT));t.setTextColor(Color.rgb(93,117,119));t.setTextSize(12);t.setLetterSpacing(.12f);t.setTypeface(null,Typeface.BOLD);t.setPadding(8,20,8,8);panel.addView(t);
 }
 void menuItem(String icon,String title,String subtitle,Runnable action){
  LinearLayout outer=new LinearLayout(this);outer.setOrientation(LinearLayout.HORIZONTAL);outer.setGravity(Gravity.CENTER_VERTICAL);
  GradientDrawable bg=rounded(Color.WHITE,34);bg.setStroke(1,Color.rgb(229,236,236));outer.setBackground(bg);
  outer.setPadding(18,18,16,18);
  TextView emblem=new TextView(this);emblem.setText(icon);emblem.setGravity(Gravity.CENTER);emblem.setTextSize(24);emblem.setTextColor(teal);
  emblem.setBackground(rounded(Color.rgb(229,248,242),22));LinearLayout.LayoutParams il=new LinearLayout.LayoutParams(54,54);outer.addView(emblem,il);
  LinearLayout texts=new LinearLayout(this);texts.setOrientation(1);texts.setPadding(18,0,4,0);
  TextView a=new TextView(this);a.setText(title);a.setTextSize(16);a.setTextColor(Color.rgb(29,56,60));a.setTypeface(null,Typeface.BOLD);texts.addView(a);
  TextView b=new TextView(this);b.setText(subtitle);b.setTextSize(12);b.setTextColor(Color.rgb(117,134,138));texts.addView(b);
  outer.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
  TextView arrow=new TextView(this);arrow.setText("›");arrow.setTextSize(28);arrow.setTextColor(teal);outer.addView(arrow);
  LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,5,0,8);panel.addView(outer,lp);
  outer.setOnClickListener(v->action.run());
 }
 void showSection(String type){
  section=type;page(type.equals("calls")?"Call Activity":type.equals("team")?"Team Management":type.equals("reports")?"Reports & Insights":"Settings");
  if(type.equals("calls")){
    sectionTitle("Your calling workspace");
    menuItem("☏","Assigned Numbers","View numbers and update contacted status",()->{Intent i=new Intent(this,WorklistActivity.class);i.putExtra("company",company);startActivity(i);});
    menuItem("◷","Call History","Review calls saved on this device",()->localHistory());
    menuItem("↻","Sync Now","Read permitted call history and sync",()->{store.scan(this,company,sim);store.upload();CallStore.schedule(this);toast("Sync requested");});
    menuItem("✓","Call Access","Grant Android call-history permissions",()->{if(checkSelfPermission(Manifest.permission.READ_CALL_LOG)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.READ_CALL_LOG,Manifest.permission.READ_CONTACTS},12);else {CallStore.schedule(this);toast("Permission already granted");}});
  }else if(type.equals("team")){
    sectionTitle("Team administration");
    menuItem("▦","Company QR Code","Invite employees to this company",()->showQr());
    menuItem("✓","Join Requests","Review and approve employee requests",()->requests());
    if("owner".equals(role))menuItem("♙","Members & Roles","Manage team access and assignments",()->manageTeam());
  }else if(type.equals("reports")){
    sectionTitle("Performance");
    menuItem("▥","Call Summary","Call counts and durations",()->report());
    if("owner".equals(role)||"admin".equals(role))menuItem("☷","Team Calls","Review calls from authorized agents",()->teamLogs());
  }else{
    sectionTitle("Account & company");
    menuItem("▤","Switch Company","Choose a different workspace",()->{company="";getSharedPreferences("settings",0).edit().remove("company").apply();section="";home();});
    menuItem("⇥","Sign Out","Sign out of Google and Sharaco",()->{auth.signOut();company="";getSharedPreferences("settings",0).edit().remove("company").apply();GoogleSignIn.getClient(this,new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()).signOut();section="";home();});
  }
 }
 void toast(String msg){Toast.makeText(this,msg,Toast.LENGTH_LONG).show();}
 void page(String title){
  currentScreen=title;
  panel=new LinearLayout(this);panel.setOrientation(1);panel.setPadding(24,24,24,32);
  panel.setBackgroundColor(Color.rgb(247,250,250));
  ScrollView sv=new ScrollView(this);sv.setFillViewport(true);sv.addView(panel);setContentView(sv);
  if(!title.equals("Sharaco Call Logger")){button("‹  Back",()->onBackPressed());}
  TextView heading=new TextView(this);heading.setText(title);heading.setTextSize(23);heading.setTextColor(Color.rgb(16,48,48));heading.setTypeface(null,Typeface.BOLD);heading.setPadding(2,18,2,18);panel.addView(heading);
 }
 String uid(){return auth.getUid();}
 @Override public void onCreate(Bundle b){super.onCreate(b);auth=FirebaseAuth.getInstance();db=FirebaseFirestore.getInstance();store=new CallStore(this);company=getSharedPreferences("settings",0).getString("company","");sim=getSharedPreferences("settings",0).getString("sim","");home();}
 void home(){page("Sharaco Call Logger");
  ImageView brand=new ImageView(this);brand.setImageResource(com.sharaco.callwebhook.R.drawable.sharaco_mark);brand.setAdjustViewBounds(true);
  LinearLayout.LayoutParams logoParams=new LinearLayout.LayoutParams(-1,132);logoParams.setMargins(0,3,0,6);
  panel.addView(brand,logoParams);
  if(uid()==null){login();return;}
  label("Welcome  •  "+auth.getCurrentUser().getEmail());
  button("＋  Create a company",()->createCompany());
  button("▦  Join a team using QR",()->scanJoin());
  String pending=getSharedPreferences("settings",0).getString("pendingCompany","");if(!pending.isEmpty())button("Check joined company approval",()->choose(pending,""));
  if(!company.isEmpty())companyHome();else showMyCompanies();
  button("Sign out",()->{auth.signOut();company="";getSharedPreferences("settings",0).edit().remove("company").apply();GoogleSignIn.getClient(this,new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()).signOut();home();});
 }
 void login(){
   label("Welcome to Sharaco Call Logger");
   label("One secure Google account for your companies, call activity, and team assignments.");
   button("Continue with Google",()->startGoogleLogin());
 }
 void startGoogleLogin(){
   if(signingIn)return;
   int id=getResources().getIdentifier("default_web_client_id","string",getPackageName());
   if(id==0){toast("Google sign-in configuration missing. Add SHA-1 fingerprint in Firebase and download updated google-services.json.");return;}
   String clientId=getString(id);
   GoogleSignInOptions options=new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestEmail().requestIdToken(clientId).build();
   GoogleSignInClient client=GoogleSignIn.getClient(this,options);
   signingIn=true;
   client.signOut().addOnCompleteListener(task->{try{startActivityForResult(client.getSignInIntent(),GOOGLE_REQUEST);}catch(Exception e){signingIn=false;toast("Google Sign-In unavailable: "+e.getMessage());}});
 }
 void finishGoogleLogin(Intent data){
   signingIn=false;
   try{
     GoogleSignInAccount a=GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException.class);
     if(a==null||a.getIdToken()==null){toast("Google did not return an ID token");return;}
     auth.signInWithCredential(GoogleAuthProvider.getCredential(a.getIdToken(),null))
       .addOnSuccessListener(r->home())
       .addOnFailureListener(e->toast("Firebase Google sign-in failed: "+e.getLocalizedMessage()));
   }catch(ApiException e){toast("Google sign-in error "+e.getStatusCode()+". Check Firebase SHA-1 and Google provider.");}
 }
 @Override public void onBackPressed(){
   if(auth.getCurrentUser()==null){super.onBackPressed();return;}
   if(currentScreen.equals("Sharaco Call Logger")){new android.app.AlertDialog.Builder(this).setTitle("Exit Sharaco?").setMessage("Your call sync remains scheduled if permissions allow.").setNegativeButton("Stay",null).setPositiveButton("Exit",(d,w)->finish()).show();return;}
   if(!section.isEmpty()){
     String backTo=section;section="";
     if(currentScreen.equals("Call Activity")||currentScreen.equals("Team Management")||currentScreen.equals("Reports & Insights")||currentScreen.equals("Settings"))home();
     else showSection(backTo);
   }else home();
 }
 void createCompany(){page("Create Company");EditText name=input("Company name");button("Save company",()->{
  String n=name.getText().toString().trim();if(n.isEmpty()){toast("Company name required");return;}
  String token=UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");
  Map<String,Object> c=new HashMap<>();c.put("name",n);c.put("ownerUid",uid());c.put("joinToken",token);c.put("createdAt",FieldValue.serverTimestamp());
  DocumentReference d=db.collection("companies").document();
  Map<String,Object> member=new HashMap<>();member.put("uid",uid());member.put("role","owner");member.put("approved",true);member.put("sim","");member.put("assignedUsers",new ArrayList<String>());
  WriteBatch b=db.batch();b.set(d,c);b.set(d.collection("members").document(uid()),member);
  b.commit().addOnSuccessListener(v->{choose(d.getId(),"");}).addOnFailureListener(e->toast(e.getMessage()));
 });button("Back",()->home());}
 void showMyCompanies(){label("My companies");db.collection("companies").whereEqualTo("ownerUid",uid()).get().addOnSuccessListener(s->{for(DocumentSnapshot d:s)button("Open "+d.getString("name"),()->choose(d.getId(),""));});
  label("If you have joined a team, use Check joined company approval above to reopen it.");
 }
 void choose(String cid,String ownSim){company=cid;sim=ownSim;getSharedPreferences("settings",0).edit().putString("company",company).putString("sim",sim).apply();home();}
 void companyHome(){
  label("Your workspace  •  Select a category");
  db.collection("companies").document(company).collection("members").document(uid()).get().addOnSuccessListener(doc->{
   if(!Boolean.TRUE.equals(doc.getBoolean("approved"))){label("Awaiting owner approval");return;}
   role=doc.getString("role");sim=doc.getString("sim")==null?"":doc.getString("sim");
   getSharedPreferences("settings",0).edit().putString("sim",sim).apply();
   sectionTitle("Workspace");
   menuItem("☏","Calls & Follow-ups","Call history, sync and assigned numbers",()->showSection("calls"));
   menuItem("▥","Reports & Insights","Your call performance and activity",()->showSection("reports"));
   if("owner".equals(role)||"admin".equals(role))menuItem("♙","Team Management","QR joining, approvals and permissions",()->showSection("team"));
   menuItem("⚙","Settings & Account","Switch company and sign out",()->showSection("settings"));
   label("Role: "+role+"   •   SIM: "+(sim.isEmpty()?"Not registered":sim));
  }).addOnFailureListener(e->toast(e.getLocalizedMessage()));
 }
 void showQr(){db.collection("companies").document(company).get().addOnSuccessListener(d->{
   String token=d.getString("joinToken");if(token==null){toast("QR not available");return;}
   page("Company joining QR");String value="SHARACO:"+company+":"+token;try{
    BitMatrix bits=new MultiFormatWriter().encode(value,BarcodeFormat.QR_CODE,640,640);Bitmap image=Bitmap.createBitmap(640,640,Bitmap.Config.ARGB_8888);
    for(int y=0;y<640;y++)for(int x=0;x<640;x++)image.setPixel(x,y,bits.get(x,y)?0xff000000:0xffffffff);
    ImageView iv=new ImageView(this);iv.setImageBitmap(image);panel.addView(iv);
   }catch(Exception e){toast(e.getMessage());}button("Back",()->home());
  }).addOnFailureListener(e->toast(e.getMessage()));}
 void scanJoin(){new IntentIntegrator(this).setPrompt("Scan company QR").setBeepEnabled(false).initiateScan();}
 @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);IntentResult scan=IntentIntegrator.parseActivityResult(request,result,data);
  if(request==GOOGLE_REQUEST){finishGoogleLogin(data);return;}if(scan!=null&&scan.getContents()!=null){String[] parts=scan.getContents().split(":");if(parts.length==3&&parts[0].equals("SHARACO"))join(parts[1],parts[2]);else toast("Invalid company code");}}
 void join(String cid,String token){page("Request to Join Company");EditText name=input("Your name");EditText phone=input("Your company SIM number (+countrycode...)");
  button("Submit request",()->{
   String n=name.getText().toString().trim(),p=phone.getText().toString().trim();if(n.isEmpty()||!p.matches("\\+[0-9]{8,15}")){toast("Enter name and international SIM number");return;}
   Map<String,Object> m=new HashMap<>();m.put("uid",uid());m.put("name",n);m.put("sim",p);m.put("token",token);m.put("status","pending");m.put("requestedAt",FieldValue.serverTimestamp());
   db.collection("companies").document(cid).collection("join_requests").document(uid()).set(m).addOnSuccessListener(v->{getSharedPreferences("settings",0).edit().putString("pendingCompany",cid).apply();toast("Request submitted; owner approval required");}).addOnFailureListener(e->toast(e.getMessage()));
  });button("Back",()->home());
 }
 void requests(){page("Pending join requests");db.collection("companies").document(company).collection("join_requests").whereEqualTo("status","pending").get().addOnSuccessListener(s->{
  for(DocumentSnapshot d:s){label(d.getString("name")+" | "+d.getString("sim"));button("Approve "+d.getString("name"),()->{
   Map<String,Object> m=new HashMap<>();m.put("uid",d.getId());m.put("role","employee");m.put("approved",true);m.put("sim",d.getString("sim"));m.put("assignedUsers",new ArrayList<String>());
   WriteBatch b=db.batch();b.set(db.collection("companies").document(company).collection("members").document(d.getId()),m);
   b.update(d.getReference(),"status","approved");b.commit().addOnSuccessListener(v->requests()).addOnFailureListener(e->toast(e.getMessage()));
  });}}).addOnFailureListener(e->toast(e.getMessage()));button("Back",()->home());}
 @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);if(code==12 && checkSelfPermission(Manifest.permission.READ_CALL_LOG)==PackageManager.PERMISSION_GRANTED){CallStore.schedule(this);toast("Background sync scheduled");}else if(code==12)toast("Call-log access was not granted");}
 void manageTeam(){page("Manage Company Team");
  db.collection("companies").document(company).collection("members").get().addOnSuccessListener(s->{
   for(DocumentSnapshot d:s){String id=d.getId();label(id+" | "+d.getString("role")+" | "+d.getString("sim"));
    if(!id.equals(uid())){
     button("Promote to admin: "+id.substring(0,Math.min(8,id.length())),()->d.getReference().update("role","admin").addOnSuccessListener(v->manageTeam()).addOnFailureListener(e->toast(e.getMessage())));
     button("Set employee role: "+id.substring(0,Math.min(8,id.length())),()->d.getReference().update("role","employee").addOnSuccessListener(v->manageTeam()).addOnFailureListener(e->toast(e.getMessage())));
    }
   }
   label("Assign call-log access by employee UID");EditText admin=input("Supervisor/admin UID"),target=input("Employee UID");
   button("Grant employee access",()->{
    String a=admin.getText().toString().trim(),t=target.getText().toString().trim();if(a.isEmpty()||t.isEmpty()){toast("Enter both UIDs");return;}
    db.collection("companies").document(company).collection("members").document(a).update("assignedUsers",FieldValue.arrayUnion(t)).addOnSuccessListener(v->toast("Assigned")).addOnFailureListener(e->toast(e.getMessage()));
   });
  }).addOnFailureListener(e->toast(e.getMessage()));button("Back",()->home());
 }
 void localHistory(){page("Local SIM Call History");for(String row:store.history(company))label(row);button("Back",()->home());}
 void teamLogs(){page("Company Call Logs");withAuthorizedCalls(q->q.limit(100).get().addOnSuccessListener(s->{for(DocumentSnapshot d:s)label(d.getString("remoteNumber")+" | "+d.getString("type")+" | "+d.getLong("durationSec")+" sec");} ).addOnFailureListener(e->toast(e.getMessage())));button("Back",()->home());}
 void report(){page("Call Summary");withAuthorizedCalls(q->q.limit(1000).get().addOnSuccessListener(s->{
   long seconds=0;Map<String,Integer> byType=new TreeMap<>();for(DocumentSnapshot d:s){String t=d.getString("type");byType.put(t,byType.getOrDefault(t,0)+1);Long v=d.getLong("durationSec");seconds+=v==null?0:v;}
   label("Sample of up to 1,000 authorized calls");label("Total calls: "+s.size());label("Total duration: "+seconds+" seconds");for(String t:byType.keySet())label(t+": "+byType.get(t));
  }).addOnFailureListener(e->toast(e.getMessage())));button("Back",()->home());}
 void withAuthorizedCalls(java.util.function.Consumer<Query> action){
  Query q=db.collection("companies").document(company).collection("call_logs");
  if("owner".equals(role)){action.accept(q);return;}
  db.collection("companies").document(company).collection("members").document(uid()).get().addOnSuccessListener(m->{
   List<String> ids=new ArrayList<>();ids.add(uid());Object assigned=m.get("assignedUsers");if(assigned instanceof List){for(Object value:(List<?>)assigned){String id=String.valueOf(value);if(!ids.contains(id)&&ids.size()<30)ids.add(id);}}
   action.accept(q.whereIn("uid",ids));
  }).addOnFailureListener(e->toast(e.getMessage()));
 }
}
