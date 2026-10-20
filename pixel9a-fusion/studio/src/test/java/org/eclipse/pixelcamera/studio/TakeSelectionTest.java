package org.eclipse.pixelcamera.studio;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;
public class TakeSelectionTest {
    @Test public void ranksUsefulDetailAboveFlatOrClippedFrames(){
        byte[] flat=new byte[96*64],detail=new byte[96*64],clipped=new byte[96*64];
        Arrays.fill(flat,(byte)128);Arrays.fill(clipped,(byte)255);
        for(int y=0;y<64;y++)for(int x=0;x<96;x++)detail[y*96+x]=(byte)(80+((x/4+y/4)%2)*90);
        assertTrue(TakeSelection.score(detail,96,64)>TakeSelection.score(flat,96,64));
        assertTrue(TakeSelection.score(flat,96,64)>TakeSelection.score(clipped,96,64));
    }
    @Test public void centersCropWhenNoConfidentSubjectExists(){
        assertEquals(.5,TakeSelection.cropCenter(0,0),0);
        assertEquals(.5,TakeSelection.cropCenter(Double.NaN,1),0);
    }
    @Test public void subjectCentroidIsBoundedAndTracksOffCenterPerson(){
        assertEquals(.75,TakeSelection.cropCenter(75,100),0);
        assertEquals(.1,TakeSelection.cropCenter(-50,100),0);
        assertEquals(.9,TakeSelection.cropCenter(500,100),0);
    }
    @Test(expected=IllegalArgumentException.class)public void rejectsMalformedPlane(){
        TakeSelection.score(new byte[1],96,64);
    }
}
