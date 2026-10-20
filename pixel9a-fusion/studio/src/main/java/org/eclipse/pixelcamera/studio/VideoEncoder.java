package org.eclipse.pixelcamera.studio;

import android.media.*;
import java.io.File;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

final class VideoEncoder {
    private final MediaCodec codec;
    private final MediaMuxer muxer;
    private final File file;
    private final ArrayBlockingQueue<YuvFrame> queue=new ArrayBlockingQueue<>(3);
    private final AtomicBoolean stopping=new AtomicBoolean(), cancelled=new AtomicBoolean();
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Future<?> job;
    private int track=-1,frames;
    private final java.util.concurrent.atomic.AtomicInteger dropped=new java.util.concurrent.atomic.AtomicInteger();
    private boolean muxerStarted;
    volatile long firstTimestamp, firstArrival;
    VideoEncoder(File file,int width,int height,int orientation) throws Exception {
        this.file=file;
        MediaFormat format=MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,width,height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        format.setInteger(MediaFormat.KEY_BIT_RATE,Math.max(1_500_000,width*height*5));
        format.setInteger(MediaFormat.KEY_FRAME_RATE,30);format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1);
        String name=new MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format);
        if(name==null) throw new IllegalStateException("No YUV H.264 encoder");
        codec=MediaCodec.createByCodecName(name);
        MediaMuxer created=null;
        try {
            codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);codec.start();
            created=new MediaMuxer(file.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            created.setOrientationHint(orientation);
        } catch(Exception e) {
            codec.release();if(created!=null)created.release();throw e;
        }
        muxer=created;job=worker.submit(this::encode);
    }
    void offer(YuvFrame frame) {
        if(!stopping.get()&&!queue.offer(frame)) dropped.incrementAndGet();
    }
    private void encode() {
        boolean success=false;
        try {
            long lastPts=-1;
            while(!cancelled.get()&&(!stopping.get()||!queue.isEmpty())) {
                YuvFrame frame=queue.poll(20,TimeUnit.MILLISECONDS);
                if(frame!=null) {
                    int index=codec.dequeueInputBuffer(10_000);
                    if(index>=0) {
                        if(firstTimestamp==0) {firstTimestamp=frame.timestamp;firstArrival=frame.arrival;}
                        long pts=Math.max(lastPts+1,(frame.timestamp-firstTimestamp)/1000);lastPts=pts;
                        Image input=codec.getInputImage(index);
                        if(input==null) throw new IllegalStateException("Encoder has no writable YUV input image");
                        YuvFrame.fill(input,frame);
                        codec.queueInputBuffer(index,0,frame.width*frame.height*3/2,pts,0);frames++;
                    } else dropped.incrementAndGet();
                }
                drain(false);
            }
            if(cancelled.get()) throw new CancellationException();
            long deadline=android.os.SystemClock.elapsedRealtime()+3000;
            int input;
            while((input=codec.dequeueInputBuffer(10_000))<0) {
                drain(false);
                if(android.os.SystemClock.elapsedRealtime()>deadline) throw new IllegalStateException("Encoder input timeout");
            }
            codec.queueInputBuffer(input,0,0,lastPts+1,MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            drain(true);
            if(frames<2||!muxerStarted) throw new IllegalStateException("Video contains too few frames");
            muxer.stop();muxerStarted=false;success=true;
        } catch(Exception e) {throw new RuntimeException(e);}
        finally {
            queue.clear();
            try {codec.stop();}catch(Exception ignored){}codec.release();
            try {if(muxerStarted)muxer.stop();}catch(Exception ignored){}muxer.release();
            if(!success)file.delete();
        }
    }
    private void drain(boolean eos) throws Exception {
        MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
        long deadline=android.os.SystemClock.elapsedRealtime()+5000;
        while(true) {
            if(cancelled.get()||Thread.currentThread().isInterrupted())throw new CancellationException();
            int index=codec.dequeueOutputBuffer(info,eos?10_000:0);
            if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if(muxerStarted)throw new IllegalStateException("Unexpected encoder format change");
                track=muxer.addTrack(codec.getOutputFormat());muxer.start();muxerStarted=true;
            } else if(index>=0) {
                try {
                    if(info.size>0&&(info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0) {
                        if(!muxerStarted)throw new IllegalStateException("No video format");
                        java.nio.ByteBuffer buffer=codec.getOutputBuffer(index);
                        if(buffer==null)throw new IllegalStateException("No encoded buffer");
                        buffer.position(info.offset);buffer.limit(info.offset+info.size);
                        muxer.writeSampleData(track,buffer,info);
                    }
                    if((info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0)return;
                } finally {codec.releaseOutputBuffer(index,false);}
            } else if(!eos)return;
            if(eos&&android.os.SystemClock.elapsedRealtime()>deadline)throw new IllegalStateException("Encoder drain timeout");
        }
    }
    File finish() throws Exception {
        stopping.set(true);
        try {job.get(12,TimeUnit.SECONDS);return file;}
        catch(Exception e){cancelled.set(true);job.cancel(true);throw e;}
        finally {worker.shutdownNow();}
    }
    void cancel() {cancelled.set(true);stopping.set(true);job.cancel(true);worker.shutdownNow();}
    int frames() {return frames;}
    int dropped() {return dropped.get();}
}
