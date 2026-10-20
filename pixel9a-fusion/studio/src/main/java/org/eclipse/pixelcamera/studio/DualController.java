package org.eclipse.pixelcamera.studio;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.hardware.camera2.*;
import android.hardware.camera2.params.*;
import android.media.*;
import android.net.Uri;
import android.os.*;
import org.json.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

final class DualController implements AutoCloseable {
    interface Listener {
        void plans(List<DualPlan> plans);
        void ready(DualPlan plan);
        void unavailable();
        void preview(int index,Bitmap bitmap,int orientation,boolean front);
        void busy(boolean busy,boolean recording);
        void status(String message);
        void saved(Uri uri,String summary);
    }
    private static final class Feed {
        final DualPlan.Lens lens;final ImageReader reader;final int index;
        long lastPreview,lastSample;boolean seen;
        Feed(DualPlan.Lens lens,int index) {
            this.lens=lens;this.index=index;
            reader=ImageReader.newInstance(lens.size.getWidth(),lens.size.getHeight(),android.graphics.ImageFormat.YUV_420_888,2);
        }
    }
    private static final class Camera {
        final String id;final List<Feed> feeds=new ArrayList<>();
        CameraDevice device;CameraCaptureSession session;SessionConfiguration configuration;
        Camera(String id){this.id=id;}
    }
    private static final class Candidate {
        final YuvFrame frame;final double score;
        Candidate(YuvFrame frame){this.frame=frame;score=TakeSelection.score(frame.y,frame.width,frame.height);}
    }
    private final Context context;private final Listener listener;
    private final HandlerThread thread=new HandlerThread("Pixel9aDualCamera");
    private final Handler handler,main=new Handler(Looper.getMainLooper());
    private final ExecutorService previews=Executors.newSingleThreadExecutor(),exports=Executors.newSingleThreadExecutor();
    private final AtomicBoolean[] previewBusy={new AtomicBoolean(),new AtomicBoolean()};
    private final AtomicBoolean closed=new AtomicBoolean();
    private final Map<String,Camera> cameras=new LinkedHashMap<>();
    private final Set<CameraDevice> retiring=Collections.newSetFromMap(new IdentityHashMap<>());
    private List<DualPlan> plans=Collections.emptyList();
    private DualPlan plan,pending;
    private volatile int generation;
    private int opening;
    private boolean ready,recording,exporting,singleTake;
    private VideoEncoder[] encoders;
    private MediaRecorder microphone;
    private File audioFile,takeDirectory;
    private long audioStart,recordStart;
    private Runnable timer,openTimeout;
    private List<List<Candidate>> candidates;
    DualController(Context context,Listener listener) {
        this.context=context.getApplicationContext();this.listener=listener;
        thread.start();handler=new Handler(thread.getLooper());
    }
    private void ui(Runnable action){main.post(()->{if(!closed.get())action.run();});}
    private void status(String text){ui(()->listener.status(text));}
    void discover() {
        handler.post(()->{
            try {
                if(!org.eclipse.pixelcamera.fusion.CameraProfile.isPixel9a())throw new IllegalStateException("Pixel 9a required");
                plans=DualPlan.discover((CameraManager)context.getSystemService(Context.CAMERA_SERVICE));
                ui(()->listener.plans(plans));
                if(plans.isEmpty())status("Android exposes no usable main/ultrawide or front/back camera combination.");
            }catch(Exception e){status("Camera discovery failed: "+e.getMessage());}
        });
    }
    void select(int index) {
        handler.post(()->{
            if(closed.get()||recording||exporting||index<0||index>=plans.size())return;
            generation++;ready=false;pending=plans.get(index);ui(()->{listener.unavailable();listener.busy(true,false);});
            teardown();if(retiring.isEmpty()&&opening==0)openPending();
        });
    }
    private void teardown() {
        if(openTimeout!=null)handler.removeCallbacks(openTimeout);
        for(Camera camera:cameras.values()) {
            if(camera.session!=null)camera.session.close();
            for(Feed feed:camera.feeds)feed.reader.close();
            if(camera.device!=null){retiring.add(camera.device);camera.device.close();}
        }
        cameras.clear();
    }
    @SuppressLint("MissingPermission")
    private void openPending() {
        if(closed.get()||pending==null||opening>0||!retiring.isEmpty())return;
        plan=pending;pending=null;final int token=generation;
        CameraManager manager=(CameraManager)context.getSystemService(Context.CAMERA_SERVICE);
        try {
            DualPlan.Lens[] lenses={plan.first,plan.second};
            for(int i=0;i<2;i++){
                DualPlan.Lens lens=lenses[i];Feed feed=new Feed(lens,i);
                Camera camera=cameras.computeIfAbsent(lens.cameraId,Camera::new);camera.feeds.add(feed);
                feed.reader.setOnImageAvailableListener(reader->frame(feed,token),handler);
            }
            for(Camera camera:cameras.values()){
                List<OutputConfiguration> outputs=new ArrayList<>();
                for(Feed feed:camera.feeds){
                    OutputConfiguration output=new OutputConfiguration(feed.reader.getSurface());
                    if(feed.lens.physicalId!=null)output.setPhysicalCameraId(feed.lens.physicalId);
                    outputs.add(output);
                }
                camera.configuration=new SessionConfiguration(SessionConfiguration.SESSION_REGULAR,outputs,handler::post,
                        new CameraCaptureSession.StateCallback(){
                            @Override public void onConfigured(CameraCaptureSession session){
                                if(closed.get()||token!=generation){session.close();return;}
                                camera.session=session;startPreviewWhenReady(token);
                            }
                            @Override public void onConfigureFailed(CameraCaptureSession session){
                                session.close();if(token==generation)error("Camera rejected the simultaneous streams. This pair is unavailable.");
                            }
                        });
            }
            if(!plan.logical){
                Map<String,SessionConfiguration> query=new HashMap<>();
                for(Camera camera:cameras.values())query.put(camera.id,camera.configuration);
                if(!manager.isConcurrentSessionConfigurationSupported(query))
                    throw new IllegalStateException("Android does not support this concurrent recording configuration");
            }
            for(Camera camera:cameras.values()) {
                opening++;
                try { manager.openCamera(camera.id,new CameraDevice.StateCallback(){
                private boolean waiting=true;
                private void opened(){if(waiting){waiting=false;opening--;}}
                @Override public void onOpened(CameraDevice device){
                    opened();
                    if(closed.get()||token!=generation){retiring.add(device);device.close();return;}
                    camera.device=device;
                    if(cameras.values().stream().allMatch(c->c.device!=null))configureAll(token);
                }
                @Override public void onDisconnected(CameraDevice device){
                    opened();retiring.add(device);device.close();if(token==generation)error("A camera disconnected");
                }
                @Override public void onError(CameraDevice device,int code){
                    opened();retiring.add(device);device.close();if(token==generation)error("Camera unavailable ("+code+")");
                }
                @Override public void onClosed(CameraDevice device){
                    retiring.remove(device);
                    if(retiring.isEmpty()&&opening==0&&pending!=null&&!closed.get())openPending();
                }
            },handler); } catch(Exception e) {opening--;throw e;}
            }
            openTimeout=()->{if(token==generation&&!ready)error("No frames from both cameras; simultaneous mode is unavailable.");};
            handler.postDelayed(openTimeout,6000);
            status("Checking "+plan.title+" · "+plan.first.size+" / "+plan.second.size);
        }catch(Exception e){error(e.getMessage());}
    }
    private void configureAll(int token) {
        try{
            // Open every concurrent camera before configuring any session, per Camera2 contract.
            for(Camera camera:cameras.values()){
                try{
                    if(!camera.device.isSessionConfigurationSupported(camera.configuration))
                        throw new IllegalStateException("Unsupported simultaneous output configuration");
                }catch(UnsupportedOperationException ignored){/* Actual session creation is the authoritative check. */}
            }
            for(Camera camera:cameras.values())camera.device.createCaptureSession(camera.configuration);
        }catch(Exception e){if(token==generation)error("Simultaneous session failed: "+e.getMessage());}
    }
    private void startPreviewWhenReady(int token){
        if(cameras.values().stream().anyMatch(c->c.session==null))return;
        try{
            for(Camera camera:cameras.values()){
                CaptureRequest.Builder request=camera.device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                for(Feed feed:camera.feeds)request.addTarget(feed.reader.getSurface());
                camera.session.setRepeatingRequest(request.build(),null,handler);
            }
        }catch(Exception e){if(token==generation)error("Preview failed: "+e.getMessage());}
    }
    private void frame(Feed feed,int token){
        Image image=null;
        try{
            image=feed.reader.acquireLatestImage();
            if(image==null||closed.get()||token!=generation)return;
            feed.seen=true;
            if(!ready&&cameras.values().stream().flatMap(c->c.feeds.stream()).allMatch(v->v.seen)){
                ready=true;if(openTimeout!=null)handler.removeCallbacks(openTimeout);
                ui(()->{if(token==generation){listener.ready(plan);listener.busy(false,false);}});status("Both camera streams are live");
            }
            long now=SystemClock.elapsedRealtime();
            boolean show=now-feed.lastPreview>=180&&!previewBusy[feed.index].get();
            if(!recording&&!show)return;
            YuvFrame frame=YuvFrame.copy(image);
            if(recording){
                encoders[feed.index].offer(frame);
                if(singleTake&&now-feed.lastSample>=900){
                    feed.lastSample=now;Candidate candidate=new Candidate(frame);
                    List<Candidate> list=candidates.get(feed.index);
                    list.add(candidate);list.sort(Comparator.comparingDouble((Candidate c)->c.score).reversed());
                    while(list.size()>2)list.remove(list.size()-1);
                }
            }
            if(show&&previewBusy[feed.index].compareAndSet(false,true)){
                feed.lastPreview=now;
                previews.execute(()->{
                    Bitmap bitmap=null;
                    try{
                        bitmap=frame.bitmap(480);Bitmap delivered=bitmap;
                        main.post(()->{
                            if(closed.get()||token!=generation){delivered.recycle();return;}
                            listener.preview(feed.index,delivered,feed.lens.orientation(),feed.lens.front());
                        });
                    }catch(RuntimeException|OutOfMemoryError ignored){if(bitmap!=null)bitmap.recycle();}
                    finally{previewBusy[feed.index].set(false);}
                });
            }
        }catch(RuntimeException|OutOfMemoryError e){if(!closed.get()&&token==generation)error("Camera frame error: "+e.getMessage());}
        finally{if(image!=null)image.close();}
    }
    void start(boolean single,boolean sound){
        handler.post(()->{
            if(!ready||recording||exporting||closed.get())return;
            if(single&&!plan.logical){status("Single Take requires the main + ultrawide pair.");return;}
            PowerManager power=(PowerManager)context.getSystemService(Context.POWER_SERVICE);
            if(power!=null&&power.getCurrentThermalStatus()>=PowerManager.THERMAL_STATUS_SEVERE){
                status("Let the phone cool before recording two lenses.");return;
            }
            try{
                if(new StatFs(context.getCacheDir().getAbsolutePath()).getAvailableBytes()<256L*1024*1024)
                    throw new IllegalStateException("At least 256 MB of free space is needed");
                takeDirectory=new File(context.getCacheDir(),"studio_"+UUID.randomUUID());
                if(!takeDirectory.mkdirs())throw new IllegalStateException("Cannot create capture directory");
                encoders=new VideoEncoder[2];
                DualPlan.Lens[] lenses={plan.first,plan.second};
                for(int i=0;i<2;i++)encoders[i]=new VideoEncoder(new File(takeDirectory,"camera_"+i+".mp4"),
                        lenses[i].size.getWidth(),lenses[i].size.getHeight(),lenses[i].orientation());
                audioFile=null;audioStart=0;
                if(sound){
                    audioFile=new File(takeDirectory,"microphone.m4a");
                    microphone=new MediaRecorder(context);microphone.setAudioSource(MediaRecorder.AudioSource.MIC);
                    microphone.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                    microphone.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                    microphone.setAudioSamplingRate(44100);microphone.setAudioChannels(1);microphone.setAudioEncodingBitRate(128000);
                    microphone.setOutputFile(audioFile);microphone.prepare();microphone.start();
                    audioStart=SystemClock.elapsedRealtimeNanos();
                }
                candidates=Arrays.asList(new ArrayList<>(),new ArrayList<>());
                for(Camera camera:cameras.values())for(Feed feed:camera.feeds)feed.lastSample=0;
                singleTake=single;recording=true;recordStart=SystemClock.elapsedRealtime();
                ui(()->listener.busy(true,true));
                status(single?"Single Take · capturing both lenses for 8 seconds":"Multi-Record · both lenses recording");
                timer=this::tick;handler.postDelayed(timer,1000);
            }catch(Exception e){
                if(encoders!=null)for(VideoEncoder encoder:encoders)if(encoder!=null)encoder.cancel();
                releaseMicrophone();removeTake(takeDirectory);
                ui(()->listener.busy(false,false));status("Recording could not start: "+e.getMessage());
            }
        });
    }
    private void tick(){
        if(!recording)return;
        long seconds=(SystemClock.elapsedRealtime()-recordStart)/1000;
        PowerManager power=(PowerManager)context.getSystemService(Context.POWER_SERVICE);
        boolean hot=power!=null&&power.getCurrentThermalStatus()>=PowerManager.THERMAL_STATUS_SEVERE;
        if(seconds>=(singleTake?8:60)||hot){finishRecording();return;}
        status((singleTake?"Single Take · ":"Multi-Record · ")+seconds+" s · both lenses");
        handler.postDelayed(timer,1000);
    }
    void stop(){handler.post(this::finishRecording);}
    private void releaseMicrophone(){
        if(microphone!=null){try{microphone.release();}catch(Exception ignored){}microphone=null;}
    }
    private void finishRecording(){
        if(!recording)return;
        recording=false;exporting=true;if(timer!=null)handler.removeCallbacks(timer);
        boolean audioFailed=false;
        if(microphone!=null){
            try{microphone.stop();}catch(RuntimeException e){audioFailed=true;}
            releaseMicrophone();if(audioFailed){audioFile.delete();audioFile=null;}
        }
        final File sound=audioFile,directory=takeDirectory;
        final long soundStart=audioStart;
        final VideoEncoder[] videos=encoders;
        final List<List<Candidate>> selected=candidates;
        final DualPlan capturedPlan=plan;
        final boolean edits=singleTake,microphoneFailed=audioFailed;
        ui(()->listener.busy(true,false));status("Finishing both videos"+(edits?" and automatic edits…":"…"));
        exports.execute(()->{
            try{
                File[] raw=new File[2];Exception failure=null;
                for(int i=0;i<2;i++)try{raw[i]=videos[i].finish();}catch(Exception e){failure=e;}
                if(failure!=null)throw failure;
                String take="P9A_"+new java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.US).format(new Date());
                JSONObject report=new JSONObject();JSONArray sources=new JSONArray();Uri first=null;
                int photoCount=0,portraitCount=0,aiFailures=0;
                try(MediaExport output=new MediaExport(context)){
                    DualPlan.Lens[] lenses={capturedPlan.first,capturedPlan.second};
                    for(int i=0;i<2;i++){
                        File completed=MediaExport.withAudio(raw[i],sound,
                                sound==null?0:(soundStart-videos[i].firstArrival)/1000,
                                lenses[i].orientation(),new File(directory,"finished_"+i+".mp4"));
                        Uri uri=output.video(take+"_"+lenses[i].label.toLowerCase(Locale.US),completed);
                        if(first==null)first=uri;
                        JSONObject source=new JSONObject();source.put("label",lenses[i].label);
                        source.put("cameraId",lenses[i].cameraId);source.put("physicalId",lenses[i].physicalId);
                        source.put("size",lenses[i].size.toString());source.put("firstSensorTimestampNs",videos[i].firstTimestamp);
                        source.put("firstHostArrivalNs",videos[i].firstArrival);source.put("frames",videos[i].frames());
                        source.put("droppedFrames",videos[i].dropped());sources.put(source);
                    }
                    if(edits)try(SingleTakeEditor editor=new SingleTakeEditor()){
                        for(int lens=0;lens<2;lens++){
                            List<Candidate> list=selected.get(lens);list.sort(Comparator.comparingLong(c->c.frame.timestamp));
                            for(int i=0;i<list.size();i++)editor.export(output,take+"_"+lenses[lens].label.toLowerCase(Locale.US)+"_"+(i+1),
                                    list.get(i).frame,lenses[lens].orientation());
                        }
                        photoCount=editor.photos;portraitCount=editor.portraits;aiFailures=editor.aiFailures;
                    }
                    report.put("mode",edits?"single_take":"multi_record");report.put("sources",sources);
                    report.put("audio",sound!=null);report.put("microphoneStopFailed",microphoneFailed);
                    report.put("audioStartHostNs",soundStart);report.put("audioAlignment","host start/arrival estimate, not hardware sync");
                    report.put("simultaneous",true);report.put("physicalLogicalCamera",capturedPlan.logical);
                    report.put("photos",photoCount);report.put("portraits",portraitCount);report.put("aiFailures",aiFailures);
                    output.metadata(take,report.toString(2));output.commit();
                }
                Uri last=first;
                String summary="Saved 2 videos"+(sound==null?" without sound":" with shared microphone audio")+
                        (edits?", "+photoCount+" photos, "+portraitCount+" portraits":"")+
                        (aiFailures>0?" · segmentation failed on "+aiFailures+" frames":"");
                ui(()->listener.saved(last,summary));
            }catch(Exception|OutOfMemoryError e){status("Take export failed: "+e.getMessage());}
            finally{
                for(VideoEncoder encoder:videos)encoder.cancel();for(List<Candidate> list:selected)list.clear();
                removeTake(directory);
                handler.post(()->{exporting=false;ui(()->listener.busy(false,false));});
            }
        });
    }
    private static void removeTake(File directory){
        if(directory==null)return;File[] files=directory.listFiles();
        if(files!=null)for(File file:files)file.delete();directory.delete();
    }
    private void error(String message){
        if(recording)finishRecording();
        generation++;ready=false;pending=null;teardown();
        ui(()->{listener.unavailable();listener.busy(exporting,false);});status(message);
    }
    @Override public void close(){
        if(!closed.compareAndSet(false,true))return;
        handler.post(()->{
            if(recording)finishRecording();
            generation++;pending=null;teardown();releaseMicrophone();
            previews.shutdownNow();exports.shutdown();thread.quitSafely();
        });
    }
}
