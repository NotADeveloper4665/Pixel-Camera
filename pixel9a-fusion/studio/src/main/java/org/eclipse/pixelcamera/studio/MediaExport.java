package org.eclipse.pixelcamera.studio;

import android.content.*;
import android.graphics.Bitmap;
import android.media.*;
import android.net.Uri;
import android.provider.MediaStore;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;

/** Keeps every output pending until the entire take has been exported. */
final class MediaExport implements AutoCloseable {
    private final Context context;
    private final List<Uri> pending=new ArrayList<>();
    private boolean committed;
    MediaExport(Context context){this.context=context;}
    Uri add(String name,String mime,Uri collection,String directory,File source,Bitmap image) throws Exception {
        ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);
        values.put(MediaStore.MediaColumns.MIME_TYPE,mime);values.put(MediaStore.MediaColumns.RELATIVE_PATH,directory);
        values.put(MediaStore.MediaColumns.IS_PENDING,1);
        Uri uri=context.getContentResolver().insert(collection,values);
        if(uri==null)throw new IOException("Cannot create "+name);pending.add(uri);
        try(OutputStream out=context.getContentResolver().openOutputStream(uri,"w")) {
            if(out==null)throw new IOException("Cannot write "+name);
            if(image!=null) {if(!image.compress(Bitmap.CompressFormat.JPEG,95,out))throw new IOException("JPEG failed");}
            else try(InputStream in=new FileInputStream(source)){in.transferTo(out);}
        }
        return uri;
    }
    Uri photo(String name,Bitmap image)throws Exception{
        return add(name+".jpg","image/jpeg",MediaStore.Images.Media.EXTERNAL_CONTENT_URI,"DCIM/Pixel9aStudio",null,image);
    }
    Uri video(String name,File file)throws Exception{
        return add(name+".mp4","video/mp4",MediaStore.Video.Media.EXTERNAL_CONTENT_URI,"Movies/Pixel9aStudio",file,null);
    }
    void metadata(String name,String json)throws Exception{
        File file=File.createTempFile("take_report_",".json",context.getCacheDir());
        try {
            try(Writer writer=new OutputStreamWriter(new FileOutputStream(file),java.nio.charset.StandardCharsets.UTF_8)){writer.write(json);}
            add(name+".json","application/json",MediaStore.Downloads.EXTERNAL_CONTENT_URI,"Download/Pixel9aStudio",file,null);
        }finally{file.delete();}
    }
    void commit() throws Exception {
        ArrayList<ContentProviderOperation> operations=new ArrayList<>();
        for(Uri uri:pending)operations.add(ContentProviderOperation.newUpdate(uri)
                .withValue(MediaStore.MediaColumns.IS_PENDING,0).withExpectedCount(1).build());
        context.getContentResolver().applyBatch(MediaStore.AUTHORITY,operations);committed=true;
    }
    @Override public void close(){
        if(!committed)for(Uri uri:pending)try{context.getContentResolver().delete(uri,null,null);}catch(Exception ignored){}
    }
    static File withAudio(File video,File audio,long audioOffsetUs,int orientation,File destination)throws Exception{
        if(audio==null)return video;
        MediaExtractor v=new MediaExtractor(),a=new MediaExtractor();
        MediaMuxer muxer=null;boolean started=false,success=false;
        try {
            v.setDataSource(video.getAbsolutePath());a.setDataSource(audio.getAbsolutePath());
            int vi=track(v,"video/"),ai=track(a,"audio/");
            if(vi<0||ai<0)throw new IOException("Missing video or microphone track");
            muxer=new MediaMuxer(destination.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int vo=muxer.addTrack(v.getTrackFormat(vi)),ao=muxer.addTrack(a.getTrackFormat(ai));
            muxer.setOrientationHint(orientation);muxer.start();started=true;
            v.selectTrack(vi);a.selectTrack(ai);
            copy(v,muxer,vo,0,4*1024*1024);
            copy(a,muxer,ao,audioOffsetUs,256*1024);
            muxer.stop();started=false;success=true;return destination;
        }finally{
            v.release();a.release();
            if(muxer!=null){if(started)try{muxer.stop();}catch(Exception ignored){}muxer.release();}
            if(!success)destination.delete();
        }
    }
    private static int track(MediaExtractor e,String prefix){
        for(int i=0;i<e.getTrackCount();i++){
            String mime=e.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if(mime!=null&&mime.startsWith(prefix))return i;
        }return -1;
    }
    private static void copy(MediaExtractor e,MediaMuxer m,int track,long offset,int capacity)throws Exception{
        ByteBuffer buffer=ByteBuffer.allocateDirect(capacity);MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
        long last=-1;
        while(true){
            buffer.clear();int size=e.readSampleData(buffer,0);if(size<0)break;
            long pts=e.getSampleTime()+offset;
            if(pts>=0){
                info.set(0,size,Math.max(last+1,pts),(e.getSampleFlags()&MediaExtractor.SAMPLE_FLAG_SYNC)!=0?MediaCodec.BUFFER_FLAG_KEY_FRAME:0);
                buffer.position(0);buffer.limit(size);m.writeSampleData(track,buffer,info);last=info.presentationTimeUs;
            }
            e.advance();
        }
    }
}
