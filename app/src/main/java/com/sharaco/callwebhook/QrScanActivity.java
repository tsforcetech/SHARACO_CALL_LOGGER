package com.sharaco.callwebhook;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;

/** Camera preview stays inside a bounded scanning frame. */
public class QrScanActivity extends Activity {
 private DecoratedBarcodeView scanner;
 private boolean finished=false;
 @Override public void onCreate(Bundle state){
  super.onCreate(state);
  LinearLayout page=new LinearLayout(this);UiKit.screen(this,page);
  page.setGravity(Gravity.CENTER_HORIZONTAL);
  ScrollView scroll=new ScrollView(this);scroll.addView(page);setContentView(scroll);
  TextView title=new TextView(this);title.setText("Scan Company QR");UiKit.heading(title);page.addView(title);
  TextView detail=new TextView(this);detail.setText("Position the company's invitation QR within the camera frame. You will review details before submitting a join request.");
  UiKit.label(detail);detail.setPadding(0,UiKit.dp(this,12),0,UiKit.dp(this,18));page.addView(detail);
  FrameLayout frame=new FrameLayout(this);frame.setBackground(UiKit.shape(this,android.graphics.Color.BLACK,18,UiKit.BORDER));frame.setClipToOutline(true);
  LinearLayout.LayoutParams frameParams=new LinearLayout.LayoutParams(-1,UiKit.dp(this,310));frameParams.setMargins(0,UiKit.dp(this,10),0,UiKit.dp(this,16));page.addView(frame,frameParams);
  scanner=new DecoratedBarcodeView(this);
  frame.addView(scanner,new FrameLayout.LayoutParams(-1,-1));
  scanner.decodeSingle(new BarcodeCallback(){
   @Override public void barcodeResult(BarcodeResult result){
    if(finished||result==null||result.getText()==null)return;
    String value=result.getText();
    if(!value.matches("SHARACO:[A-Za-z0-9_-]{3,128}:[A-Za-z0-9]{32,128}")){
     Toast.makeText(QrScanActivity.this,"Invalid company invitation QR",Toast.LENGTH_SHORT).show();
     scanner.decodeSingle(this);return;
    }
    finished=true;scanner.pause();
    Intent out=new Intent();out.putExtra("qr",value);setResult(RESULT_OK,out);finish();
   }
  });
  TextView note=new TextView(this);note.setText("Only scan a QR supplied by a trusted company administrator.");UiKit.label(note);page.addView(note);
  Button cancel=new Button(this);cancel.setText("Cancel");UiKit.action(this,cancel,true);page.addView(cancel,new LinearLayout.LayoutParams(-1,UiKit.dp(this,52)));cancel.setOnClickListener(v->finish());
  if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.CAMERA},31);
  else scanner.resume();
 }
 @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){
  super.onRequestPermissionsResult(code,permissions,results);
  if(code==31){if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)scanner.resume();
   else{Toast.makeText(this,"Camera permission is required to scan a company QR",Toast.LENGTH_LONG).show();finish();}}
 }
 @Override protected void onResume(){super.onResume();if(scanner!=null&&checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED&&!finished)scanner.resume();}
 @Override protected void onPause(){if(scanner!=null)scanner.pause();super.onPause();}
}
