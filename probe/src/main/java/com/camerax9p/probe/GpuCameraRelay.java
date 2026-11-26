package com.camerax9p.probe;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLExt;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Relays one Camera2 texture to the recorder and viewfinder surfaces. */
final class GpuCameraRelay implements AutoCloseable {
    private static final int[] END = {EGL14.EGL_NONE};
    private final HandlerThread renderer = new HandlerThread("camera-relay");
    private final Handler handler;
    private final boolean sensorUsesElapsedRealtime;
    private final FloatBuffer vertices = ByteBuffer.allocateDirect(16 * Float.BYTES)
        .order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final float[] textureMatrix = new float[16];
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface encodeWindow = EGL14.EGL_NO_SURFACE, previewWindow = EGL14.EGL_NO_SURFACE;
    private SurfaceTexture texture;
    private Surface input;
    private int program, positions, coordinates, transform, textureId;
    private int encodeWidth, encodeHeight, previewWidth, previewHeight;
    private volatile boolean active;
    private long presentationOffset, lastPresentationTime = -1;
    private boolean presentationClockSet;
    private long encodedFrames, previewFrames;
    private volatile RuntimeException drawFailure;
    private boolean previewFailed;

    record Stats(long encodedFrames, long previewFrames, boolean previewFailed, boolean interrupted) {}

    static boolean supportedDevice() {
        return "OPPO".equalsIgnoreCase(Build.MANUFACTURER) && "CPH2791".equalsIgnoreCase(Build.MODEL);
    }

    GpuCameraRelay(Surface encoder, Surface preview, int width, int height, boolean sensorUsesElapsedRealtime)
            throws Exception {
        this.sensorUsesElapsedRealtime = sensorUsesElapsedRealtime;
        vertices.put(new float[]{-1,-1,0,0, 1,-1,1,0, -1,1,0,1, 1,1,1,1}).position(0);
        renderer.start();
        handler = new Handler(renderer.getLooper());
        try {
            call(() -> configure(encoder, preview, width, height));
        } catch (Exception failure) {
            close();
            throw failure;
        }
    }

    Surface input() { return input; }
    void start() { active = true; }
    boolean interrupted() { return drawFailure != null; }

    Stats finish() throws Exception {
        active = false;
        // Drain any frame already being forwarded before the recorder is stopped.
        call(() -> {});
        if (encodedFrames == 0 && drawFailure != null) throw drawFailure;
        return new Stats(encodedFrames, previewFrames, previewFailed, interrupted());
    }

    private void configure(Surface encoder, Surface preview, int width, int height) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        require(EGL14.eglInitialize(display, new int[2], 0, new int[2], 0), "initialize EGL");
        int[] attributes = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            0x3142, 1, EGL14.EGL_NONE}; // EGL_RECORDABLE_ANDROID
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        require(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)
            && count[0] == 1, "select EGL config");
        EGLConfig config = configs[0];
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        encodeWindow = EGL14.eglCreateWindowSurface(display, config, encoder, END, 0);
        previewWindow = EGL14.eglCreateWindowSurface(display, config, preview, END, 0);
        makeCurrent(encodeWindow);
        encodeWidth = dimension(encodeWindow, EGL14.EGL_WIDTH);
        encodeHeight = dimension(encodeWindow, EGL14.EGL_HEIGHT);
        previewWidth = dimension(previewWindow, EGL14.EGL_WIDTH);
        previewHeight = dimension(previewWindow, EGL14.EGL_HEIGHT);
        int vertex = shader(GLES20.GL_VERTEX_SHADER, """
            attribute vec2 pos; attribute vec2 uv; uniform mat4 tex;
            varying vec2 uvOut;
            void main() { gl_Position = vec4(pos, 0., 1.); uvOut = (tex * vec4(uv, 0., 1.)).xy; }
            """);
        int fragment = shader(GLES20.GL_FRAGMENT_SHADER, """
            #extension GL_OES_EGL_image_external : require
            precision mediump float; uniform samplerExternalOES camera; varying vec2 uvOut;
            void main() { gl_FragColor = texture2D(camera, uvOut); }
            """);
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertex);
        GLES20.glAttachShader(program, fragment);
        GLES20.glLinkProgram(program);
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, count, 0);
        if (count[0] == 0) throw new IllegalStateException(GLES20.glGetProgramInfoLog(program));
        GLES20.glDeleteShader(vertex);
        GLES20.glDeleteShader(fragment);
        positions = GLES20.glGetAttribLocation(program, "pos");
        coordinates = GLES20.glGetAttribLocation(program, "uv");
        transform = GLES20.glGetUniformLocation(program, "tex");
        GLES20.glGenTextures(1, count, 0);
        textureId = count[0];
        int external = GLES11Ext.GL_TEXTURE_EXTERNAL_OES;
        GLES20.glBindTexture(external, textureId);
        GLES20.glTexParameteri(external, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(external, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(external, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(external, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        texture = new SurfaceTexture(textureId);
        texture.setDefaultBufferSize(width, height);
        input = new Surface(texture);
        texture.setOnFrameAvailableListener(ignored -> forward(), handler);
    }

    private void forward() {
        if (drawFailure != null) return;
        try {
            texture.updateTexImage();
            if (!active) return;
            texture.getTransformMatrix(textureMatrix);
            long time = texture.getTimestamp();
            if (!presentationClockSet) {
                long monotonicBefore = System.nanoTime();
                long elapsed = android.os.SystemClock.elapsedRealtimeNanos();
                long monotonicAfter = System.nanoTime();
                long monotonic = monotonicBefore + (monotonicAfter - monotonicBefore) / 2;
                presentationOffset = monotonic - (sensorUsesElapsedRealtime ? elapsed : time);
                presentationClockSet = true;
            }
            long presentationTime = time + presentationOffset;
            if (presentationTime <= lastPresentationTime) return;
            lastPresentationTime = presentationTime;
            draw(encodeWindow, encodeWidth, encodeHeight, presentationTime);
            if (encodedFrames++ % 2 == 0 && !previewFailed) {
                try {
                    draw(previewWindow, previewWidth, previewHeight, presentationTime);
                    previewFrames++;
                } catch (RuntimeException failure) {
                    previewFailed = true;
                    android.util.Log.w("CameraX9P", "GPU viewfinder stopped", failure);
                }
            }
        } catch (RuntimeException failure) {
            drawFailure = failure;
            android.util.Log.w("CameraX9P", "GPU video forwarding stopped", failure);
        }
    }

    private void draw(EGLSurface window, int width, int height, long time) {
        makeCurrent(window);
        GLES20.glViewport(0, 0, width, height);
        GLES20.glUseProgram(program);
        GLES20.glUniformMatrix4fv(transform, 1, false, textureMatrix, 0);
        vertices.position(0);
        GLES20.glVertexAttribPointer(positions, 2, GLES20.GL_FLOAT, false, 16, vertices);
        vertices.position(2);
        GLES20.glVertexAttribPointer(coordinates, 2, GLES20.GL_FLOAT, false, 16, vertices);
        GLES20.glEnableVertexAttribArray(positions);
        GLES20.glEnableVertexAttribArray(coordinates);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        require(EGLExt.eglPresentationTimeANDROID(display, window, time), "set presentation timestamp");
        require(EGL14.eglSwapBuffers(display, window), "send GPU frame");
    }

    private void makeCurrent(EGLSurface window) {
        require(EGL14.eglMakeCurrent(display, window, window, context), "bind EGL window");
    }

    private int dimension(EGLSurface window, int key) {
        int[] size = new int[1];
        require(EGL14.eglQuerySurface(display, window, key, size, 0), "read EGL window size");
        return size[0];
    }

    private static int shader(int type, String code) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, code);
        GLES20.glCompileShader(shader);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException(GLES20.glGetShaderInfoLog(shader));
        return shader;
    }

    private static void require(boolean ok, String operation) {
        if (!ok) throw new IllegalStateException(operation + ": EGL error " + EGL14.eglGetError());
    }

    private void call(Runnable action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(action, null);
        if (!handler.post(task)) throw new IllegalStateException("Camera relay thread stopped");
        task.get(5, TimeUnit.SECONDS);
    }

    @Override public void close() {
        active = false;
        try {
            call(() -> {
                if (input != null) input.release();
                if (texture != null) texture.release();
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                    if (previewWindow != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, previewWindow);
                    if (encodeWindow != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, encodeWindow);
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
                    EGL14.eglTerminate(display);
                    EGL14.eglReleaseThread();
                }
            });
        } catch (Exception failure) {
            android.util.Log.w("CameraX9P", "Cannot release GPU camera relay", failure);
        } finally {
            renderer.quitSafely();
        }
    }
}
