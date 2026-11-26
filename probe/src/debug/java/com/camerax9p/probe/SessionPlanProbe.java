package com.camerax9p.probe;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.hardware.camera2.params.*;
import android.util.Size;
import android.os.Build;
import org.json.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/** Public session admission queries only. A positive answer is not capture evidence. */
final class SessionPlanProbe {
    static String run(Context context) {
        JSONObject report = new JSONObject();
        try {
            report.put("kind", "public-session-admission-only").put("api", Build.VERSION.SDK_INT);
            if (Build.VERSION.SDK_INT < 35) throw new UnsupportedOperationException("CameraDeviceSetup requires API 35");
            if (context.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) throw new SecurityException("Camera permission required");
            CameraManager manager = context.getSystemService(CameraManager.class);
            JSONArray cases = new JSONArray();
            // Real target sizes are confirmed in this phone's vendor metadata. No fabricated names/identity.
            for (String id : manager.getCameraIdList()) {
                boolean querySupported = manager.isCameraDeviceSetupSupported(id);
                if (!querySupported) {
                    cases.put(new JSONObject().put("camera",id).put("querySupported",false));
                    continue;
                }
                CameraDevice.CameraDeviceSetup setup = manager.getCameraDeviceSetup(id);
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                for (int pixels : new int[]{12,50,200}) for (boolean maximum : new boolean[]{false,true}) {
                    int w=pixels==12?4096:pixels==50?8192:16384, h=pixels==12?3072:pixels==50?6144:12288;
                    for (int format : new int[]{ImageFormat.JPEG,ImageFormat.RAW_SENSOR,ImageFormat.RAW10}) {
                        cases.put(query(setup,id,null,w,h,format,maximum));
                    }
                }
                if (!characteristics.getPhysicalCameraIds().isEmpty()) {
                    for (String physical : characteristics.getPhysicalCameraIds()) {
                        cases.put(query(setup,id,physical,8192,6144,ImageFormat.JPEG,false));
                    }
                }
            }
            report.put("cases",cases);
        } catch (Exception e) { try { report.put("error",e.toString()); } catch(JSONException ignored) {} }
        try { Files.write(new File(context.getFilesDir(),"session-plan.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8)); }
        catch(Exception e) { return "Cannot save query report: "+e; }
        return report.toString();
    }
    @android.annotation.TargetApi(35)
    private static JSONObject query(CameraDevice.CameraDeviceSetup setup,String id,String physical,int w,int h,int format,boolean maximum) throws JSONException {
        JSONObject row=new JSONObject().put("camera",id).put("physical",physical).put("width",w).put("height",h).put("format",format).put("maximumPixelMode",maximum);
        try {
            // API 35 permits query-only outputs with no Surface or image allocation.
            OutputConfiguration output=new OutputConfiguration(format,new Size(w,h));
            if(physical!=null) output.setPhysicalCameraId(physical);
            int mode=maximum?CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION:CameraMetadata.SENSOR_PIXEL_MODE_DEFAULT;
            output.addSensorPixelModeUsed(mode);
            SessionConfiguration session=new SessionConfiguration(SessionConfiguration.SESSION_REGULAR,Collections.singletonList(output),Runnable::run,new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) { s.close(); }
                @Override public void onConfigureFailed(CameraCaptureSession s) { s.close(); }
            });
            CaptureRequest.Builder parameters=setup.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            parameters.set(CaptureRequest.SENSOR_PIXEL_MODE,mode);
            session.setSessionParameters(parameters.build());
            boolean accepted=setup.isSessionConfigurationSupported(session);
            row.put("configurationAccepted",accepted);
            if(accepted) {
                try {
                    CameraCharacteristics specific=setup.getSessionCharacteristics(session);
                    row.put("sessionPixelArray",String.valueOf(specific.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)));
                    row.put("sessionMaxPixelArray",String.valueOf(specific.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION)));
                } catch(Exception e) { row.put("sessionCharacteristicsError",e.toString()); }
            }
        } catch(Exception e) { row.put("error",e.toString()); }
        return row;
    }
}
