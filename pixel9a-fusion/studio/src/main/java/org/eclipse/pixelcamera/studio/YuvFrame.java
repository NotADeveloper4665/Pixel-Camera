package org.eclipse.pixelcamera.studio;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.media.Image;
import java.nio.ByteBuffer;

final class YuvFrame {
    final int width,height;
    final byte[] y,u,v;
    final long timestamp,arrival;
    YuvFrame(int width,int height,byte[] y,byte[] u,byte[] v,long timestamp,long arrival) {
        this.width=width;this.height=height;this.y=y;this.u=u;this.v=v;this.timestamp=timestamp;this.arrival=arrival;
    }
    private static byte[] plane(Image.Plane plane,int left,int top,int width,int height) {
        ByteBuffer buffer=plane.getBuffer().duplicate(); int base=buffer.position();
        byte[] output=new byte[width*height];
        for(int y=0;y<height;y++) for(int x=0;x<width;x++)
            output[y*width+x]=buffer.get(base+(y+top)*plane.getRowStride()+(x+left)*plane.getPixelStride());
        return output;
    }
    static YuvFrame copy(Image image) {
        Rect c=image.getCropRect(); Image.Plane[] p=image.getPlanes();
        if((c.width()&1)!=0||(c.height()&1)!=0) throw new IllegalArgumentException("YUV dimensions must be even");
        return new YuvFrame(c.width(),c.height(),plane(p[0],c.left,c.top,c.width(),c.height()),
                plane(p[1],c.left/2,c.top/2,c.width()/2,c.height()/2),
                plane(p[2],c.left/2,c.top/2,c.width()/2,c.height()/2),
                image.getTimestamp(),android.os.SystemClock.elapsedRealtimeNanos());
    }
    Bitmap bitmap(int maxWidth) {
        int scale=Math.max(1,(width+maxWidth-1)/maxWidth), w=width/scale,h=height/scale;
        int[] pixels=new int[w*h];
        for(int yy=0;yy<h;yy++) for(int x=0;x<w;x++) {
            int sx=x*scale,sy=yy*scale;
            int l=(y[sy*width+sx]&255)-16, cb=(u[(sy/2)*(width/2)+sx/2]&255)-128,
                    cr=(v[(sy/2)*(width/2)+sx/2]&255)-128;
            int r=clamp((298*l+409*cr+128)>>8),g=clamp((298*l-100*cb-208*cr+128)>>8),
                    b=clamp((298*l+516*cb+128)>>8);
            pixels[yy*w+x]=0xff000000|(r<<16)|(g<<8)|b;
        }
        return Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888);
    }
    private static int clamp(int v) { return Math.max(0,Math.min(255,v)); }
    static void fill(Image input,YuvFrame frame) {
        Image.Plane[] planes=input.getPlanes(); byte[][] data={frame.y,frame.u,frame.v};
        for(int p=0;p<3;p++) {
            int w=p==0?frame.width:frame.width/2,h=p==0?frame.height:frame.height/2;
            ByteBuffer buffer=planes[p].getBuffer();int base=buffer.position();
            for(int row=0;row<h;row++) for(int x=0;x<w;x++)
                buffer.put(base+row*planes[p].getRowStride()+x*planes[p].getPixelStride(),data[p][row*w+x]);
        }
    }
}
