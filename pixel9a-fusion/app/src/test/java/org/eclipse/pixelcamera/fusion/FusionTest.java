package org.eclipse.pixelcamera.fusion;

import org.junit.Test;
import java.util.*;
import java.util.concurrent.CancellationException;
import static org.junit.Assert.*;

public class FusionTest {
    private Fusion.Frame pattern(int width, int height) {
        int[] pixels = new int[width*height];
        for (int y=0; y<height; y++) for (int x=0; x<width; x++) {
            int v = (int)(125 + 35*Math.sin(x*.23) + 28*Math.cos(y*.31) + 16*Math.sin(x*.13+y*.19));
            pixels[y*width+x] = 0xff000000 | v<<16 | v<<8 | v;
        }
        return new Fusion.Frame(width,height,pixels);
    }
    private Fusion.Frame translate(Fusion.Frame f, int dx, int dy) {
        int[] pixels = new int[f.pixels.length];
        for (int y=0; y<f.height; y++) for (int x=0; x<f.width; x++) {
            int sx=Math.max(0,Math.min(f.width-1,x-dx)), sy=Math.max(0,Math.min(f.height-1,y-dy));
            pixels[y*f.width+x]=f.pixels[sy*f.width+sx];
        }
        return new Fusion.Frame(f.width,f.height,pixels);
    }
    private double mse(Fusion.Frame a, Fusion.Frame b) {
        double sum=0;
        for(int i=0; i<a.pixels.length; i++) {
            int d=(a.pixels[i]&255)-(b.pixels[i]&255); sum+=d*d;
        }
        return sum/a.pixels.length;
    }
    @Test public void registersKnownTranslation() {
        Fusion.Frame reference=pattern(128,96);
        Fusion.Alignment alignment=Fusion.align(reference,translate(reference,3,-2));
        assertTrue(alignment.usable);
        assertEquals(3f,alignment.dx,.35f);
        assertEquals(-2f,alignment.dy,.35f);
    }
    @Test public void oneFramePreservesPixels() {
        Fusion.Frame original=pattern(64,64);
        Fusion.Result result=Fusion.merge(Collections.singletonList(original));
        assertArrayEquals(original.pixels,result.image.pixels);
        assertEquals(1,result.alignedFrames);
        assertEquals(1.0,result.meanContributors,0);
    }
    @Test public void flatSceneDoesNotInventShift() {
        int[] pixels=new int[64*64]; Arrays.fill(pixels,0xff7f7f7f);
        Fusion.Frame frame=new Fusion.Frame(64,64,pixels);
        Fusion.Alignment shift=Fusion.align(frame,frame);
        assertEquals(0,shift.dx,0); assertEquals(0,shift.dy,0);
        assertArrayEquals(pixels,Fusion.merge(Arrays.asList(frame,frame)).image.pixels);
    }
    @Test public void independentNoiseIsReduced() {
        Fusion.Frame clean=pattern(128,96);
        List<Fusion.Frame> burst=new ArrayList<>(); Random random=new Random(925);
        for (int k=0;k<5;k++) {
            int[] pixels=new int[clean.pixels.length];
            for(int i=0;i<pixels.length;i++) {
                int v=Math.max(0,Math.min(255,(clean.pixels[i]&255)+random.nextInt(15)-7));
                pixels[i]=0xff000000 | v<<16 | v<<8 | v;
            }
            burst.add(new Fusion.Frame(clean.width,clean.height,pixels));
        }
        Fusion.Result result=Fusion.merge(burst);
        assertTrue("Merging must reduce measured noise",mse(result.image,clean)<.7*mse(burst.get(result.referenceIndex),clean));
        assertTrue(result.alignedFrames>=3);
    }
    @Test public void motionIsRejectedRelativeToSelectedReference() {
        Fusion.Frame scene=pattern(128,96);
        int[] moved=scene.pixels.clone();
        for(int y=30;y<54;y++) for(int x=40;x<64;x++) moved[y*128+x]=0xfff8f8f8;
        List<Fusion.Frame> burst=Arrays.asList(scene,new Fusion.Frame(128,96,moved));
        Fusion.Result result=Fusion.merge(burst);
        int center=40*128+50;
        assertEquals("Do not blend a moving white object into its background",
                burst.get(result.referenceIndex).pixels[center]&255,result.image.pixels[center]&255,2);
    }
    @Test public void unrelatedFrameCannotSmearOutput() {
        Fusion.Frame a=pattern(128,96);
        int[] pixels=new int[a.pixels.length]; Random random=new Random(18);
        for(int i=0;i<pixels.length;i++) {
            int v=random.nextInt(256); pixels[i]=0xff000000 | v<<16 | v<<8 | v;
        }
        assertFalse(Fusion.align(a,new Fusion.Frame(128,96,pixels)).usable);
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsMismatchedDimensions() {
        Fusion.merge(Arrays.asList(pattern(64,64),pattern(65,64)));
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsEmptyBurst() { Fusion.merge(Collections.emptyList()); }
    @Test(expected=IllegalArgumentException.class) public void enforcesPixelBudget() {
        new Fusion.Frame(2000,2000,new int[1]);
    }
    @Test public void respectsCancellation() {
        Thread.currentThread().interrupt();
        try { Fusion.merge(Collections.singletonList(pattern(64,64))); fail("Cancellation expected"); }
        catch(CancellationException expected) { }
        finally { Thread.interrupted(); }
    }
    @Test public void splitsTenTimesZoomWithoutExceedingCameraLimit() {
        ZoomPlan plan=ZoomPlan.create(10,.5f,8);
        assertEquals(8,plan.hardware,0); assertEquals(1.25,plan.residual,0);
        assertEquals(10,plan.hardware*plan.residual,0);
    }
    @Test public void doesNotDoubleCropNativeZoom() {
        ZoomPlan plan=ZoomPlan.create(2,.5f,8);
        assertEquals(2,plan.hardware,0); assertEquals(1,plan.residual,0);
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsInventedUltrawideRange() { ZoomPlan.create(.5f,1,8); }
    @Test(expected=IllegalArgumentException.class) public void rejectsNonFiniteZoom() { ZoomPlan.create(Float.NaN,.5f,8); }
}
