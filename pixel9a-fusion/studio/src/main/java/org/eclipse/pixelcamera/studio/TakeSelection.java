package org.eclipse.pixelcamera.studio;

/** Blur/exposure score for choosing frames; segmentation supplies the later subject edits. */
public final class TakeSelection {
    public static double score(byte[] luma,int width,int height) {
        if(width<8||height<8||luma.length!=width*height)throw new IllegalArgumentException("Invalid luma plane");
        double edges=0;int count=0,clipped=0;
        for(int y=2;y<height-2;y+=3)for(int x=2;x<width-2;x+=3) {
            int i=y*width+x,v=luma[i]&255;
            double edge=4*v-(luma[i-1]&255)-(luma[i+1]&255)-(luma[i-width]&255)-(luma[i+width]&255);
            edges+=edge*edge;if(v<20||v>235)clipped++;count++;
        }
        return Math.log1p(edges/count)-3.0*clipped/count;
    }
    public static double cropCenter(double weightedPosition,double weight) {
        if(!Double.isFinite(weightedPosition)||!Double.isFinite(weight)||weight<=0)return .5;
        return Math.max(.1,Math.min(.9,weightedPosition/weight));
    }
    private TakeSelection(){}
}
