package com.camerax9p.probe;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.provider.MediaStore;
import android.widget.*;
import java.io.*;
import java.nio.file.Files;
import org.json.JSONObject;

/** User-driven OEM capture/import: preserves encoded bytes and reads dimensions without decoding pixels. */
public final class NativeBridgeActivity extends Activity {
    private TextView status;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(32,80,32,32);
        status = new TextView(this);
        status.setText(R.string.native_import_intro);
        layout.addView(status);
        Button camera = new Button(this); camera.setText(R.string.open_oppo_camera); layout.addView(camera);
        camera.setOnClickListener(v -> {
            try { startActivity(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).setPackage("com.oplus.camera")); }
            catch (Exception e) {
                android.util.Log.w("CameraX9P", "Could not open OPPO Camera", e);
                status.setText(R.string.open_oppo_failure);
            }
        });
        Button pick = new Button(this); pick.setText(R.string.import_original); layout.addView(pick);
        pick.setOnClickListener(v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), 20));
        setContentView(layout);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request,result,data);
        if (request != 20 || result != RESULT_OK || data == null || data.getData() == null) return;
        status.setText(R.string.importing_original);
        new Thread(() -> {
            File temp = new File(getFilesDir(), "native-import.tmp");
            try {
                long count = 0;
                try (InputStream in = getContentResolver().openInputStream(data.getData()); OutputStream out = new FileOutputStream(temp)) {
                    if (in == null) throw new IOException("No input stream");
                    byte[] buffer = new byte[65536]; int n;
                    while ((n = in.read(buffer)) != -1) { count += n; if (count > 512L*1024*1024) throw new IOException("Import exceeds 512 MiB"); out.write(buffer,0,n); }
                }
                BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(temp.getAbsolutePath(), bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Unsupported or invalid image");
                File destination = new File(getFilesDir(),"native-original.image");
                Files.move(temp.toPath(),destination.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                JSONObject report = new JSONObject().put("width", bounds.outWidth).put("height", bounds.outHeight)
                    .put("megapixels", (long)bounds.outWidth*bounds.outHeight/1000000.0).put("bytes",count)
                    .put("encodedBytesPreserved",true).put("origin","user-selected document; native capture provenance not automatically established");
                Files.write(new File(getFilesDir(),"native-import.json").toPath(),report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                runOnUiThread(() -> status.setText(report.toString()));
            } catch (Exception e) {
                temp.delete();
                android.util.Log.w("CameraX9P", "Original import failed", e);
                runOnUiThread(() -> status.setText(R.string.import_failure));
            }
        },"native-import").start();
    }
}
