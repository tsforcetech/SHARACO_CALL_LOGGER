package com.sharaco.callwebhook;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Scrollable, accessible data grid with aligned headers, alternating rows and optional row actions. */
public final class DataTable {
 private final Activity activity;
 private final LinearLayout body;
 private final int[] widths;
 private int count=0;
 public DataTable(Activity activity, LinearLayout parent, String[] headers, int[] widths){
  this.activity=activity;this.widths=widths.clone();
  HorizontalScrollView scroll=new HorizontalScrollView(activity);
  scroll.setHorizontalScrollBarEnabled(true);
  scroll.setFillViewport(true);
  body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);
  scroll.addView(body);
  LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(-1,-2);
  layout.setMargins(0,UiKit.dp(activity,10),0,UiKit.dp(activity,14));
  parent.addView(scroll,layout);
  addRow(headers,true,null);
 }
 private TextView cell(String value,int width,boolean header){
  TextView t=new TextView(activity);
  t.setText(value==null?"—":value);
  t.setTextColor(header?Color.WHITE:UiKit.INK);
  t.setTextSize(header?12:13);
  if(header)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
  t.setGravity(Gravity.CENTER_VERTICAL);
  t.setMaxLines(2);
  t.setEllipsize(android.text.TextUtils.TruncateAt.END);
  t.setPadding(UiKit.dp(activity,12),UiKit.dp(activity,12),UiKit.dp(activity,12),UiKit.dp(activity,12));
  t.setLayoutParams(new LinearLayout.LayoutParams(UiKit.dp(activity,width),-2));
  return t;
 }
 private void addRow(String[] values,boolean header,Runnable action){
  LinearLayout row=new LinearLayout(activity);
  row.setOrientation(LinearLayout.HORIZONTAL);
  row.setGravity(Gravity.CENTER_VERTICAL);
  row.setBackgroundColor(header?UiKit.INK:(count++%2==0?Color.WHITE:UiKit.SURFACE));
  for(int i=0;i<widths.length;i++)row.addView(cell(i<values.length?values[i]:"",widths[i],header));
  if(action!=null){
   row.setClickable(true);row.setFocusable(true);
   row.setContentDescription("Open record: "+android.text.TextUtils.join(", ",values));
   row.setOnClickListener(v->action.run());
  }
  body.addView(row);
  View line=new View(activity);line.setBackgroundColor(UiKit.BORDER);
  body.addView(line,new LinearLayout.LayoutParams(-1,UiKit.dp(activity,1)));
 }
 public void add(String... values){addRow(values,false,null);}
 public void addClickable(Runnable click,String... values){addRow(values,false,click);}
 public void addActions(String[] values,String firstLabel,Runnable first,String secondLabel,Runnable second){
  LinearLayout row=new LinearLayout(activity);row.setOrientation(LinearLayout.HORIZONTAL);
  row.setGravity(Gravity.CENTER_VERTICAL);
  row.setBackgroundColor(count++%2==0?Color.WHITE:UiKit.SURFACE);
  for(int i=0;i<widths.length-1;i++)row.addView(cell(i<values.length?values[i]:"",widths[i],false));
  LinearLayout buttons=new LinearLayout(activity);buttons.setOrientation(LinearLayout.HORIZONTAL);
  buttons.setPadding(UiKit.dp(activity,6),UiKit.dp(activity,5),UiKit.dp(activity,6),UiKit.dp(activity,5));
  if(first!=null){
   TextView call=new TextView(activity);call.setText(firstLabel);call.setGravity(Gravity.CENTER);
   call.setTextColor(Color.WHITE);call.setTextSize(13);call.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
   call.setBackground(UiKit.shape(activity,UiKit.BRAND,10,Color.TRANSPARENT));
   LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,UiKit.dp(activity,42),1);
   lp.setMargins(0,0,UiKit.dp(activity,5),0);buttons.addView(call,lp);
   call.setClickable(true);call.setFocusable(true);call.setOnClickListener(v->first.run());
  }
  if(second!=null){
   TextView details=new TextView(activity);details.setText(secondLabel);details.setGravity(Gravity.CENTER);
   details.setTextColor(UiKit.BRAND);details.setTextSize(13);
   details.setBackground(UiKit.shape(activity,UiKit.TINT,10,Color.TRANSPARENT));
   buttons.addView(details,new LinearLayout.LayoutParams(0,UiKit.dp(activity,42),1));
   details.setClickable(true);details.setFocusable(true);details.setOnClickListener(v->second.run());
  }
  row.addView(buttons,new LinearLayout.LayoutParams(UiKit.dp(activity,widths[widths.length-1]),-2));
  body.addView(row);
  View line=new View(activity);line.setBackgroundColor(UiKit.BORDER);
  body.addView(line,new LinearLayout.LayoutParams(-1,UiKit.dp(activity,1)));
 }
 public void clearData(){while(body.getChildCount()>2)body.removeViewAt(2);count=0;}
}
