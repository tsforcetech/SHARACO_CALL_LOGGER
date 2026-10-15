package com.sharaco.callwebhook;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import java.util.*;

public class MainActivity extends Activity {
  LinearLayout layout; FirebaseAuth auth; FirebaseFirestore db;
  void field(String title){ TextView t=new TextView(this);t.setText(title);t.setTextSize(18);t.setPadding(0,16,0,8);layout.addView(t); }
  EditText input(String hint){EditText e=new EditText(this);e.setHint(hint);layout.addView(e);return e;}
  void button(String name,Runnable r){Button b=new Button(this);b.setText(name);layout.addView(b);b.setOnClickListener(v->r.run());}
  void toast(String value){Toast.makeText(this,value,Toast.LENGTH_LONG).show();}
  void start(){layout=new LinearLayout(this);layout.setOrientation(1);layout.setPadding(28,32,28,20);ScrollView scroll=new ScrollView(this);scroll.addView(layout);setContentView(scroll);}
  @Override public void onCreate(Bundle state){super.onCreate(state);auth=FirebaseAuth.getInstance();db=FirebaseFirestore.getInstance();render();}
  void render(){start();field("SHARACO CALL LOGGER");if(auth.getCurrentUser()==null){authForm();return;}field("Account: "+auth.getCurrentUser().getEmail());button("Sign out",()->auth.signOut());field("Create company");EditText name=input("Company name");button("Create",()->{String n=name.getText().toString().trim();if(n.isEmpty()){toast("Company name required");return;}Map<String,Object> m=new HashMap<>();m.put("name",n);m.put("ownerUid",auth.getUid());m.put("createdAt",com.google.firebase.firestore.FieldValue.serverTimestamp());db.collection("companies").add(m).addOnSuccessListener(doc->{Map<String,Object> member=new HashMap<>();member.put("role","owner");member.put("uid",auth.getUid());member.put("approved",true);doc.collection("members").document(auth.getUid()).set(member).addOnSuccessListener(v->toast("Company created")).addOnFailureListener(e->toast(e.getMessage()));}).addOnFailureListener(e->toast(e.getMessage()));});field("My companies");db.collection("companies").whereEqualTo("ownerUid",auth.getUid()).get().addOnSuccessListener(s->{for(com.google.firebase.firestore.QueryDocumentSnapshot d:s){field(d.getString("name")+" ("+d.getId()+")");}}).addOnFailureListener(e->toast(e.getMessage()));}
  void authForm(){field("Login or register");EditText email=input("Email");EditText password=input("Password");password.setInputType(129);button("Sign in",()->auth.signInWithEmailAndPassword(email.getText().toString().trim(),password.getText().toString()).addOnSuccessListener(r->render()).addOnFailureListener(e->toast(e.getMessage())));button("Register",()->auth.createUserWithEmailAndPassword(email.getText().toString().trim(),password.getText().toString()).addOnSuccessListener(r->render()).addOnFailureListener(e->toast(e.getMessage())));}
}
