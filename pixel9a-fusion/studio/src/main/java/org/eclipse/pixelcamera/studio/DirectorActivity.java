package org.eclipse.pixelcamera.studio;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.util.*;

public final class DirectorActivity extends Activity implements DualController.Listener {
    private final ImageView[] images=new ImageView[2];
    private final TextView[] lensLabels=new TextView[2];
    private Spinner pair;
    private Switch single;
    private CheckBox microphone;
    private Button capture,last,reconnect;
    private TextView status;
    private DualController controller;
    private List<DualPlan> choices=Collections.emptyList();
    private boolean resumed,live,working,recording,pendingStart;
    private String selectedTitle;
    private Uri lastVideo;
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.BLACK);
        root.setOnApplyWindowInsetsListener((view,insets)->{
            Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;
        });
        TextView title=text("DIRECTOR'S VIEW",21);title.setPadding(16,16,16,8);root.addView(title);
        pair=new Spinner(this);root.addView(pair);
        for(int i=0;i<2;i++){
            FrameLayout frame=new FrameLayout(this);
            images[i]=new ImageView(this);images[i].setScaleType(ImageView.ScaleType.FIT_CENTER);
            frame.addView(images[i],new FrameLayout.LayoutParams(-1,-1));
            lensLabels[i]=text(i==0?"Camera 1":"Camera 2",15);
            frame.addView(lensLabels[i],new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.START));
            root.addView(frame,new LinearLayout.LayoutParams(-1,0,1));
        }
        single=new Switch(this);single.setText("Single Take · 8 seconds + automatic photos");
        single.setTextColor(Color.WHITE);single.setChecked("single_take".equals(getIntent().getStringExtra("mode")));root.addView(single);
        microphone=new CheckBox(this);microphone.setText("Record sound");microphone.setTextColor(Color.WHITE);
        microphone.setChecked(true);root.addView(microphone);
        status=text("Discovering simultaneous camera support…",14);status.setPadding(16,8,16,8);root.addView(status);
        capture=new Button(this);capture.setText(single.isChecked()?"Capture Single Take":"Record both cameras");
        capture.setEnabled(false);capture.setOnClickListener(v->capture());root.addView(capture);
        LinearLayout actions=new LinearLayout(this);
        Button back=new Button(this);back.setText("Fusion");back.setOnClickListener(v->finish());
        last=new Button(this);last.setText("Last video");last.setEnabled(false);
        last.setOnClickListener(v->{
            if(lastVideo==null)return;
            try{startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(lastVideo,"video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));}
            catch(ActivityNotFoundException e){status("No video viewer installed");}
        });
        reconnect=new Button(this);reconnect.setText("Reconnect");
        reconnect.setOnClickListener(v->{if(controller!=null&&!working&&!choices.isEmpty())controller.select(pair.getSelectedItemPosition());});
        actions.addView(back,new LinearLayout.LayoutParams(0,-2,1));
        actions.addView(reconnect,new LinearLayout.LayoutParams(0,-2,1));
        actions.addView(last,new LinearLayout.LayoutParams(0,-2,1));
        root.addView(actions);setContentView(root);root.requestApplyInsets();
        pair.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(AdapterView<?> parent,View view,int position,long id){
                if(controller!=null&&!working){
                    selectedTitle=choices.get(position).title;
                    if(single.isChecked()&&!choices.get(position).logical)single.setChecked(false);
                    live=false;capture.setEnabled(false);controller.select(position);
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent){}
        });
        single.setOnCheckedChangeListener((button,checked)->{
            if(working)return;
            if(checked){
                int rear=-1;for(int i=0;i<choices.size();i++)if(choices.get(i).logical){rear=i;break;}
                if(rear<0){single.setChecked(false);status("Main + ultrawide is not exposed by Android on this build.");return;}
                if(pair.getSelectedItemPosition()!=rear)pair.setSelection(rear);
            }
            updateControls();
        });
    }
    private TextView text(String value,int size){TextView v=new TextView(this);v.setText(value);v.setTextColor(Color.WHITE);v.setTextSize(size);return v;}
    @Override protected void onResume(){
        super.onResume();resumed=true;
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.CAMERA},20);
        else open();
    }
    private void open(){if(resumed&&controller==null){controller=new DualController(this,this);controller.discover();}}
    @Override protected void onPause(){
        resumed=false;live=false;if(controller!=null){controller.close();controller=null;}
        working=false;recording=false;updateControls();super.onPause();
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==20){
            if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)open();
            else status("Camera permission is needed to open the two streams.");
        }else if(request==21){
            if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED){pendingStart=true;if(live&&!working){pendingStart=false;capture();}}
            else{pendingStart=false;microphone.setChecked(false);status("Microphone permission declined. Press Record again to capture without sound.");}
        }
    }
    private void capture(){
        if(controller==null)return;
        if(recording){capture.setEnabled(false);controller.stop();return;}
        if(!live||working)return;
        if(microphone.isChecked()&&checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            pendingStart=true;requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},21);return;
        }
        if(single.isChecked()&&!choices.get(pair.getSelectedItemPosition()).logical){
            status("Select Main + ultrawide for Single Take.");return;
        }
        controller.start(single.isChecked(),microphone.isChecked());
    }
    private void updateControls(){
        capture.setEnabled(recording||live&&!working);
        capture.setText(recording?"Stop both cameras":working?"Finishing…":single.isChecked()?"Capture Single Take":"Record both cameras");
        pair.setEnabled(!working);single.setEnabled(!working);microphone.setEnabled(!working);
        if(reconnect!=null)reconnect.setEnabled(!working&&!choices.isEmpty());
    }
    @Override public void plans(List<DualPlan> plans){
        choices=plans;live=false;
        String preferred=selectedTitle;
        pair.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,plans));
        if(plans.isEmpty()){capture.setEnabled(false);single.setEnabled(false);return;}
        if(single.isChecked()){
            int rear=-1;for(int i=0;i<plans.size();i++)if(plans.get(i).logical){rear=i;break;}
            if(rear>=0)pair.setSelection(rear);else single.setChecked(false);
        }else if(preferred!=null){
            for(int i=0;i<plans.size();i++)if(preferred.equals(plans.get(i).title)){pair.setSelection(i);break;}
        }
    }
    @Override public void ready(DualPlan plan){
        live=true;lensLabels[0].setText(plan.first.label+" · "+plan.first.size);
        lensLabels[1].setText(plan.second.label+" · "+plan.second.size);updateControls();
    }
    @Override public void unavailable(){live=false;updateControls();}
    @Override public void preview(int index,Bitmap bitmap,int orientation,boolean front){
        Matrix transform=new Matrix();transform.postRotate(orientation);if(front)transform.postScale(-1,1);
        Bitmap upright=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),transform,true);
        images[index].setImageBitmap(upright);
        if(upright!=bitmap)bitmap.recycle();
    }
    @Override public void busy(boolean busy,boolean isRecording){
        working=busy;recording=isRecording;updateControls();
        if(!busy&&live&&pendingStart){pendingStart=false;capture();}
    }
    @Override public void status(String value){status.setText(value);}
    @Override public void saved(Uri uri,String summary){lastVideo=uri;last.setEnabled(true);status(summary);}
}
