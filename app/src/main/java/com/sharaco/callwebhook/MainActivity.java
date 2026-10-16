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
 String promptedCompany="";
 int teal=UiKit.BRAND;
 int ink=UiKit.INK;
 GradientDrawable rounded(int color,int r){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(r);return d;}
 void label(String s){TextView v=new TextView(this);v.setText(s);UiKit.label(v);v.setPadding(8,12,8,12);panel.addView(v);}
 EditText input(String hint){EditText v=new EditText(this);v.setHint(hint);UiKit.input(this,v);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,UiKit.dp(this,7),0,UiKit.dp(this,7));panel.addView(v,lp);return v;}
 void button(String s,Runnable job){
  Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(15);
  boolean back=s.startsWith("‹")||s.equals("Back")||s.equals("Sign out")||s.equals("Change company");
  b.setTextColor(back?Color.rgb(22,79,74):Color.WHITE);
  UiKit.action(this,b,back);
  LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,-2);bp.setMargins(0,7,0,7);
  panel.addView(b,bp);b.setOnClickListener(v->job.run());
}
 void sectionTitle(String text){
  TextView t=new TextView(this);t.setText(text.toUpperCase(java.util.Locale.ROOT));t.setTextColor(Color.rgb(93,117,119));t.setTextSize(12);t.setLetterSpacing(.12f);t.setTypeface(null,Typeface.BOLD);t.setPadding(8,20,8,8);panel.addView(t);
 }
 void menuItem(String icon,String title,String subtitle,Runnable action){
  LinearLayout outer=new LinearLayout(this);outer.setOrientation(LinearLayout.HORIZONTAL);outer.setGravity(Gravity.CENTER_VERTICAL);
  GradientDrawable bg=UiKit.shape(this,Color.WHITE,18,UiKit.BORDER);outer.setBackground(bg);
  outer.setPadding(UiKit.dp(this,14),UiKit.dp(this,16),UiKit.dp(this,14),UiKit.dp(this,16));outer.setElevation(0);
  TextView emblem=new TextView(this);emblem.setText(icon);emblem.setGravity(Gravity.CENTER);emblem.setTextSize(24);emblem.setTextColor(teal);
  emblem.setBackground(rounded(Color.rgb(226,246,241),20));LinearLayout.LayoutParams il=new LinearLayout.LayoutParams(UiKit.dp(this,48),UiKit.dp(this,48));outer.addView(emblem,il);
  LinearLayout texts=new LinearLayout(this);texts.setOrientation(1);texts.setPadding(18,0,4,0);
  TextView a=new TextView(this);a.setText(title);a.setTextSize(16);a.setTextColor(Color.rgb(29,56,60));a.setTypeface(null,Typeface.BOLD);texts.addView(a);
  TextView b=new TextView(this);b.setText(subtitle);b.setTextSize(13);b.setTextColor(Color.rgb(117,134,138));texts.addView(b);
  outer.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
  TextView arrow=new TextView(this);arrow.setText("›");arrow.setTextSize(28);arrow.setTextColor(teal);outer.addView(arrow);
  LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,5,0,8);panel.addView(outer,lp);
  outer.setClickable(true);outer.setFocusable(true);outer.setContentDescription(title+". "+subtitle);outer.setOnClickListener(v->action.run());
 }
 void showSection(String type){
  if((type.equals("team")||type.equals("reports")||type.equals("settings")) && !isAdmin()){toast("Company admin access required");return;}
  section=type;page(type.equals("calls")?"Call Activity":type.equals("team")?"Team Management":type.equals("reports")?"Reports & Insights":"Settings");
  if(type.equals("calls")){
    sectionTitle("Your calling workspace");
    menuItem("♙","My SIM Number","Register your own company calling number",()->ownSim());
    menuItem("☏","Assigned Numbers","View numbers and update contacted status",()->{Intent i=new Intent(this,WorklistActivity.class);i.putExtra("company",company);startActivity(i);});
    menuItem("◷","Local Call History","Calls saved on this device including pending uploads",()->localHistory());
    menuItem("☁","Synced Call History","My uploaded call history from Firebase",()->syncedHistory());
    menuItem("☷","Sync Health","Pending records, upload status and last sync",()->syncHealth());


    menuItem("◉","Lead Follow-ups","Update lead status and next actions",()->openCrm());


  }else if(type.equals("team")){
    sectionTitle("Team administration");
    menuItem("▦","Company QR Code","Invite employees to this company",()->showQr());
    menuItem("✓","Join Requests","Review and approve employee requests",()->requests());
    menuItem("♙","Members & Roles","Manage or view company users",()->manageTeam());
    menuItem("▣","Devices","View company device registrations",()->openDevices());
  }else if(type.equals("reports")){
    sectionTitle("Performance");
    menuItem("▥","Company Call Reports","Date filters and CSV export",()->{Intent i=new Intent(this,ReportActivity.class);i.putExtra("company",company);startActivity(i);});
    if("owner".equals(role)||"admin".equals(role))menuItem("☷","Team Calls","Review calls from authorized agents",()->teamLogs());
  }else{
    sectionTitle("Account & company");
    menuItem("▤","Switch Company","Choose a different workspace",()->home());
    menuItem("⇥","Sign Out","Sign out of Google and Sharaco",()->{CallStore.stop(this);FollowUpWorker.stop(this);auth.signOut();company="";getSharedPreferences("settings",0).edit().clear().apply();section="";home();});
  }
 }
 boolean isAdmin(){return "owner".equals(role)||"admin".equals(role);}
 void toast(String msg){Toast.makeText(this,msg,Toast.LENGTH_LONG).show();}
 void page(String title){
  currentScreen=title;
  panel=new LinearLayout(this);UiKit.screen(this,panel);
  ScrollView sv=new ScrollView(this);sv.setFillViewport(true);sv.addView(panel);setContentView(sv);
  if(!title.equals("Sharaco Call Logger")){button("‹  Back",()->onBackPressed());}
  TextView heading=new TextView(this);heading.setText(title);UiKit.heading(heading);heading.setPadding(2,18,2,18);panel.addView(heading);
 }
 String uid(){return auth.getUid();}
 @Override public void onCreate(Bundle b){super.onCreate(b);auth=FirebaseAuth.getInstance();db=FirebaseFirestore.getInstance();store=new CallStore(this);company=getSharedPreferences("settings",0).getString("company","");sim=getSharedPreferences("settings",0).getString("sim","");home();}
 void home(){
  section="";company="";role="";
  getSharedPreferences("settings",0).edit().remove("company").apply();
  page("Sharaco Call Logger");
  ImageView brand=new ImageView(this);brand.setImageResource(R.drawable.sharaco_mark);brand.setAdjustViewBounds(true);
  panel.addView(brand,new LinearLayout.LayoutParams(-1,UiKit.dp(this,105)));
  if(uid()==null){login();return;}
  label("Signed in: "+auth.getCurrentUser().getEmail());
  String userId=uid();
  db.collection("users").document(userId).collection("companies").get()
    .addOnSuccessListener(result->{
     if(!userId.equals(uid())||!currentScreen.equals("Sharaco Call Logger"))return;
     String mode=getSharedPreferences("onboarding",0).getString(userId+"_mode","");
     if(result.isEmpty() && mode.isEmpty()){chooseAccountType();return;}
     boolean accountAdmin="admin".equals(mode);
     for(DocumentSnapshot d:result)if("owner".equals(d.getString("role"))||"admin".equals(d.getString("role")))accountAdmin=true;
     showWorkspaceHome(accountAdmin);
    }).addOnFailureListener(e->{
     if(!currentScreen.equals("Sharaco Call Logger"))return;
     label("Unable to load your company directory: "+e.getLocalizedMessage());
     button("Retry",()->home());
    });
 }
 void chooseAccountType(){
  page("Welcome to SHARACO");
  label("How will you use SHARACO Call Logger? Choose your starting role.");
  menuItem("▣","Company Administrator","Create your own company and manage team members",()->{
   getSharedPreferences("onboarding",0).edit().putString(uid()+"_mode","admin").apply();
   createCompany();
  });
  menuItem("♙","Team Member","Join your company's workspace by scanning its QR code",()->{
   getSharedPreferences("onboarding",0).edit().putString(uid()+"_mode","team").apply();
   teamWelcome();
  });
  button("Delete my account",()->confirmAccountDeletion());
  button("Sign out",()->signOutAccount());
 }
 void showWorkspaceHome(boolean adminMode){
  sectionTitle("YOUR COMPANIES");
  showMyCompanies();
  String pending=getSharedPreferences("settings",0).getString("pendingCompany","");
  if(!pending.isEmpty())menuItem("◷","Approval status","Check whether your company owner approved your request",()->checkPending(pending));
  sectionTitle(adminMode?"ADMIN TOOLS":"TEAM MEMBER");
  if(adminMode){
   menuItem("＋","Create another company","Open the guided company creation wizard",()->createCompany());
   menuItem("↻","Restore an existing company","Recover a company you already own if it is missing from the list",()->restoreOwnedCompany());
  }
  menuItem("▦","Scan company QR","Join an authorized company as a team member",()->scanJoin());
  if(!adminMode)label("Only your assigned company information will be available. Admin and reporting options are hidden.");
  button("Delete my account",()->confirmAccountDeletion());
  button("Sign out",()->signOutAccount());
 }
 void restoreOwnedCompany(){
  page("Restore Existing Company");
  label("Use this only for a company you created previously and that is missing from your company list.");
  label("Copy its Firestore document ID from Firebase Console > companies.");
  EditText id=input("Existing company document ID");
  button("Verify and Restore",()->{
   String cid=id.getText().toString().trim();
   if(cid.isEmpty()||cid.contains("/")){id.setError("Enter a valid company document ID");return;}
   db.collection("companies").document(cid).get(Source.SERVER).addOnSuccessListener(doc->{
    if(!doc.exists()||!uid().equals(doc.getString("ownerUid"))){toast("Company not found or this account is not the owner");return;}
    Map<String,Object> index=new HashMap<>();index.put("name",doc.getString("name"));
    index.put("role","owner");index.put("status","approved");index.put("updatedAt",FieldValue.serverTimestamp());
    db.collection("users").document(uid()).collection("companies").document(cid).set(index)
     .addOnSuccessListener(v->{toast("Company restored");home();})
     .addOnFailureListener(e->toast("Unable to restore company: "+e.getLocalizedMessage()));
   }).addOnFailureListener(e->toast("Company lookup failed: "+e.getLocalizedMessage()));
  });
  button("Cancel",()->home());
 }
 void teamWelcome(){
  page("Join as Team Member");
  label("Step 1 · Ask your company administrator for the company's QR code.");
  label("Step 2 · Scan the QR code in the camera frame.");
  label("Step 3 · Enter your name and registered SIM, then wait for approval.");
  button("Scan Company QR",()->scanJoin());
  String pending=getSharedPreferences("settings",0).getString("pendingCompany","");
  if(!pending.isEmpty())button("Check approval",()->checkPending(pending));
  button("Back",()->home());
 }
 void checkPending(String cid){
  db.collection("companies").document(cid).collection("members").document(uid()).get()
   .addOnSuccessListener(d->{
    if(Boolean.TRUE.equals(d.getBoolean("approved"))){
     getSharedPreferences("settings",0).edit().remove("pendingCompany").apply();choose(cid,"");
    }else{toast("Awaiting company administrator approval");teamWelcome();}
   }).addOnFailureListener(e->toast("Approval check failed: "+e.getLocalizedMessage()));
 }
 void signOutAccount(){
  CallStore.stop(this);FollowUpWorker.stop(this);auth.signOut();company="";promptedCompany="";
  getSharedPreferences("settings",0).edit().clear().apply();home();
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
   }else if(currentScreen.equals("Company Workspace"))home();else if(!company.isEmpty())companyHome();else home();
 }
 void createCompany(){companyWizard(1,"");}
 void companyWizard(int step,String companyName){
  if(step==1){
   page("Create Company · 1 of 3");
   label("Company details");
   EditText name=input("Company name");
   if(!companyName.isEmpty())name.setText(companyName);
   label("This is the company workspace your team will join.");
   button("Continue",()->{
    String value=name.getText().toString().trim();
    if(value.length()<2||value.length()>100){name.setError("Company name must contain 2 to 100 characters");return;}
    companyWizard(2,value);
   });
   button("I already own a company",()->restoreOwnedCompany());
   button("Cancel",()->home());return;
  }
  if(step==2){
   page("Create Company · 2 of 3");
   label("Administrator");
   label("Company: "+companyName);
   label("Your signed-in account will become the company owner. Only authorized owners and admins can manage members or view company-wide reports.");
   label("Employees join via QR and require approval.");
   button("Continue",()->companyWizard(3,companyName));
   button("Back",()->companyWizard(1,companyName));return;
  }
  page("Create Company · 3 of 3");
  label("Review & create");
  UiKit.cardText(this,panel,companyName,"Owner: "+auth.getCurrentUser().getEmail());
  label("The invite QR code becomes available after the company has been created.");
  button("Create Company",()->saveNewCompany(companyName));
  button("Back",()->companyWizard(2,companyName));
 }
 void saveNewCompany(String n){
  String ownerUid=uid();
  if(ownerUid==null)return;
  String token=UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");
  Map<String,Object> c=new HashMap<>();c.put("name",n);c.put("ownerUid",ownerUid);
  c.put("joinToken",token);c.put("createdAt",FieldValue.serverTimestamp());
  DocumentReference d=db.collection("companies").document();
  Map<String,Object> member=new HashMap<>();member.put("uid",ownerUid);member.put("role","owner");
  member.put("approved",true);member.put("sim","");member.put("assignedUsers",new ArrayList<String>());
  Map<String,Object> index=new HashMap<>();index.put("name",n);index.put("role","owner");
  index.put("status","approved");index.put("updatedAt",FieldValue.serverTimestamp());
  WriteBatch batch=db.batch();batch.set(d,c);batch.set(d.collection("members").document(ownerUid),member);
  batch.set(db.collection("users").document(ownerUid).collection("companies").document(d.getId()),index);
  batch.commit().addOnSuccessListener(v->{
   getSharedPreferences("onboarding",0).edit().putString(ownerUid+"_mode","admin").apply();
   toast("Company created");choose(d.getId(),"");
  }).addOnFailureListener(e->toast("Company creation failed: "+e.getLocalizedMessage()));
 }
 void showMyCompanies(){
  String account=uid();if(account==null)return;
  db.collection("users").document(account).collection("companies").get().addOnSuccessListener(list->{
   if(!account.equals(uid())||!currentScreen.equals("Sharaco Call Logger"))return;
   if(list.isEmpty())label("No approved company membership yet. Join by QR or create a company if you are an administrator.");
   for(DocumentSnapshot entry:list){
    String cid=entry.getId();String title=entry.getString("name");
    db.collection("companies").document(cid).collection("members").document(account).get()
     .addOnSuccessListener(member->{
      if(!account.equals(uid())||!currentScreen.equals("Sharaco Call Logger"))return;
      if(!Boolean.TRUE.equals(member.getBoolean("approved")))return;
      String memberRole=member.getString("role");
      menuItem("▣",title==null?cid:title,
        ("owner".equals(memberRole)||"admin".equals(memberRole)?"Administrator":"Team member")+" · Open workspace",
        ()->choose(cid,""));
     });
   }
  }).addOnFailureListener(e->label("Unable to load your company directory: "+e.getLocalizedMessage()));
 }
 void choose(String cid,String ownSim){
  company=cid;section="";getSharedPreferences("settings",0).edit().putString("company",cid).apply();
  companyHome();
}
 void companyHome(){
  page("Company Workspace");
  menuItem("‹","All Companies","Back to company selection",()->home());
  db.collection("companies").document(company).get().addOnSuccessListener(d->label("Company: "+d.getString("name")));
  label("Everything for your team, in one place.");
  db.collection("companies").document(company).collection("members").document(uid()).get().addOnSuccessListener(doc->{
   if(!Boolean.TRUE.equals(doc.getBoolean("approved"))){label("Awaiting owner approval");return;}
   role=doc.getString("role");sim=doc.getString("sim")==null?"":doc.getString("sim");
   getSharedPreferences("settings",0).edit().putString("sim",sim).apply();
   {getSharedPreferences("settings",0).edit().putString("syncCompany",company).putString("syncSim",sim).apply();if(checkSelfPermission(Manifest.permission.READ_CALL_LOG)==PackageManager.PERMISSION_GRANTED && getSharedPreferences("settings",0).getBoolean("syncConsent",false) && company.equals(getSharedPreferences("settings",0).getString("syncConsentCompany","")))CallStore.schedule(this);}
   sectionTitle("WORKSPACE");
   menuItem("☏","Calls & Follow-ups","History, leads, automatic sync",()->showSection("calls"));
   menuItem("◷","Pending Customer Calls","Open my customer calling list and dial",()->{Intent i=new Intent(this,WorklistActivity.class);i.putExtra("company",company);i.putExtra("showPending",true);startActivity(i);});
   menuItem("☁","Synced Calls","See my uploaded call history",()->syncedHistory());
   menuItem("▥","My Call Report","My call statistics, filters and call records",()->{Intent i=new Intent(this,ReportActivity.class);i.putExtra("company",company);startActivity(i);});
   if(isAdmin())menuItem("▥","Reports & Insights","Company-wide call performance and activity",()->showSection("reports"));
   if(isAdmin())menuItem("▣","Company Overview","Employees, assigned numbers and sync activity",()->companyOverview());
   if("owner".equals(role)||"admin".equals(role))menuItem("♙","Team Management","QR joining, approvals and permissions",()->showSection("team"));
   if(isAdmin())menuItem("⚙","Company Configuration","Company administration and configuration",()->showSection("settings"));
   label("Signed in as "+role+"   ·   SIM "+(sim.isEmpty()?"not set":sim));
   button("Company membership & account",()->companyMembershipOptions());
   registerDeviceAutomatically(company);
  }).addOnFailureListener(e->toast(e.getLocalizedMessage()));
 }
 void registerDeviceAutomatically(String cid){
  String account=uid();if(account==null||cid.isEmpty())return;
  android.content.SharedPreferences prefs=getSharedPreferences("device_install",0);
  String deviceId=prefs.getString("deviceId","");
  if(deviceId.isEmpty()){deviceId=UUID.randomUUID().toString();prefs.edit().putString("deviceId",deviceId).apply();}
  DocumentReference ref=db.collection("companies").document(cid).collection("devices").document(account+"_"+deviceId);
  ref.get(Source.SERVER).addOnSuccessListener(device->{
   if(!cid.equals(company)||!account.equals(uid()))return;
   if(device.exists()){
    if(!"active".equals(device.getString("status"))){toast("Device disabled by administrator");return;}
    maybeAskSyncConsent(cid);return;
   }
   Map<String,Object> m=new HashMap<>();
   m.put("uid",account);m.put("model",android.os.Build.MANUFACTURER+" "+android.os.Build.MODEL);
   m.put("androidVersion",android.os.Build.VERSION.RELEASE);
   m.put("lastSeen",FieldValue.serverTimestamp());m.put("status","active");
   ref.set(m).addOnSuccessListener(v->{if(cid.equals(company))maybeAskSyncConsent(cid);})
    .addOnFailureListener(e->toast("Device registration failed: "+e.getLocalizedMessage()));
  }).addOnFailureListener(e->toast("Unable to check device registration: "+e.getLocalizedMessage()));
 }
 long startOfToday(){java.util.Calendar day=java.util.Calendar.getInstance();day.set(java.util.Calendar.HOUR_OF_DAY,0);day.set(java.util.Calendar.MINUTE,0);day.set(java.util.Calendar.SECOND,0);day.set(java.util.Calendar.MILLISECOND,0);return day.getTimeInMillis();}
 void maybeAskSyncConsent(String cid){
  if(cid.equals(promptedCompany)||!cid.equals(company))return;
  promptedCompany=cid;
  android.content.SharedPreferences prefs=getSharedPreferences("settings",0);
  boolean allowed=prefs.getBoolean("syncConsent",false)&&cid.equals(prefs.getString("syncConsentCompany",""));
  if(allowed){askMissingPermissions();return;}
  new android.app.AlertDialog.Builder(this).setTitle("Enable automatic call syncing?")
   .setMessage("If you agree, SHARACO will import call metadata from the first call of today (00:00 local time), including earlier calls today, and automatically sync subsequent calls to this company. Shared data includes phone numbers, timestamps, durations and optional contact names. You can pause sync later.")
   .setPositiveButton("Allow",(dialog,which)->{
    prefs.edit().putBoolean("syncConsent",true).putString("syncConsentCompany",cid)
     .putLong("syncConsentStartedAt",startOfToday()).apply();askMissingPermissions();
   }).setNegativeButton("Not now",(dialog,which)->CallStore.stop(this)).show();
 }
 void askMissingPermissions(){
  java.util.ArrayList<String> missing=new java.util.ArrayList<>();
  if(checkSelfPermission(Manifest.permission.READ_CALL_LOG)!=PackageManager.PERMISSION_GRANTED)missing.add(Manifest.permission.READ_CALL_LOG);
  if(checkSelfPermission(Manifest.permission.READ_PHONE_STATE)!=PackageManager.PERMISSION_GRANTED)missing.add(Manifest.permission.READ_PHONE_STATE);
  if(checkSelfPermission(Manifest.permission.READ_CONTACTS)!=PackageManager.PERMISSION_GRANTED)missing.add(Manifest.permission.READ_CONTACTS);
  if(missing.isEmpty()){CallStore.schedule(this);return;}
  requestPermissions(missing.toArray(new String[0]),12);
 }
 void companyMembershipOptions(){
  page("Membership and Account");
  label("Company: "+company);
  if("owner".equals(role)){
   label("Company owners must arrange secure deletion or ownership transfer before leaving.");
   button("Permanently delete company",()->{
    EditText confirmation=new EditText(this);
    confirmation.setHint("Enter company ID: "+company);
    new android.app.AlertDialog.Builder(this).setTitle("Permanently delete company?")
     .setMessage("This permanently deletes company calls, assignments, members and devices. All staff lose access. This cannot be undone. Enter the company ID to confirm.")
     .setView(confirmation).setNegativeButton("Cancel",null)
     .setPositiveButton("Delete",(dialog,which)->{
      String cid=company;
      if(!cid.equals(confirmation.getText().toString().trim())){toast("Company ID does not match. Nothing was deleted.");return;}
      Map<String,Object> input=new HashMap<>();input.put("companyId",cid);input.put("confirm",cid);
      com.google.firebase.functions.FirebaseFunctions.getInstance("us-central1").getHttpsCallable("deleteCompany")
       .call(input).addOnSuccessListener(done->{
        CallStore.stop(this);toast("Company permanently deleted");home();
       }).addOnFailureListener(e->toast("Deletion failed. Deploy and verify the secure deleteCompany function: "+e.getLocalizedMessage()));
     }).show();
   });
  }else button("Leave company",()->new android.app.AlertDialog.Builder(this)
   .setTitle("Leave this company?")
   .setMessage("Your membership will be removed. Company-owned call records remain with the company.")
   .setNegativeButton("Cancel",null).setPositiveButton("Leave",(d,w)->leaveCompany()).show());
  if("owner".equals(role))button("Remove team members",()->manageTeam());
  button("Back",()->companyHome());
 }
 void leaveCompany(){
  String account=uid(),cid=company;if(account==null)return;
  WriteBatch b=db.batch();
  b.delete(db.collection("companies").document(cid).collection("members").document(account));
  b.delete(db.collection("users").document(account).collection("companies").document(cid));
  b.commit().addOnSuccessListener(v->{CallStore.stop(this);toast("Membership removed");home();})
   .addOnFailureListener(e->toast("Unable to leave: "+e.getLocalizedMessage()));
 }
 void confirmAccountDeletion(){
  new android.app.AlertDialog.Builder(this).setTitle("Delete your SHARACO account?")
   .setMessage("This removes your SHARACO login and memberships. Organization-owned call logs remain in their companies. Company owners cannot delete their account until ownership is resolved.")
   .setNegativeButton("Cancel",null).setPositiveButton("Delete account",(d,w)->deleteAccount()).show();
 }
 void deleteAccount(){
  String account=uid();if(account==null)return;
  com.google.firebase.auth.FirebaseUser signedIn=auth.getCurrentUser();
  long lastSignIn=signedIn==null||signedIn.getMetadata()==null?0:signedIn.getMetadata().getLastSignInTimestamp();
  if(lastSignIn<=0||System.currentTimeMillis()-lastSignIn>180000L){
   toast("For your security, sign out and sign in again, then delete the account within 3 minutes.");return;
  }
  db.collection("users").document(account).collection("companies").get(Source.SERVER).addOnSuccessListener(list->{
   for(DocumentSnapshot d:list)if("owner".equals(d.getString("role"))){toast("You own a company. Resolve company ownership first.");return;}
   if(list.size()>100){toast("Contact support to delete an account with many memberships");return;}
   WriteBatch b=db.batch();
   for(DocumentSnapshot d:list){
    b.delete(db.collection("companies").document(d.getId()).collection("members").document(account));
    b.delete(d.getReference());
   }
   b.commit().addOnSuccessListener(v->auth.getCurrentUser().delete()
    .addOnSuccessListener(done->{
     CallStore.stop(this);FollowUpWorker.stop(this);
     getSharedPreferences("settings",0).edit().clear().apply();
     getSharedPreferences("onboarding",0).edit().remove(account+"_mode").apply();
     deleteDatabase("call_history.db");toast("Account deleted");home();
    }).addOnFailureListener(e->toast("Recent sign-in required. Sign out, sign in again and retry: "+e.getLocalizedMessage())))
    .addOnFailureListener(e->toast("Membership cleanup failed: "+e.getLocalizedMessage()));
  }).addOnFailureListener(e->toast("Account check failed: "+e.getLocalizedMessage()));
 }
 void openDevices(){Intent i=new Intent(this,DeviceActivity.class);i.putExtra("company",company);startActivity(i);}
 void openCrm(){
  Intent i=new Intent(this,CrmActivity.class);i.putExtra("company",company);startActivity(i);
 }
 void syncHealth(){
  page("Call Sync Health");sectionTitle("Current company");
  label("Pending upload: "+store.countPending(company));
  label("Successfully uploaded: "+store.countSynced(company));
  long last=CallStore.lastSync(this);
  label("Last background sync: "+(last==0?"Not yet completed":new java.util.Date(last).toString()));
  android.content.SharedPreferences status=getSharedPreferences("sync_diagnostics",0);
  label("Worker status: "+status.getString("status","Never run"));
  label("Eligible calls at last check: "+status.getInt("lastEligibleCount",0));
  long attempted=status.getLong("lastAttempt",0);
  label("Last attempted: "+(attempted==0?"Not yet":new java.util.Date(attempted).toString()));
  boolean permission=checkSelfPermission(Manifest.permission.READ_CALL_LOG)==PackageManager.PERMISSION_GRANTED;
  label("Android call-log permission: "+(permission?"Granted":"Not granted"));
  label("My SIM: "+(sim.isEmpty()?"Not registered":sim));
  label("Call log mode: All accessible phone call-log entries; no SIM subscription verification.");
  label("Android rows scanned at last attempt: "+status.getInt("lastScanned",0));
  label("New local rows at last attempt: "+status.getInt("lastNewLocal",0));
  if(status.contains("scanError"))label("Scan issue: "+status.getString("scanError","Unknown"));
  label("This device is automatically registered when you open your approved company.");
  label("Android schedules periodic work approximately every 15 minutes. Battery optimization, connectivity, sign-in and device policies can delay sync.");

  label("Automatic upload consent for this company: "+(getSharedPreferences("settings",0).getBoolean("syncConsent",false)&&company.equals(getSharedPreferences("settings",0).getString("syncConsentCompany",""))?"Enabled":"Disabled"));
  button("Manage sync consent",()->syncPrivacy());
  button("Queue upload now",()->{if(!getSharedPreferences("settings",0).getBoolean("syncConsent",false)||!company.equals(getSharedPreferences("settings",0).getString("syncConsentCompany",""))){toast("Enable consent for this company first");return;}CallStore.syncNow(this);toast("Upload requested; check Worker status after a short delay");});
  button("Refresh sync status",()->syncHealth());
 }
 void companyOverview(){
  if(!isAdmin()){toast("Admin access required");return;}
  page("Company Overview");sectionTitle("Company activity");
  db.collection("companies").document(company).collection("members").get().addOnSuccessListener(s->label("Team members: "+s.size())).addOnFailureListener(e->toast(e.getLocalizedMessage()));
  db.collection("companies").document(company).collection("assigned_numbers").get().addOnSuccessListener(s->{
    int contacted=0;for(DocumentSnapshot d:s)if("contacted".equals(d.getString("status")))contacted++;
    label("Assigned leads: "+s.size()+"  ·  Contacted: "+contacted);
  }).addOnFailureListener(e->toast(e.getLocalizedMessage()));
  db.collection("companies").document(company).collection("call_logs").limit(1000).get().addOnSuccessListener(s->{
    int incoming=0,outgoing=0,missed=0;long seconds=0;
    for(DocumentSnapshot d:s){String type=d.getString("type");if("INCOMING".equals(type))incoming++;if("OUTGOING".equals(type))outgoing++;if("MISSED".equals(type))missed++;Long dur=d.getLong("durationSec");if(dur!=null)seconds+=dur;}
    label("Recent calls (up to 1,000): "+s.size());
    label("Incoming: "+incoming+"  ·  Outgoing: "+outgoing+"  ·  Missed: "+missed);
    label("Total call time: "+(seconds/60)+" minutes");
  }).addOnFailureListener(e->toast(e.getLocalizedMessage()));
 }
 void syncPrivacy(){
  page("Sync Consent & Privacy");
  label("Company: "+company);
  label("Initial consent includes calls from the beginning of the calendar day you enable sync, including any earlier calls that day. No earlier dates are included.");
  label("When enabled, Sharaco reads call history (phone numbers, contact names if contacts permission is granted, call type, time and duration) and uploads it to the selected company's Firebase database.");
  label("Background sync is scheduled about every 15 minutes when Android permits. Your company owner and admins can access company call metadata.");
  label("You can pause future synchronization here. Previously uploaded records are not deleted automatically.");
  label("SIM filtering is not used: all accessible phone call records may be uploaded, including calls from another SIM or line. A saved company SIM number is not proof of the originating SIM.");
  boolean enabled=getSharedPreferences("settings",0).getBoolean("syncConsent",false) && company.equals(getSharedPreferences("settings",0).getString("syncConsentCompany",""));
  label("Current setting: "+(enabled?"Enabled":"Disabled"));
  button("Enable Automatic Sync",()->{android.content.SharedPreferences preferences=getSharedPreferences("settings",0);long consentStart=(preferences.getBoolean("syncConsent",false)&&company.equals(preferences.getString("syncConsentCompany","")))?preferences.getLong("syncConsentStartedAt",0L):0L;if(consentStart<=0L)consentStart=startOfToday();preferences.edit().putBoolean("syncConsent",true).putString("syncConsentCompany",company).putLong("syncConsentStartedAt",consentStart).apply();if(checkSelfPermission(Manifest.permission.READ_CALL_LOG)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.READ_CALL_LOG},12);else CallStore.schedule(this);toast("Automatic sync enabled");syncPrivacy();});
  button("Pause Automatic Sync",()->{getSharedPreferences("settings",0).edit().putBoolean("syncConsent",false).remove("syncConsentCompany").apply();CallStore.stop(this);toast("Background sync paused");syncPrivacy();});
 }
 void ownSim(){
  page("My Company SIM Number");
  label("This number is used for recording company calls. Include country code.");
  EditText number=input("+971501234567");
  db.collection("companies").document(company).collection("members").document(uid()).get()
     .addOnSuccessListener(d->{String existing=d.getString("sim");if(existing!=null)number.setText(existing);});
  button("Save SIM number",()->{
    String value=number.getText().toString().trim().replace(" ","");
    if(!value.matches("\\+[0-9]{8,15}")){number.setError("Use +countrycode and 8–15 digits");return;}
    db.collection("companies").document(company).collection("members").document(uid()).update("sim",value)
       .addOnSuccessListener(v->{sim=value;getSharedPreferences("settings",0).edit().putString("sim",value).putString("syncCompany",company).putString("syncSim",value).apply();if(checkSelfPermission(Manifest.permission.READ_CALL_LOG)==PackageManager.PERMISSION_GRANTED)CallStore.schedule(this);toast("SIM number saved; background sync enabled if call permission granted");showSection("calls");})
       .addOnFailureListener(e->toast("Unable to save SIM: "+e.getLocalizedMessage()));
  });
 }
 void showQr(){db.collection("companies").document(company).get().addOnSuccessListener(d->{
   String token=d.getString("joinToken");if(token==null){toast("QR not available");return;}
   page("Company joining QR");String value="SHARACO:"+company+":"+token;try{
    BitMatrix bits=new MultiFormatWriter().encode(value,BarcodeFormat.QR_CODE,640,640);Bitmap image=Bitmap.createBitmap(640,640,Bitmap.Config.ARGB_8888);
    for(int y=0;y<640;y++)for(int x=0;x<640;x++)image.setPixel(x,y,bits.get(x,y)?0xff000000:0xffffffff);
    ImageView iv=new ImageView(this);iv.setImageBitmap(image);panel.addView(iv);
   }catch(Exception e){toast(e.getMessage());}button("Back",()->home());
  }).addOnFailureListener(e->toast(e.getMessage()));}
 void scanJoin(){startActivityForResult(new Intent(this,QrScanActivity.class),819);}
 @Override protected void onActivityResult(int request,int result,Intent data){
  super.onActivityResult(request,result,data);
  if(request==GOOGLE_REQUEST){if(result==RESULT_OK)finishGoogleLogin(data);return;}
  if(request==819&&result==RESULT_OK&&data!=null){
   String qr=data.getStringExtra("qr");if(qr==null){toast("No company code scanned");return;}
   String[] parts=qr.split(":");
   if(parts.length==3&&"SHARACO".equals(parts[0]))join(parts[1],parts[2]);
   else toast("Invalid company invitation");
  }
 }
 void join(String cid,String token){page("Request to Join Company");EditText name=input("Your name");EditText phone=input("Your company SIM number (+countrycode...)");
  button("Submit request",()->{
   String n=name.getText().toString().trim(),p=phone.getText().toString().trim();if(n.isEmpty()||!p.matches("\\+[0-9]{8,15}")){toast("Enter name and international SIM number");return;}
   Map<String,Object> m=new HashMap<>();m.put("uid",uid());m.put("name",n);m.put("sim",p);m.put("token",token);m.put("status","pending");m.put("requestedAt",FieldValue.serverTimestamp());
   db.collection("companies").document(cid).collection("join_requests").document(uid()).set(m).addOnSuccessListener(v->{getSharedPreferences("settings",0).edit().putString("pendingCompany",cid).apply();getSharedPreferences("onboarding",0).edit().putString(uid()+"_mode","team").apply();toast("Request submitted; owner approval required");teamWelcome();}).addOnFailureListener(e->toast(e.getMessage()));
  });button("Back",()->home());
 }
 void requests(){page("Pending join requests");db.collection("companies").document(company).collection("join_requests").whereEqualTo("status","pending").get().addOnSuccessListener(s->{
  for(DocumentSnapshot d:s){label(d.getString("name")+" | "+d.getString("sim"));button("Approve "+d.getString("name"),()->{
   Map<String,Object> m=new HashMap<>();m.put("uid",d.getId());m.put("role","employee");m.put("approved",true);m.put("sim",d.getString("sim"));m.put("assignedUsers",new ArrayList<String>());
   WriteBatch b=db.batch();b.set(db.collection("companies").document(company).collection("members").document(d.getId()),m);
   b.update(d.getReference(),"status","approved");Map<String,Object> directory=new HashMap<>();directory.put("name",company);directory.put("role","employee");directory.put("status","approved");directory.put("updatedAt",FieldValue.serverTimestamp());
   b.set(db.collection("users").document(d.getId()).collection("companies").document(company),directory);
   b.commit().addOnSuccessListener(v->requests()).addOnFailureListener(e->toast(e.getMessage()));
  });}}).addOnFailureListener(e->toast(e.getMessage()));button("Back",()->home());}
 @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);if(code==12 && checkSelfPermission(Manifest.permission.READ_CALL_LOG)==PackageManager.PERMISSION_GRANTED){if(getSharedPreferences("settings",0).getBoolean("syncConsent",false) && company.equals(getSharedPreferences("settings",0).getString("syncConsentCompany","")))CallStore.schedule(this);toast("Permissions updated. Register this device to enable uploads.");}else if(code==12)toast("Call-log access was not granted");}
 void manageTeam(){if(!isAdmin()){toast("Access denied");return;}page("Manage Company Team");
  db.collection("companies").document(company).collection("members").get().addOnSuccessListener(s->{
   for(DocumentSnapshot d:s){String id=d.getId();label(id+" | "+d.getString("role")+" | "+d.getString("sim"));
    if("owner".equals(role)&&!id.equals(uid())){
     button("Promote to admin: "+id.substring(0,Math.min(8,id.length())),()->d.getReference().update("role","admin").addOnSuccessListener(v->manageTeam()).addOnFailureListener(e->toast(e.getMessage())));
     button("Set employee role: "+id.substring(0,Math.min(8,id.length())),()->d.getReference().update("role","employee").addOnSuccessListener(v->manageTeam()).addOnFailureListener(e->toast(e.getMessage())));
     button("Remove member: "+id.substring(0,Math.min(8,id.length())),()->new android.app.AlertDialog.Builder(this)
      .setTitle("Remove team member?")
      .setMessage("Remove this employee from the company and revoke their company access. Existing company call history remains.")
      .setNegativeButton("Cancel",null).setPositiveButton("Remove",(dialog,which)->{
       WriteBatch batch=db.batch();
       batch.delete(d.getReference());
       batch.delete(db.collection("users").document(id).collection("companies").document(company));
       batch.commit().addOnSuccessListener(v->manageTeam()).addOnFailureListener(e->toast("Unable to remove member: "+e.getLocalizedMessage()));
      }).show());
     boolean approved=Boolean.TRUE.equals(d.getBoolean("approved"));
     button((approved?"Deactivate: ":"Reactivate: ")+id.substring(0,Math.min(8,id.length())),()->new android.app.AlertDialog.Builder(this).setTitle(approved?"Deactivate team member?":"Reactivate team member?")
      .setMessage(approved?"This stops the member from opening the workspace and syncing new calls after their next access check.":"Restore this member's access?")
      .setNegativeButton("Cancel",null)
      .setPositiveButton("Confirm",(dialog,which)->d.getReference().update("approved",!approved).addOnSuccessListener(v->manageTeam()).addOnFailureListener(e->toast(e.getMessage()))).show());
    }
   }
   if("owner".equals(role)){label("Assign call-log access by employee UID");EditText admin=input("Supervisor/admin UID"),target=input("Employee UID");
   button("Grant employee access",()->{
    String a=admin.getText().toString().trim(),t=target.getText().toString().trim();if(a.isEmpty()||t.isEmpty()){toast("Enter both UIDs");return;}
    db.collection("companies").document(company).collection("members").document(a).update("assignedUsers",FieldValue.arrayUnion(t)).addOnSuccessListener(v->toast("Assigned")).addOnFailureListener(e->toast(e.getMessage()));
   });}
  }).addOnFailureListener(e->toast(e.getMessage()));button("Repair missing employee workspace links",()->repairCompanyDirectory());button("Back",()->home());
 }
 void repairCompanyDirectory(){
  if(!isAdmin()||company.isEmpty())return;
  final String cid=company;
  db.collection("companies").document(cid).get(Source.SERVER).addOnSuccessListener(org->{
   String name=org.getString("name");
   db.collection("companies").document(cid).collection("members").get(Source.SERVER).addOnSuccessListener(list->{
    WriteBatch batch=db.batch();int count=0;
    for(DocumentSnapshot member:list){
     if(!Boolean.TRUE.equals(member.getBoolean("approved")))continue;
     Map<String,Object> entry=new HashMap<>();entry.put("name",name==null?cid:name);
     entry.put("role",member.getString("role")==null?"employee":member.getString("role"));
     entry.put("status","approved");entry.put("updatedAt",FieldValue.serverTimestamp());
     batch.set(db.collection("users").document(member.getId()).collection("companies").document(cid),entry);
     count++;
    }
    if(count==0){toast("No approved members to repair");return;}
    final int repaired=count;
    batch.commit().addOnSuccessListener(v->toast("Workspace links refreshed for "+repaired+" members"))
     .addOnFailureListener(e->toast("Unable to repair workspace links: "+e.getLocalizedMessage()));
   }).addOnFailureListener(e->toast(e.getLocalizedMessage()));
  }).addOnFailureListener(e->toast(e.getLocalizedMessage()));
 }
 void localHistory(){
  page("Local Call History");
  label("Phone calls recorded locally for this company. Swipe the table horizontally to see all fields.");
  DataTable table=new DataTable(this,panel,
   new String[]{"Date / Time","Contact","Phone Number","Call Type","Duration","Upload Status"},
   new int[]{145,145,155,135,100,115});
  ArrayList<String[]> rows=store.historyRows(company);
  label("Showing "+rows.size()+" most recent local calls (maximum 300).");
  for(String[] row:rows)table.add(row);
  if(rows.isEmpty())label("No local calls found. Check the Sync Status screen for the last scan result.");
  button("Back",()->showSection("calls"));
 }
 void syncedHistory(){
  page("My Synced Call History");
  label("These are your own call records stored in the current company, independent of CRM assignments.");
  String member=uid(),cid=company;
  db.collection("companies").document(cid).collection("call_logs")
   .whereEqualTo("uid",member).limit(200).get(Source.SERVER).addOnSuccessListener(result->{
    if(!cid.equals(company)||!member.equals(uid()))return;
    ArrayList<DocumentSnapshot> docs=new ArrayList<>(result.getDocuments());
    docs.sort((a,b)->Long.compare(b.getLong("startedAt")==null?0:b.getLong("startedAt"),a.getLong("startedAt")==null?0:a.getLong("startedAt")));
    int incoming=0,outgoing=0,missed=0;long duration=0;
    for(DocumentSnapshot d:docs){
     String kind=d.getString("type");
     if("INCOMING".equals(kind))incoming++;else if("OUTGOING".equals(kind))outgoing++;else if("MISSED".equals(kind))missed++;
     Long secs=d.getLong("durationSec");duration+=secs==null?0:secs;
    }
    label("Recent calls: "+docs.size()+" · Incoming "+incoming+" · Outgoing "+outgoing+" · Missed "+missed+" · Duration "+duration/60+" mins");
    if(docs.isEmpty())label("No synced calls yet. Verify call-sync health or wait for the background worker.");
    DataTable table=new DataTable(this,panel,
     new String[]{"Date / Time","Contact","Phone Number","Call Type","Duration","Company SIM"},
     new int[]{145,145,155,145,100,155});
    java.text.SimpleDateFormat fmt=new java.text.SimpleDateFormat("dd MMM yyyy HH:mm",java.util.Locale.getDefault());
    for(DocumentSnapshot d:docs){
     Long stamp=d.getLong("startedAt");Long seconds=d.getLong("durationSec");
     table.add(stamp==null?"—":fmt.format(new java.util.Date(stamp)),
      d.getString("contactName"),d.getString("remoteNumber"),d.getString("type"),
      (seconds==null?0:seconds)+" sec",d.getString("sim"));
    }
  }).addOnFailureListener(e->toast("Cannot fetch synced calls: "+e.getLocalizedMessage()));
  button("Back",()->showSection("calls"));
 }
 void teamLogs(){if(!isAdmin()){toast("Access denied");return;}page("Company Call Logs");withAuthorizedCalls(q->q.limit(100).get().addOnSuccessListener(s->{for(DocumentSnapshot d:s)label(d.getString("remoteNumber")+" | "+d.getString("type")+" | "+d.getLong("durationSec")+" sec");} ).addOnFailureListener(e->toast(e.getMessage())));button("Back",()->home());}
 void report(){if(!isAdmin()){toast("Access denied");return;}page("Call Summary");withAuthorizedCalls(q->q.limit(1000).get().addOnSuccessListener(s->{
   long seconds=0;Map<String,Integer> byType=new TreeMap<>();for(DocumentSnapshot d:s){String t=d.getString("type");byType.put(t,byType.getOrDefault(t,0)+1);Long v=d.getLong("durationSec");seconds+=v==null?0:v;}
   label("Sample of up to 1,000 authorized calls");label("Total calls: "+s.size());label("Total duration: "+seconds+" seconds");for(String t:byType.keySet())label(t+": "+byType.get(t));
  }).addOnFailureListener(e->toast(e.getMessage())));button("Back",()->home());}
 void withAuthorizedCalls(java.util.function.Consumer<Query> action){
  Query q=db.collection("companies").document(company).collection("call_logs");
  if(isAdmin()){action.accept(q);return;}
  db.collection("companies").document(company).collection("members").document(uid()).get().addOnSuccessListener(m->{
   List<String> ids=new ArrayList<>();ids.add(uid());Object assigned=m.get("assignedUsers");if(assigned instanceof List){for(Object value:(List<?>)assigned){String id=String.valueOf(value);if(!ids.contains(id)&&ids.size()<30)ids.add(id);}}
   action.accept(q.whereIn("uid",ids));
  }).addOnFailureListener(e->toast(e.getMessage()));
 }
}
