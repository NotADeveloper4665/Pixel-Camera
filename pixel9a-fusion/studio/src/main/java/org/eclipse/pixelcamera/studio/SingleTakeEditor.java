package org.eclipse.pixelcamera.studio;

import android.graphics.*;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.segmentation.*;
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions;
import java.nio.FloatBuffer;
import java.util.*;

/** Bundled on-device person segmentation drives the portrait blur and crop. */
final class SingleTakeEditor implements AutoCloseable {
    private Segmenter segmenter;
    int portraits,photos,aiFailures;
    SingleTakeEditor(){
        try{
            segmenter=Segmentation.getClient(new SelfieSegmenterOptions.Builder()
                    .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).enableRawSizeMask().build());
        }catch(RuntimeException|LinkageError failure){aiFailures++;segmenter=null;}
    }
    void export(MediaExport output,String name,YuvFrame frame,int orientation)throws Exception{
        Bitmap sensor=frame.bitmap(1280),original=sensor;
        try{
            if(orientation!=0){Matrix rotation=new Matrix();rotation.postRotate(orientation);
                original=Bitmap.createBitmap(sensor,0,0,sensor.getWidth(),sensor.getHeight(),rotation,true);}
            output.photo(name+"_best",original);photos++;
            SegmentationMask mask=null;
            if(segmenter!=null)try{mask=Tasks.await(segmenter.process(InputImage.fromBitmap(original,0)));}
            catch(java.util.concurrent.ExecutionException|RuntimeException failure){aiFailures++;}
            int width=original.getWidth(),height=original.getHeight();
            int[] pixels=new int[width*height];original.getPixels(pixels,0,width,0,0,width,height);
            float[] probability=null;int mw=0,mh=0;double weight=0,cx=0,cy=0;
            if(mask!=null){
                mw=mask.getWidth();mh=mask.getHeight();
                FloatBuffer data=mask.getBuffer().duplicate().order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();data.rewind();probability=new float[mw*mh];data.get(probability);
                for(int y=0;y<mh;y++)for(int x=0;x<mw;x++){
                    float confidence=probability[y*mw+x];
                    if(confidence>.65f){double w=confidence;weight+=w;cx+=w*(x+.5)/mw;cy+=w*(y+.5)/mh;}
                }
            }
            boolean person=weight>mw*mh*.015&&weight<mw*mh*.9;
            Bitmap filtered=Bitmap.createBitmap(filter(pixels,person),width,height,Bitmap.Config.ARGB_8888);
            try{output.photo(name+(person?"_portrait_tone":"_vivid"),filtered);photos++;}finally{filtered.recycle();}
            {
                double centerX=person?TakeSelection.cropCenter(cx,weight):.5;
                double centerY=person?TakeSelection.cropCenter(cy,weight):.5;
                int cropWidth=Math.min(width,(int)(height*.8)),cropHeight=Math.min(height,(int)(width/.8));
                int left=Math.max(0,Math.min(width-cropWidth,(int)(centerX*width)-cropWidth/2));
                int top=Math.max(0,Math.min(height-cropHeight,(int)(centerY*height)-cropHeight/2));
                Bitmap crop=Bitmap.createBitmap(original,left,top,cropWidth,cropHeight);
                try{output.photo(name+(person?"_subject_crop":"_composition_crop"),crop);photos++;}finally{if(crop!=original)crop.recycle();}
            }
            if(person){
                int[] blurred=blur(pixels,width,height,10);
                for(int y=0;y<height;y++)for(int x=0;x<width;x++){
                    float probabilityHere=sample(probability,mw,mh,(x+.5f)*mw/width-.5f,(y+.5f)*mh/height-.5f);
                    float t=Math.max(0,Math.min(1,(probabilityHere-.2f)/.6f));t=t*t*(3-2*t);
                    int i=y*width+x,src=pixels[i],bg=blurred[i];
                    int r=Math.round(((src>>16)&255)*t+((bg>>16)&255)*(1-t));
                    int g=Math.round(((src>>8)&255)*t+((bg>>8)&255)*(1-t));
                    int b=Math.round((src&255)*t+(bg&255)*(1-t));
                    blurred[i]=0xff000000|(r<<16)|(g<<8)|b;
                }
                Bitmap portrait=Bitmap.createBitmap(blurred,width,height,Bitmap.Config.ARGB_8888);
                try{output.photo(name+"_bokeh",portrait);photos++;portraits++;}finally{portrait.recycle();}
            }
        }finally{if(original!=sensor)original.recycle();sensor.recycle();}
    }
    private static float sample(float[] mask,int w,int h,float x,float y){
        x=Math.max(0,Math.min(w-1,x));y=Math.max(0,Math.min(h-1,y));
        int ix=(int)x,iy=(int)y,nx=Math.min(w-1,ix+1),ny=Math.min(h-1,iy+1);
        float dx=x-ix,dy=y-iy;
        return (1-dy)*((1-dx)*mask[iy*w+ix]+dx*mask[iy*w+nx])+
                dy*((1-dx)*mask[ny*w+ix]+dx*mask[ny*w+nx]);
    }
    private static int[] filter(int[] source,boolean portrait){
        int[] out=new int[source.length];
        for(int i=0;i<out.length;i++){
            int p=source[i];float r=(p>>16)&255,g=(p>>8)&255,b=p&255;
            float l=.2126f*r+.7152f*g+.0722f*b,saturation=portrait?1.04f:1.18f;
            r=l+(r-l)*saturation;g=l+(g-l)*saturation;b=l+(b-l)*saturation;
            out[i]=0xff000000|(clamp((r-128)*1.05f+128+(portrait?4:0))<<16)|
                    (clamp((g-128)*1.05f+128)<<8)|clamp((b-128)*1.05f+128-(portrait?3:0));
        }return out;
    }
    private static int clamp(float value){return Math.max(0,Math.min(255,Math.round(value)));}
    private static int[] blur(int[] input,int width,int height,int radius){
        int[] horizontal=new int[input.length],out=new int[input.length];
        for(int y=0;y<height;y++){
            int r=0,g=0,b=0,n=2*radius+1;
            for(int k=-radius;k<=radius;k++){int p=input[y*width+Math.max(0,Math.min(width-1,k))];r+=(p>>16)&255;g+=(p>>8)&255;b+=p&255;}
            for(int x=0;x<width;x++){
                horizontal[y*width+x]=0xff000000|((r/n)<<16)|((g/n)<<8)|(b/n);
                int a=input[y*width+Math.max(0,x-radius)],z=input[y*width+Math.min(width-1,x+radius+1)];
                r+=((z>>16)&255)-((a>>16)&255);g+=((z>>8)&255)-((a>>8)&255);b+=(z&255)-(a&255);
            }
        }
        for(int x=0;x<width;x++){
            int r=0,g=0,b=0,n=2*radius+1;
            for(int k=-radius;k<=radius;k++){int p=horizontal[Math.max(0,Math.min(height-1,k))*width+x];r+=(p>>16)&255;g+=(p>>8)&255;b+=p&255;}
            for(int y=0;y<height;y++){
                out[y*width+x]=0xff000000|((r/n)<<16)|((g/n)<<8)|(b/n);
                int a=horizontal[Math.max(0,y-radius)*width+x],z=horizontal[Math.min(height-1,y+radius+1)*width+x];
                r+=((z>>16)&255)-((a>>16)&255);g+=((z>>8)&255)-((a>>8)&255);b+=(z&255)-(a&255);
            }
        }return out;
    }
    @Override public void close(){if(segmenter!=null)segmenter.close();}
}
