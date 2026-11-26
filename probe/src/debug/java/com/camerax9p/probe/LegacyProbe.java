package com.camerax9p.probe;

import android.content.Context;
import android.hardware.Camera;
import org.json.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;

/** Inventory only: closes each Camera1 device before opening the next. */
@SuppressWarnings("deprecation")
final class LegacyProbe {
    static String run(Context context) {
        JSONObject report=new JSONObject();
        try {
            if(context.checkSelfPermission(android.Manifest.permission.CAMERA)!=android.content.pm.PackageManager.PERMISSION_GRANTED) throw new SecurityException("Camera permission required");
            JSONArray cameras=new JSONArray();
            for(int i=0;i<Camera.getNumberOfCameras();i++) {
                Camera camera=null;
                JSONObject item=new JSONObject().put("legacyId",i);
                try {
                    camera=Camera.open(i);
                    Camera.Parameters parameters=camera.getParameters();
                    JSONArray sizes=new JSONArray();
                    for(Camera.Size size:parameters.getSupportedPictureSizes()) sizes.put(new JSONObject().put("width",size.width).put("height",size.height));
                    item.put("pictureSizes",sizes);
                    // The local report may include vendor parameter names; no media or device serial.
                    item.put("parameters",parameters.flatten());
                } catch(Exception e) { item.put("error",e.toString()); }
                finally { if(camera!=null) camera.release(); }
                cameras.put(item);
            }
            report.put("cameras",cameras);
        } catch(Exception e) { try { report.put("error",e.toString()); } catch(JSONException ignored) {} }
        try { Files.write(new File(context.getFilesDir(),"legacy.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8)); }
        catch(Exception e) { return "Cannot save legacy report: "+e; }
        return report.toString();
    }
}
