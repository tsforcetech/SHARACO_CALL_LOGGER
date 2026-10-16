package com.sharaco.callwebhook;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import android.os.Build;

/** Shared lightweight design system for every native screen. Dimensions are dp. */
public final class UiKit {
 private UiKit(){}
 public static final int INK=Color.rgb(23,43,54);
 public static final int MUTED=Color.rgb(101,119,127);
 public static final int BRAND=Color.rgb(0,137,123);
 public static final int SURFACE=Color.rgb(245,248,249);
 public static final int BORDER=Color.rgb(222,232,233);
 public static final int TINT=Color.rgb(231,246,242);
 public static int dp(Activity a,int n){return Math.round(n*a.getResources().getDisplayMetrics().density);}
 public static GradientDrawable shape(Activity a,int fill,int radius,int stroke){
  GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(a,radius));
  if(stroke!=Color.TRANSPARENT)d.setStroke(dp(a,1),stroke);return d;
 }
 public static void screen(Activity a,LinearLayout panel){
  panel.setOrientation(LinearLayout.VERTICAL);
  panel.setBackgroundColor(SURFACE);
  panel.setPadding(dp(a,20),dp(a,24),dp(a,20),dp(a,36));
  if(Build.VERSION.SDK_INT>=23){
   a.getWindow().setStatusBarColor(SURFACE);
   a.getWindow().setNavigationBarColor(SURFACE);
   a.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
  }
 }
 public static void heading(TextView t){
  t.setTextColor(INK);t.setTextSize(27);t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
  t.setLetterSpacing(-0.025f);
 }
 public static void label(TextView t){
  t.setTextColor(MUTED);t.setTextSize(14);t.setLineSpacing(dpFrom(t,3),1f);
 }
 private static int dpFrom(View v,int n){return Math.round(n*v.getResources().getDisplayMetrics().density);}
 public static void input(Activity a,EditText t){
  t.setTextColor(INK);t.setHintTextColor(MUTED);t.setTextSize(15);
  t.setSingleLine(true);
  t.setPadding(dp(a,16),dp(a,12),dp(a,16),dp(a,12));
  t.setBackground(shape(a,Color.WHITE,14,BORDER));
 }
 public static void action(Activity a,Button b,boolean secondary){
  b.setAllCaps(false);b.setTextSize(15);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
  b.setTextColor(secondary?BRAND:Color.WHITE);b.setMinHeight(dp(a,50));
  b.setBackground(shape(a,secondary?TINT:BRAND,14,Color.TRANSPARENT));
  b.setElevation(0);
 }
 public static LinearLayout card(Activity a,LinearLayout parent){
  LinearLayout c=new LinearLayout(a);c.setOrientation(LinearLayout.VERTICAL);
  c.setPadding(dp(a,16),dp(a,12),dp(a,16),dp(a,12));
  c.setBackground(shape(a,Color.WHITE,18,BORDER));
  LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
  lp.setMargins(0,dp(a,7),0,dp(a,7));parent.addView(c,lp);return c;
 }
 public static void cardText(Activity a,LinearLayout parent,String headline,String detail){
  LinearLayout card=card(a,parent);
  TextView h=new TextView(a);h.setText(headline);h.setTextSize(16);h.setTypeface(Typeface.DEFAULT,Typeface.BOLD);h.setTextColor(INK);card.addView(h);
  if(detail!=null&&!detail.isEmpty()){
   TextView d=new TextView(a);d.setText(detail);label(d);
   d.setPadding(0,dp(a,6),0,0);card.addView(d);
  }
 }
 public static void subtitle(Activity a,LinearLayout panel,String text){
  TextView s=new TextView(a);s.setText(text.toUpperCase(java.util.Locale.ROOT));
  s.setTextColor(MUTED);s.setTextSize(11);s.setTypeface(Typeface.DEFAULT,Typeface.BOLD);s.setLetterSpacing(.1f);
  s.setPadding(dp(a,4),dp(a,18),0,dp(a,8));panel.addView(s);
 }
}
