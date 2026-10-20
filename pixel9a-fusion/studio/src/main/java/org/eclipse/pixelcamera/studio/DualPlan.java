package org.eclipse.pixelcamera.studio;

import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Size;
import android.util.SizeF;
import java.util.*;

final class DualPlan {
    static final class Lens {
        final String cameraId, physicalId, label;
        final CameraCharacteristics info;
        final Size size;
        Lens(String cameraId, String physicalId, String label, CameraCharacteristics info) {
            this.cameraId=cameraId; this.physicalId=physicalId; this.label=label; this.info=info;
            StreamConfigurationMap map=info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] sizes=map==null?null:map.getOutputSizes(ImageFormat.YUV_420_888);
            if(sizes==null) throw new IllegalArgumentException("No YUV stream for "+label);
            size=Arrays.stream(sizes).filter(s->s.getWidth()<=1280&&s.getHeight()<=720&&
                    s.getWidth()>=320&&s.getWidth()%2==0&&s.getHeight()%2==0)
                    .max(Comparator.comparingLong(s->(long)s.getWidth()*s.getHeight()))
                    .orElseThrow(()->new IllegalArgumentException("No supported video size for "+label));
        }
        int orientation() { Integer v=info.get(CameraCharacteristics.SENSOR_ORIENTATION); return v==null?0:v; }
        boolean front() { return Objects.equals(info.get(CameraCharacteristics.LENS_FACING),CameraCharacteristics.LENS_FACING_FRONT); }
    }
    final String title;
    final boolean logical;
    final Lens first, second;
    DualPlan(String title, boolean logical, Lens first, Lens second) {
        this.title=title; this.logical=logical; this.first=first; this.second=second;
    }
    private static double fieldOfView(CameraCharacteristics c) {
        float[] focal=c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        SizeF sensor=c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        return focal==null||focal.length==0||sensor==null?Double.NaN:sensor.getWidth()/focal[0];
    }
    static List<DualPlan> discover(CameraManager manager) throws CameraAccessException {
        List<DualPlan> result=new ArrayList<>();
        for(String logical:manager.getCameraIdList()) {
            CameraCharacteristics c=manager.getCameraCharacteristics(logical);
            if(!Objects.equals(c.get(CameraCharacteristics.LENS_FACING),CameraCharacteristics.LENS_FACING_BACK)) continue;
            List<String> rear=new ArrayList<>(c.getPhysicalCameraIds());
            rear.removeIf(id->{try{return !Double.isFinite(fieldOfView(manager.getCameraCharacteristics(id))) ||
                    !Objects.equals(manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING),
                    CameraCharacteristics.LENS_FACING_BACK);}catch(Exception e){return true;}});
            if(rear.size()<2) continue;
            rear.sort(Comparator.comparingDouble(id->{try{return fieldOfView(manager.getCameraCharacteristics(id));}
                catch(Exception e){return Double.NaN;}}));
            String main=rear.get(0), wide=rear.get(rear.size()-1);
            try {
                double ratio=fieldOfView(manager.getCameraCharacteristics(wide))/fieldOfView(manager.getCameraCharacteristics(main));
                if(ratio<1.2) continue;
                result.add(new DualPlan("Main + ultrawide",true,
                        new Lens(logical,main,"Main",manager.getCameraCharacteristics(main)),
                        new Lens(logical,wide,"Ultrawide",manager.getCameraCharacteristics(wide))));
                break;
            } catch(IllegalArgumentException ignored) {}
        }
        for(Set<String> combination:manager.getConcurrentCameraIds()) {
            List<String> ids=new ArrayList<>(combination); Collections.sort(ids);
            boolean added=false;
            for(String back:ids) for(String front:ids) {
                CameraCharacteristics b=manager.getCameraCharacteristics(back), a=manager.getCameraCharacteristics(front);
                if(!added && Objects.equals(b.get(CameraCharacteristics.LENS_FACING),CameraCharacteristics.LENS_FACING_BACK) &&
                        Objects.equals(a.get(CameraCharacteristics.LENS_FACING),CameraCharacteristics.LENS_FACING_FRONT)) {
                    try {
                        result.add(new DualPlan("Front + back",false,new Lens(back,null,"Back",b),new Lens(front,null,"Front",a)));
                        added=true;
                    } catch(IllegalArgumentException ignored) {}
                }
            }
            if(added) break;
        }
        return result;
    }
    @Override public String toString() { return title; }
}
