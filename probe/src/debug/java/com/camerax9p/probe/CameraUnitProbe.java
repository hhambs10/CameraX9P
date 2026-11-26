package com.camerax9p.probe;

import android.content.Context;
import org.json.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.*;

/** Local diagnostic of the installed vendor library. Never impersonates another package. */
final class CameraUnitProbe {
    static String run(Context context) {
        JSONObject report = new JSONObject();
        Object instance = null;
        Class<?> type = null;
        try {
            report.put("package", context.getPackageName());
            type = Class.forName("com.oplus.ocs.camera.CameraUnitImpl");
            report.put("libraryLoadable", true);
            JSONArray methods = new JSONArray();
            for (Method method : type.getMethods()) if (method.getDeclaringClass() != Object.class) methods.put(method.toString());
            report.put("publicMethods", methods);
            instance = type.getConstructor().newInstance();
            Object authed = type.getMethod("isAuthedClient", Context.class).invoke(instance, context);
            report.put("authenticatedClient", authed);
            if (Boolean.TRUE.equals(authed)) {
                type.getMethod("initialize",Context.class).invoke(instance,context);
                report.put("cameraTypes",String.valueOf(type.getMethod("getAllSupportCameraType").invoke(instance)));
                report.put("cameraModes",String.valueOf(type.getMethod("getAllSupportCameraMode").invoke(instance)));
            } else {
                report.put("status","Library available; this application is not authenticated. No privileged capture attempted.");
            }
        } catch(Throwable e) {
            try { report.put("error",String.valueOf(e instanceof InvocationTargetException ? ((InvocationTargetException)e).getCause() : e)); } catch(JSONException ignored) {}
        } finally {
            if(instance!=null && type!=null) try { type.getMethod("release").invoke(instance); } catch(Throwable ignored) {}
        }
        try { Files.write(new File(context.getFilesDir(),"cameraunit.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8)); }
        catch(Exception e) { return "Cannot save CameraUnit report: "+e; }
        return report.toString();
    }
}
