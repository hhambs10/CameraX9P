package com.camerax9p.probe;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.StateListAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.util.Range;
import android.view.inputmethod.EditorInfo;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.text.style.RelativeSizeSpan;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.content.pm.PackageManager;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.*;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.view.View;
import android.widget.*;
import com.camerax9p.core.CubeLut;
import com.camerax9p.core.PhotoGeometry;
import java.io.*;
import java.util.List;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import org.json.JSONObject;

/** Interactive settings and capture harness with a live viewfinder. */
public final class CameraX9PActivity extends Activity {
    private static final int BACKGROUND = 0xff080b12;
    private static final int INK = 0xfff2f5fa;
    private static final int MUTED = 0xffb3bfce;
    private static final int ACCENT = 0xffb5defa;
    private static final int VIEWFINDER_ACTIVE = 0xffb2ebff;
    private static final int GLASS_LIGHT = 0xea283440, GLASS_DARK = 0xf0151c27;
    private static final int GLASS_EDGE = 0x467d9abb;
    private static final int PANEL_LIGHT = 0xfc283544, PANEL_DARK = 0xfc151e2b;
    private static final int ACTIVE_LIGHT = 0xe234536b, ACTIVE_DARK = 0xf01b2b40;
    private static final int ACTIVE_EDGE = 0x9785ccfc;
    private static final int RED = 0xffff737e;
    private static final int LANDSCAPE_LEFT_RAIL = 108, LANDSCAPE_RIGHT_RAIL = 112;
    private static final int[] REAR_LENSES = {R.string.rear_main, R.string.rear_ultrawide, R.string.rear_telephoto};
    private static final float[] LENS_MAGNIFICATIONS = {1f, .6f, 3f, 1f};
    private static final int[] PHOTO_FORMATS = {R.string.original_4_3, R.string.format_wide, R.string.format_xpan, R.string.format_polaroids};
    private static final String[] PHOTO_FILES = {"JPEG", "RAW", "RAW + JPEG"};
    private static final int FILE_JPEG = 0, FILE_RAW = 1, FILE_BOTH = 2;
    private static final int PHOTO_WIDE = 1, PHOTO_XPAN = 2, PHOTO_POLAROID = 3;
    private static final String[] CAMERA_IDS = {"0", "3", "4", "1"};

    private TextView status, formatChip, qualityChip, lensChip, previewTitle, previewHint, photoMode, videoMode;
    private ImageButton colorButton;
    private ImageButton reviewChip, profileChip, lapseLookButton, microphoneButton;
    private ImageButton cameraSwitchButton;
    private VideoCameraSwitch recordingCameraSwitch;
    private int lastRearLens;
    private boolean recordingFront;
    private AudioManager audioManager;
    private String microphoneKey = VideoMicrophone.PHONE;
    private List<VideoMicrophone> microphones = List.of();
    private final AudioDeviceCallback audioDevices = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] devices) { refreshMicrophones(); }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] devices) { refreshMicrophones(); }
    };
    private FrameLayout controls;
    private ImageButton screenFlashButton;
    private View screenFlashBorder;
    private boolean screenFlashOn;
    private float previousScreenBrightness;
    private boolean landscape;
    private boolean cleanRecording;
    private View shutter, shutterCenter, emptyPreview, recordDot;
    private TextView stopButton, recordTimer;
    private long recordingStartedAt;
    private java.util.concurrent.atomic.AtomicBoolean recordingStop;
    private java.util.concurrent.atomic.AtomicBoolean recordingPaused;
    private AlertDialog recoveryDialog;
    private java.util.concurrent.atomic.AtomicBoolean burstStop;
    private boolean shutterHeld;
    private int lapseIntervalMs = 500;
    private boolean starLapse;
    private StarLapse.Preset starPreset;
    private ObjectAnimator recordPulse;
    private ImageView image;
    private TextureView texture;
    private ImageButton gridChip, rotateChip;
    private TextView autofocusButton;
    private TextView focusZoomButton;
    private LinearLayout manualFocusPanel;
    private SeekBar focusSlider;
    private Float manualFocus;
    private boolean focusMagnified;
    private TextView flashButton, adjustmentButton;
    private CameraCharacteristics currentCamera;
    private volatile float zoomRatio = 1f;
    private LinearLayout zoomPanel, zoomLenses;
    private TextView zoomValue;
    private ZoomRuler zoomRuler;
    private final TextView[] zoomLensButtons = new TextView[REAR_LENSES.length];
    private final Runnable hideZoom = this::hideZoomPanel;
    private enum Adjustment { BRIGHTNESS, CONTRAST, ISO }
    private Adjustment adjustment = Adjustment.BRIGHTNESS;
    private int ev, contrast, iso, photoFlash;
    private int meteredIso;
    private boolean manualIsoSelected;
    private long manualShutter;
    private long selectedShutter;
    private WhiteBalance whiteBalance = WhiteBalance.AUTO;
    private int selfTimerSeconds;
    private Runnable pendingShutter;
    private boolean videoTorch;
    private TextView fpsChip, captionChip, fileChip;
    private View framingGrid;
    private ZebraOverlay zebraOverlay;
    private boolean zebraEnabled;
    private LevelOverlay levelOverlay;
    private ImageButton levelButton;
    private boolean levelEnabled;
    private boolean bracketEnabled;
    private View focusMarker;
    private final Rect framing = new Rect();
    private final Rect mount = new Rect();
    private boolean gridEnabled;
    private int manualRotation;
    private String polaroidCaption = "";
    private LivePreview livePreview;
    private boolean resumed, reviewing, capturing, retryCapture;
    private ListPopupWindow dropdown;
    private CubeLut lut;
    private String savedLutName;
    private static final ExecutorService LUT_IO = new java.util.concurrent.ThreadPoolExecutor(0, 2, 30,
        java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.SynchronousQueue<>());
    private final android.os.Handler lutHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private LutImport lutImport;

    private static final class LutImport {
        final android.os.CancellationSignal cancellation = new android.os.CancellationSignal();
        java.util.concurrent.Future<?> task;
        Runnable timeout;
    }
    private String lutName;
    private boolean saveLut;
    private boolean monochrome;
    private int previewRevision;
    private Bitmap original;
    private GalleryPreview galleryPreview;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean busy, recordingMode;
    private int photoFile = FILE_JPEG;
    private android.util.Size rawSize;
    private int lensIndex, photoIndex, intensity = 100, fps = 30;
    private VideoOptions videoSize = VideoOptions.FULL_GATE;
    private VideoOptions standardSize = VideoOptions.FULL_GATE;
    private VideoOptions standardWideSize = VideoOptions.UHD;
    private int standardFps = 30;
    private VideoProfile videoProfile = VideoProfile.STANDARD;
    private List<VideoProfile> availableProfiles = List.of();
    private List<VideoOptions> videoSizes = List.of();
    private List<Integer> videoRates = List.of();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        audioManager = getSystemService(AudioManager.class);
        if (state != null) {
            microphoneKey = state.getString("microphone", VideoMicrophone.PHONE);
            recordingMode = state.getBoolean("video");
            photoIndex = state.getInt("photo", state.getBoolean("xpan") ? PHOTO_XPAN : 0);
            photoFile = state.getInt("photoFile", FILE_JPEG);
            lapseIntervalMs = state.getInt("lapseIntervalMs", 500);
            starLapse = state.getBoolean("starLapse");
            zoomRatio = state.getFloat("zoomRatio", 1f);
            adjustment = Adjustment.values()[state.getInt("adjustment")];
            ev = state.getInt("ev");
            contrast = state.getInt("contrast");
            iso = state.getInt("iso");
            meteredIso = state.getInt("meteredIso", iso);
            manualIsoSelected = state.getBoolean("manualIsoSelected", iso > 0);
            manualShutter = state.getLong("manualShutter");
            selectedShutter = state.getLong("selectedShutter");
            whiteBalance = WhiteBalance.values()[state.getInt("whiteBalance")];
            selfTimerSeconds = state.getInt("selfTimerSeconds");
            zebraEnabled = state.getBoolean("zebraEnabled");
            levelEnabled = state.getBoolean("levelEnabled");
            bracketEnabled = state.getBoolean("bracketEnabled");
            if (state.containsKey("manualFocus")) manualFocus = state.getFloat("manualFocus");
            photoFlash = state.getInt("photoFlash");
            videoTorch = state.getBoolean("videoTorch");
            polaroidCaption = state.getString("polaroidCaption", "");
            gridEnabled = state.getBoolean("grid");
            manualRotation = state.getInt("rotation");
            lensIndex = state.getInt("lens");
            videoSize = VideoOptions.values()[state.getInt("size", 2)];
            fps = state.getInt("fps", 30);
            videoProfile = VideoProfile.values()[state.getInt("profile")];
            standardSize = VideoOptions.values()[state.getInt("standardSize", videoSize.ordinal())];
            standardWideSize = VideoOptions.values()[state.getInt("standardWideSize", VideoOptions.UHD.ordinal())];
            standardFps = state.getInt("standardFps", fps);
        }
        updateVideoOptions();
        landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        FrameLayout preview = new FrameLayout(this);
        preview.setBackgroundColor(BACKGROUND);
        getWindow().setDecorFitsSystemWindows(false);
        setContentView(preview);
        texture = new TextureView(this) {
            private PointF tap;
            private float downX, downY;
            private int swipeSteps;
            private boolean dragged, startedInFrame;
            private boolean multiTouch;
            private float pinchSpan;

            @Override public boolean onTouchEvent(MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    hideZoomPanel();
                    multiTouch = false;
                    pinchSpan = 0;
                } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
                    multiTouch = true;
                    pinchSpan = event.getPointerCount() == 2 ? span(event) : 0;
                    if (pinchSpan > 0 && startedInFrame && canZoom()) showZoomPanel();
                } else if (action == MotionEvent.ACTION_POINTER_UP) {
                    pinchSpan = 0;
                    if (event.getPointerCount() == 2) scheduleZoomHide(1100);
                } else if (multiTouch && action == MotionEvent.ACTION_CANCEL) {
                    scheduleZoomHide(1100);
                } else if (multiTouch && action == MotionEvent.ACTION_MOVE && event.getPointerCount() == 2
                        && startedInFrame && canZoom()) {
                    float next = span(event);
                    if (pinchSpan < dp(16)) pinchSpan = next;
                    else if (next >= dp(16) && Math.abs(next - pinchSpan) >= dp(2)) {
                        setZoomRatio(zoomRatio * next / pinchSpan);
                        pinchSpan = next;
                    }
                }
                if (multiTouch) return true;
                switch (action) {
                    case MotionEvent.ACTION_DOWN -> {
                        downX = event.getX();
                        downY = event.getY();
                        startedInFrame = framing.contains((int) downX, (int) downY);
                        swipeSteps = 0;
                        dragged = false;
                    }
                    case MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                        float vertical = downY - event.getY();
                        float horizontal = Math.abs(downX - event.getX());
                        dragged |= Math.max(horizontal, Math.abs(vertical)) > dp(12);
                        if (startedInFrame && dragged && Math.abs(vertical) > horizontal) {
                            int steps = Math.round(vertical / dp(36));
                            if (steps != swipeSteps) {
                                adjustBy(steps - swipeSteps);
                                swipeSteps = steps;
                            }
                        } else if (!dragged && event.getActionMasked() == MotionEvent.ACTION_UP) {
                            tap = new PointF(event.getX(), event.getY());
                            performClick();
                        }
                    }
                    default -> { }
                }
                return true;
            }

            private float span(MotionEvent event) {
                return (float) Math.hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0));
            }

            @Override public boolean performClick() {
                super.performClick();
                PointF point = tap;
                tap = null;
                if (point == null) focusAt(framing.exactCenterX(), framing.exactCenterY());
                else focusAt(point.x, point.y);
                return true;
            }
        };
        preview.addView(texture, new FrameLayout.LayoutParams(-1, -1));
        texture.setContentDescription(getString(R.string.viewfinder_hint));
        texture.setClickable(true);
        texture.setFocusable(true);
        screenFlashBorder = new View(this) {
            @Override protected void onDraw(Canvas canvas) {
                if (framing.isEmpty()) return;
                canvas.save();
                canvas.clipOutRect(framing);
                canvas.drawColor(Color.WHITE);
                canvas.restore();
            }
        };
        screenFlashBorder.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        screenFlashBorder.setVisibility(View.GONE);
        preview.addView(screenFlashBorder, new FrameLayout.LayoutParams(-1, -1));
        zebraOverlay = new ZebraOverlay(texture);
        preview.addView(zebraOverlay, new FrameLayout.LayoutParams(-1, -1));
        framingGrid = new View(this) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override protected void onDraw(Canvas canvas) {
                if (isPolaroid()) {
                    paint.setColor(Color.WHITE);
                    canvas.save();
                    canvas.clipOutRect(framing);
                    canvas.drawRect(mount, paint);
                    canvas.restore();
                    PolaroidCaption.draw(canvas, polaroidCaption, mount.left, framing.bottom, mount.right, mount.bottom);
                }
                if (!gridEnabled) return;
                for (int third = 1; third <= 2; third++) {
                    float x = framing.left + framing.width() * third / 3f;
                    float y = framing.top + framing.height() * third / 3f;
                    paint.setColor(0x90000000);
                    paint.setStrokeWidth(dp(3));
                    canvas.drawLine(x, framing.top, x, framing.bottom, paint);
                    canvas.drawLine(framing.left, y, framing.right, y, paint);
                    paint.setColor(0xd9ffffff);
                    paint.setStrokeWidth(dp(1));
                    canvas.drawLine(x, framing.top, x, framing.bottom, paint);
                    canvas.drawLine(framing.left, y, framing.right, y, paint);
                }
            }
        };
        framingGrid.setVisibility(View.GONE);
        preview.addView(framingGrid, new FrameLayout.LayoutParams(-1, -1));
        levelOverlay = new LevelOverlay(this, VIEWFINDER_ACTIVE);
        preview.addView(levelOverlay, new FrameLayout.LayoutParams(-1, -1));
        focusMarker = new View(this);
        focusMarker.setVisibility(View.GONE);
        focusMarker.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        preview.addView(focusMarker, new FrameLayout.LayoutParams(dp(54), dp(54)));
        image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.addView(image, new FrameLayout.LayoutParams(-1, -1));
        image.setVisibility(View.GONE);

        LinearLayout placeholder = column();
        placeholder.setGravity(Gravity.CENTER);
        previewTitle = label(getString(R.string.opening_camera), 20, INK, true);
        previewHint = label("", 14, MUTED, false);
        previewTitle.setGravity(Gravity.CENTER);
        previewHint.setGravity(Gravity.CENTER);
        placeholder.addView(previewTitle);
        placeholder.addView(previewHint);
        preview.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
        emptyPreview = placeholder;

        autofocusButton = viewfinderButton(preview, 48, this::resetFocus);
        focusZoomButton = viewfinderButton(preview, 48, () -> {
            focusMagnified = !focusMagnified;
            updateManualFocusUi();
        });
        focusZoomButton.setText("2×");
        focusZoomButton.setContentDescription(getString(R.string.focus_magnifier));
        manualFocusPanel = row();
        manualFocusPanel.setBackground(viewfinderSurface(false));
        manualFocusPanel.setPadding(dp(12), 0, dp(12), 0);
        TextView infinity = label("∞", 20, INK, true);
        TextView near = label(getString(R.string.focus_near), 12, INK, true);
        focusSlider = new SeekBar(this);
        focusSlider.setContentDescription(getString(R.string.manual_focus_range));
        focusSlider.setMax(100);
        focusSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar slider, int value, boolean fromUser) {
                if (!fromUser || !supportsManualFocus()) return;
                manualFocus = maxFocusDistance() * value / 100f;
                livePreview.setManualFocus(CAMERA_IDS[lensIndex], manualFocus);
            }
            @Override public void onStartTrackingTouch(SeekBar slider) {}
            @Override public void onStopTrackingTouch(SeekBar slider) {}
        });
        manualFocusPanel.addView(infinity);
        manualFocusPanel.addView(focusSlider, new LinearLayout.LayoutParams(0, -1, 1));
        manualFocusPanel.addView(near);
        manualFocusPanel.setVisibility(View.GONE);
        preview.addView(manualFocusPanel, new FrameLayout.LayoutParams(dp(230), dp(48), Gravity.TOP | Gravity.LEFT));
        flashButton = viewfinderButton(preview, 52, this::cycleFlash);
        levelButton = viewfinderIcon(preview, () -> {
            levelEnabled = !levelEnabled;
            updateLevel();
        });
        screenFlashButton = viewfinderIcon(preview, () -> {});
        screenFlashButton.setOnClickListener(view -> {
            setScreenFlash(!screenFlashOn);
            refreshScreenFlash();
        });
        adjustmentButton = viewfinderButton(preview, 132, this::chooseAdjustment);
        adjustmentButton.setSingleLine(true);
        adjustmentButton.setHorizontallyScrolling(false);
        adjustmentButton.setAutoSizeTextTypeUniformWithConfiguration(10, 13, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        lapseLookButton = viewfinderIcon(preview, () -> {
            if (starPreset == null) return;
            starLapse = !starLapse;
            if (starLapse) {
                setZoomRatio(1f);
                livePreview.resetFocus();
                clearFocusUi();
            }
            updateControls();
            showModeHint();
        });
        microphoneButton = viewfinderIcon(preview, this::chooseMicrophone);

        controls = new FrameLayout(this);
        preview.addView(controls, new FrameLayout.LayoutParams(-1, -1));
        controls.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.displayCutout()
                | WindowInsets.Type.navigationBars());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            if (livePreview != null) updateViewfinderArea();
            positionRecordingDot();
            positionScreenFlashButton();
            positionCameraSwitchButton();
            return insets;
        });
        controls.requestApplyInsets();
        LinearLayout framingTools = row();
        gridChip = iconButton(() -> {
            gridEnabled = !gridEnabled;
            updateFraming();
        }, 11);
        gridChip.setImageResource(R.drawable.ic_grid);
        rotateChip = iconButton(() -> {
            manualRotation = (manualRotation + 90) % 360;
            updateFraming();
            showModeHint();
        }, 11);
        rotateChip.setImageResource(R.drawable.ic_rotate);
        int toolSize = dp(landscape ? 42 : 48);
        framingTools.addView(gridChip, new LinearLayout.LayoutParams(toolSize, toolSize));
        LinearLayout.LayoutParams rotateParams = new LinearLayout.LayoutParams(toolSize, toolSize);
        rotateParams.setMarginStart(dp(landscape ? 4 : 6));
        framingTools.addView(rotateChip, rotateParams);
        placeholder.setOnClickListener(v -> refreshPreview());
        livePreview = new LivePreview(this, texture, new LivePreview.Listener() {
            @Override public void onWaiting() {
                showPreviewMessage(getString(R.string.opening_camera), "");
                refreshCameraSwitchButton();
                updateLevel();
            }
            @Override public void onFrame() {
                emptyPreview.setVisibility(View.GONE);
                refreshScreenFlash();
                refreshCameraSwitchButton();
                updateGrid();
                updateAutofocusButton();
                updateShootingTools();
            }
            @Override public void onError() {
                setScreenFlash(false);
                showPreviewMessage(getString(R.string.viewfinder_unavailable), getString(R.string.tap_retry));
                refreshScreenFlash();
                refreshCameraSwitchButton();
                updateLevel();
            }
            @Override public void onFraming(Rect frame, Rect paper) {
                framing.set(frame);
                screenFlashBorder.invalidate();
                positionRecordingDot();
                zebraOverlay.setFrame(frame);
                levelOverlay.setFrame(frame);
                mount.set(paper);
                autofocusButton.setTranslationX(frame.left + dp(6));
                autofocusButton.setTranslationY(frame.top + dp(6));
                flashButton.setTranslationX(frame.right - dp(58));
                flashButton.setTranslationY(frame.top + dp(6));
                levelButton.setTranslationX(frame.exactCenterX() - dp(24));
                levelButton.setTranslationY(frame.top + dp(6));
                positionScreenFlashButton();
                positionCameraSwitchButton();
                adjustmentButton.setTranslationX(frame.right - adjustmentButton.getLayoutParams().width - dp(6));
                adjustmentButton.setTranslationY(frame.bottom - dp(54));
                lapseLookButton.setTranslationX(frame.left + dp(60));
                lapseLookButton.setTranslationY(frame.bottom - dp(54));
                microphoneButton.setTranslationX(frame.exactCenterX() - dp(24));
                microphoneButton.setTranslationY(frame.bottom - dp(54));
                focusZoomButton.setTranslationX(frame.left + dp(60));
                focusZoomButton.setTranslationY(frame.bottom - dp(54));
                manualFocusPanel.setTranslationX(frame.exactCenterX() - dp(115));
                manualFocusPanel.setTranslationY(frame.bottom - dp(101));
                framingGrid.invalidate();
            }
        });

        lensChip = chip("", this::chooseLens);
        lensChip.setOnLongClickListener(view -> {
            if (!canZoom()) return false;
            showZoomPanel();
            scheduleZoomHide(2800);
            return true;
        });
        formatChip = chip("", this::chooseFormat);
        qualityChip = chip("", this::chooseQuality);
        fpsChip = chip("", this::chooseFps);
        profileChip = iconButton(this::chooseVideoProfile, landscape ? 9 : 12);
        captionChip = chip("", this::editPolaroidCaption);
        fileChip = chip("", this::choosePhotoFile);
        colorButton = iconButton(this::openColor, 11);
        colorButton.setImageResource(R.drawable.ic_palette);
        LinearLayout landscapeRail = landscape ? column() : null;
        if (landscapeRail != null) {
            for (TextView setting : new TextView[]{lensChip, formatChip, qualityChip, fpsChip, fileChip}) {
                setting.setTextSize(12);
                LinearLayout.LayoutParams item = new LinearLayout.LayoutParams(-1, dp(39));
                item.bottomMargin = dp(4);
                landscapeRail.addView(setting, item);
            }
            formatChip.setPadding(dp(5), 0, dp(5), 0);
            qualityChip.setPadding(dp(5), 0, dp(5), 0);
            LinearLayout.LayoutParams profileParams = new LinearLayout.LayoutParams(dp(44), dp(40));
            profileParams.gravity = Gravity.CENTER_HORIZONTAL;
            profileParams.bottomMargin = dp(4);
            landscapeRail.addView(profileChip, profileParams);
            captionChip.setTextSize(11);
            landscapeRail.addView(captionChip, new LinearLayout.LayoutParams(-1, dp(39)));
            landscapeRail.addView(framingTools);
            controls.addView(landscapeRail, edge(dp(92), -2, Gravity.START | Gravity.CENTER_VERTICAL, 0));
        } else {
            LinearLayout settings = row();
            settings.addView(lensChip, new LinearLayout.LayoutParams(0, dp(48), .9f));
            settings.addView(formatChip, controlWidth(1.55f));
            settings.addView(fpsChip, controlWidth(1.15f));
            settings.addView(fileChip, controlWidth(1.15f));
            FrameLayout.LayoutParams settingsParams = edge(-1, -2, Gravity.TOP | Gravity.START, 12);
            settingsParams.rightMargin = dp(76);
            controls.addView(settings, settingsParams);
            captionChip.setMaxWidth(dp(184));
            LinearLayout videoSettings = row();
            videoSettings.addView(profileChip, new LinearLayout.LayoutParams(dp(48), dp(46)));
            LinearLayout.LayoutParams qualityParams = new LinearLayout.LayoutParams(dp(132), dp(46));
            qualityParams.setMarginStart(dp(6));
            videoSettings.addView(qualityChip, qualityParams);
            controls.addView(videoSettings, edge(-2, dp(46), Gravity.TOP | Gravity.START, 64));
            controls.addView(captionChip, edge(-2, dp(46), Gravity.TOP | Gravity.START, 64));
            controls.addView(framingTools, edge(-2, -2, Gravity.BOTTOM | Gravity.START, 78));
        }
        int cornerSize = dp(landscape ? 42 : 48);
        FrameLayout.LayoutParams colorParams = edge(cornerSize, cornerSize, Gravity.TOP | Gravity.END, 16);
        colorParams.rightMargin = dp(landscape ? 18 : 22);
        controls.addView(colorButton, colorParams);

        recordDot = new View(this);
        recordDot.setBackground(glass(12, RED, RED, Color.TRANSPARENT));
        recordDot.setContentDescription(getString(R.string.recording_active));
        recordDot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        recordDot.setVisibility(View.GONE);
        preview.addView(recordDot, new FrameLayout.LayoutParams(dp(11), dp(11), Gravity.TOP | Gravity.LEFT));
        recordTimer = label("", 13, INK, true);
        recordTimer.setGravity(Gravity.CENTER);
        recordTimer.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        recordTimer.setBackground(luminousSurface(13, 0xd12b3947, 0xe018202a, GLASS_EDGE));
        recordTimer.setVisibility(View.GONE);
        preview.addView(recordTimer, new FrameLayout.LayoutParams(dp(74), dp(30), Gravity.TOP | Gravity.LEFT));
        stopButton = chip("■", () -> {});
        stopButton.setContentDescription(getString(R.string.stop_recording));
        stopButton.setTextColor(RED);
        stopButton.setOnClickListener(view -> {
            if (recordingStop != null) {
                recordingStop.set(true);
                stopButton.setEnabled(false);
                if (isStarLapse()) {
                    stopButton.setText("…");
                    stopButton.setContentDescription(getString(R.string.finishing_exposure));
                }
            }
        });
        stopButton.setVisibility(View.GONE);
        FrameLayout.LayoutParams stopParams = edge(dp(56), dp(56), landscape
            ? Gravity.RIGHT | Gravity.CENTER_VERTICAL : Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, landscape ? 0 : 56);
        stopParams.rightMargin = dp(landscape ? 25 : 10);
        preview.addView(stopButton, stopParams);
        cameraSwitchButton = viewfinderIcon(preview, () -> {});
        cameraSwitchButton.setOnClickListener(view -> switchCamera());

        reviewChip = iconButton(this::openGallery, landscape ? 9 : 12);
        reviewChip.setClipToOutline(true);
        FrameLayout.LayoutParams albumParams = edge(cornerSize, cornerSize,
            (landscape ? Gravity.TOP : Gravity.BOTTOM) | Gravity.END, landscape ? 16 : 78);
        albumParams.rightMargin = dp(landscape ? 65 : 22);
        controls.addView(reviewChip, albumParams);
        showGalleryPreview();

        LinearLayout modes = landscape ? column() : row();
        modes.setPadding(dp(4), dp(3), dp(4), dp(3));
        modes.setBackground(luminousSurface(28, 0xe0192430, 0xf0121722, GLASS_EDGE));
        photoMode = chip(getString(R.string.mode_photo), () -> setMode(false));
        videoMode = chip(getString(R.string.mode_video), () -> setMode(true));
        if (landscape) {
            for (TextView mode : new TextView[]{photoMode, videoMode}) {
                mode.setTextSize(12);
                mode.setPadding(dp(4), 0, dp(4), 0);
            }
        }
        for (TextView mode : new TextView[]{photoMode, videoMode}) {
            modes.addView(mode, landscape ? new LinearLayout.LayoutParams(-1, dp(44))
                : new LinearLayout.LayoutParams(0, dp(48), 1));
        }
        FrameLayout.LayoutParams modeParams = edge(dp(landscape ? 72 : 166), -2, Gravity.BOTTOM
            | (landscape ? Gravity.END : Gravity.CENTER_HORIZONTAL), landscape ? 18 : 12);
        if (landscape) modeParams.rightMargin = dp(20);
        controls.addView(modes, modeParams);

        FrameLayout captureButton = new FrameLayout(this);
        captureButton.setBackground(ripple(luminousSurface(38, 0xd33a4a59, 0xe3161f2c, 0xa6c6e4ff), 38));
        addPressFeedback(captureButton);
        captureButton.setClickable(true);
        captureButton.setFocusable(true);
        captureButton.setOnClickListener(v -> pressShutter());
        captureButton.setOnLongClickListener(view -> {
            if (!shutterHeld || recordingMode || pendingShutter != null || busy || reviewing
                    || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return false;
            capture(true);
            return burstStop != null;
        });
        captureButton.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> shutterHeld = true;
                case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    shutterHeld = false;
                    if (burstStop != null) burstStop.set(true);
                }
            }
            return false;
        });
        shutterCenter = new View(this);
        int shutterCoreSize = dp(landscape ? 44 : 52);
        captureButton.addView(shutterCenter, new FrameLayout.LayoutParams(shutterCoreSize, shutterCoreSize, Gravity.CENTER));
        int shutterGravity = landscape ? Gravity.RIGHT | Gravity.CENTER_VERTICAL : Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        int shutterSize = dp(landscape ? 61 : 72);
        FrameLayout.LayoutParams shutterParams = edge(shutterSize, shutterSize, shutterGravity, landscape ? 0 : 66);
        if (landscape) shutterParams.rightMargin = dp(25);
        controls.addView(captureButton, shutterParams);
        shutter = captureButton;

        status = label("", landscape ? 11 : 13, MUTED, false);
        status.setGravity(Gravity.CENTER);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        if (landscapeRail != null) {
            status.setPadding(dp(2), dp(8), dp(2), dp(2));
            status.setMaxLines(4);
            status.setEllipsize(android.text.TextUtils.TruncateAt.END);
            landscapeRail.addView(status, new LinearLayout.LayoutParams(-1, -2));
        } else {
            status.setMaxWidth(dp(280));
            status.setBackground(luminousSurface(16, 0xb22a3644, 0xce131d29, 0x317b96ae));
            status.setPadding(dp(12), dp(5), dp(12), dp(5));
            status.setMaxLines(2);
            controls.addView(status, edge(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 144));
        }
        addZoomPanel(preview);
        updateControls();
        showModeHint();
        var colorSettings = getSharedPreferences("color", MODE_PRIVATE);
        intensity = colorSettings.getInt("intensity", 100);
        saveLut = colorSettings.getBoolean("saveLut", false);
        monochrome = colorSettings.getBoolean("monochrome", false);
        updateMonochromePreview();
        String savedLut = colorSettings.getString("uri", null);
        savedLutName = colorSettings.getString("lutName", null);
        colorSettings.edit().remove("uri").apply();
        if (lutFile().isFile()) loadLut(null, false);
        else if (savedLut != null) loadLut(Uri.parse(savedLut), false);
        worker.execute(() -> CaptureFiles.clearStale(this));
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private ImageButton iconButton(Runnable action, int inset) {
        ImageButton button = new ImageButton(this);
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        button.setBackground(controlSurface(false));
        button.setPadding(dp(inset), dp(inset), dp(inset), dp(inset));
        addPressFeedback(button);
        button.setOnClickListener(view -> { if (!busy) action.run(); });
        return button;
    }

    private TextView viewfinderButton(FrameLayout preview, int width, Runnable click) {
        TextView button = label("", 16, ACCENT, true);
        button.setGravity(Gravity.CENTER);
        button.setIncludeFontPadding(false);
        button.setLineSpacing(0, .85f);
        button.setOnClickListener(view -> { if (!busy) click.run(); });
        button.setVisibility(View.GONE);
        preview.addView(button, new FrameLayout.LayoutParams(dp(width), dp(48), Gravity.TOP | Gravity.LEFT));
        return button;
    }

    private ImageButton viewfinderIcon(FrameLayout preview, Runnable click) {
        ImageButton button = new ImageButton(this);
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setOnClickListener(view -> { if (!busy) click.run(); });
        button.setVisibility(View.GONE);
        preview.addView(button, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.LEFT));
        return button;
    }

    private void styleViewfinderIcon(ImageButton button, int icon, boolean active) {
        int visualState = icon * 2 + (active ? 1 : 0);
        if (Integer.valueOf(visualState).equals(button.getTag())) return;
        button.setTag(visualState);
        button.setImageResource(icon);
        button.setImageTintList(ColorStateList.valueOf(active ? VIEWFINDER_ACTIVE : INK));
        button.setSelected(active);
        button.setBackground(viewfinderSurface(active));
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
    }

    private void styleViewfinderLabel(TextView button, boolean active) {
        button.setTextColor(active ? VIEWFINDER_ACTIVE : INK);
        button.setShadowLayer(dp(active ? 6 : 4), 0, 0, active ? 0xff169fec : Color.BLACK);
        if (!Boolean.valueOf(active).equals(button.getTag())) {
            button.setBackground(viewfinderSurface(active));
            button.setTag(active);
        }
        button.setSelected(active);
    }

    private FrameLayout.LayoutParams edge(int width, int height, int gravity, int inset) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height, gravity);
        params.setMargins(dp(10), dp(inset), dp(10), dp(inset));
        return params;
    }

    private LinearLayout.LayoutParams controlWidth(float weight) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), weight);
        params.setMarginStart(dp(6));
        return params;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private GradientDrawable glass(int radius, int top, int bottom, int border) {
        GradientDrawable shape = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{top, bottom});
        shape.setCornerRadius(dp(radius));
        if (border != Color.TRANSPARENT) shape.setStroke(dp(1), border);
        return shape;
    }

    private Drawable luminousSurface(int radius, int top, int bottom, int edge) {
        GradientDrawable sheen = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0x2ee2f3ff, 0x08c6e5ff, Color.TRANSPARENT});
        sheen.setCornerRadius(dp(radius - 1));
        return new LayerDrawable(new Drawable[]{glass(radius, top, bottom, edge), new InsetDrawable(sheen, dp(1))});
    }

    private RippleDrawable ripple(Drawable shape, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x36bfe8ff), shape,
            glass(radius, Color.WHITE, Color.WHITE, Color.TRANSPARENT));
    }

    private Drawable controlSurface(boolean active) {
        return ripple(luminousSurface(23, active ? ACTIVE_LIGHT : GLASS_LIGHT,
            active ? ACTIVE_DARK : GLASS_DARK, active ? ACTIVE_EDGE : GLASS_EDGE), 23);
    }

    private Drawable viewfinderSurface(boolean active) {
        Drawable lens = glass(20, active ? 0x92385570 : 0x60222c38,
            active ? 0x60142435 : 0x52101720, active ? 0x7785ccfc : 0x26798b9e);
        return new InsetDrawable(ripple(lens, 20), dp(4));
    }

    private void addPressFeedback(View view) {
        StateListAnimator animator = new StateListAnimator();
        animator.addState(new int[]{android.R.attr.state_pressed}, scaleFeedback(view, .96f, 85));
        animator.addState(new int[0], scaleFeedback(view, 1f, 160));
        view.setStateListAnimator(animator);
    }

    private ObjectAnimator scaleFeedback(View view, float scale, int duration) {
        return ObjectAnimator.ofPropertyValuesHolder(view, PropertyValuesHolder.ofFloat(View.SCALE_X, scale),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, scale)).setDuration(duration);
    }

    private TextView chip(String text, Runnable action) {
        TextView view = label(text, 14, INK, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(8), 0, dp(8), 0);
        view.setSingleLine();
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        view.setBackground(controlSurface(false));
        addPressFeedback(view);
        view.setFocusable(true);
        view.setOnClickListener(v -> { if (!busy) action.run(); });
        return view;
    }

    private void setMode(boolean video) {
        hideZoomPanel();
        cancelSelfTimer();
        focusMagnified = false;
        recordingMode = video;
        updateControls();
        showModeHint();
    }

    private void updateControls() {
        updateZoomLabel();
        boolean stars = isStarLapse();
        VideoOptions captureSize = stars ? VideoOptions.FULL_HD : videoSize;
        String format = recordingMode ? captureSize.format.label(this) : switch (effectivePhotoFormat()) {
            case PHOTO_WIDE -> landscape ? getString(R.string.format_wide) : getString(R.string.photo_wide_resolution, 9.4);
            case PHOTO_XPAN -> getString(R.string.format_xpan);
            case PHOTO_POLAROID -> getString(R.string.format_polaroids);
            default -> landscape ? getString(R.string.format_four_thirds) : getString(R.string.photo_full_resolution, 12.6);
        };
        boolean canFormat = recordingMode ? !stars && availableVideoFormats().size() > 1 : photoFile != FILE_RAW;
        formatChip.setText(!recordingMode && photoFile == FILE_RAW ? "4:3" : format + (canFormat ? " ▾" : ""));
        formatChip.setEnabled(canFormat);
        formatChip.setContentDescription(getString(R.string.format_description, format));
        VideoOptions outputSize = videoProfile == VideoProfile.HORIZON ? VideoOptions.FULL_HD : captureSize;
        boolean canQuality = recordingMode && !stars && sizesForFormat(captureSize.format).size() > 1;
        qualityChip.setText(outputSize.label + (canQuality ? " ▾" : ""));
        qualityChip.setContentDescription(videoProfile == VideoProfile.HORIZON
            ? getString(R.string.quality_capture_description, outputSize.description, captureSize.description)
            : getString(R.string.quality_description, outputSize.description));
        qualityChip.setEnabled(canQuality);
        qualityChip.setVisibility(recordingMode ? View.VISIBLE : View.GONE);
        fileChip.setText((photoFile == FILE_BOTH ? "RAW+JPG" : PHOTO_FILES[photoFile]) + " ▾");
        fileChip.setContentDescription(getString(R.string.photo_file_description, PHOTO_FILES[photoFile]));
        fileChip.setVisibility(!recordingMode && rawSize != null ? View.VISIBLE : View.GONE);
        boolean timeLapse = recordingMode && videoProfile == VideoProfile.TIMELAPSE;
        String interval = stars ? starPreset.intervalSeconds() + " s" : lapseLabel(lapseIntervalMs);
        fpsChip.setText(timeLapse ? interval + (stars ? "" : " ▾") : fps + " fps ▾");
        fpsChip.setContentDescription(timeLapse ? getString(R.string.lapse_interval_description, interval)
            : getString(R.string.frame_rate_description, fps));
        fpsChip.setEnabled(!stars);
        fpsChip.setVisibility(recordingMode ? View.VISIBLE : View.GONE);
        fpsChip.setPadding(dp(4), 0, dp(4), 0);
        profileChip.setImageResource(videoProfile.icon);
        styleIconControl(profileChip, videoProfile != VideoProfile.STANDARD);
        profileChip.setContentDescription(getString(R.string.profile_description, videoProfile.label(this)));
        profileChip.setVisibility(recordingMode ? View.VISIBLE : View.GONE);
        captionChip.setText(polaroidCaption.isEmpty() ? (landscape ? getString(R.string.caption) : getString(R.string.add_caption)) : polaroidCaption);
        captionChip.setContentDescription(getString(R.string.edit_polaroid_caption));
        captionChip.setVisibility(isPolaroid() ? View.VISIBLE : View.GONE);
        styleMode(photoMode, !recordingMode);
        styleMode(videoMode, recordingMode);
        shutter.setContentDescription(recordingMode ? getString(R.string.record_video)
            : pendingShutter != null ? getString(R.string.cancel_timer) : getString(R.string.take_photo));
        shutterCenter.setBackground(luminousSurface(30, recordingMode ? 0xffff8a98 : 0xfff9fdff,
            recordingMode ? 0xffdc3b58 : 0xffbaccdd, recordingMode ? 0x99ffc2c9 : 0xffeef7ff));
        shutter.setEnabled(burstStop != null || !busy && (!recordingMode || !videoRates.isEmpty()));
        shutter.setAlpha(busy ? .55f : 1f);
        styleIconControl(colorButton, lut != null || monochrome);
        String color = lut == null ? getString(R.string.color)
            : getString(saveLut ? R.string.color_lut_export : R.string.color_lut, intensity);
        colorButton.setContentDescription(monochrome ? getString(R.string.color_monochrome, color) : color);
        refreshScreenFlash();
        updateFraming();
        updateAutofocusButton();
        updateManualFocusUi();
        updateShootingTools();
        refreshCameraSwitchButton();
    }

    private void updateFraming() {
        double aspect = 4.0 / 3;
        if (recordingMode) aspect = isStarLapse() ? VideoOptions.FULL_HD.aspect() : videoSize.aspect();
        else if (photoFile == FILE_RAW && rawSize != null) aspect = (double) rawSize.getWidth() / rawSize.getHeight();
        else if (photoIndex == PHOTO_WIDE) aspect = 16.0 / 9;
        else if (photoIndex == PHOTO_XPAN) aspect = 65.0 / 24;
        livePreview.setFraming(aspect, manualRotation, isPolaroid());
        updateViewfinderArea();
        gridChip.setContentDescription(gridEnabled ? getString(R.string.grid_off) : getString(R.string.grid_on));
        rotateChip.setContentDescription(getString(R.string.rotate_description, manualRotation));
        styleIconControl(gridChip, gridEnabled);
        styleIconControl(rotateChip, manualRotation != 0);
        gridChip.setVisibility(reviewing ? View.GONE : View.VISIBLE);
        rotateChip.setVisibility(reviewing ? View.GONE : View.VISIBLE);
        updateGrid();
    }

    private void updateViewfinderArea() {
        int lightInset = screenFlashOn ? dp(14) : 0;
        if (cleanRecording) {
            livePreview.setViewfinderInsets(lightInset, lightInset, lightInset, lightInset);
            return;
        }
        int left = controls.getPaddingLeft() + dp(landscape ? LANDSCAPE_LEFT_RAIL : 0);
        int right = controls.getPaddingRight() + dp(landscape ? LANDSCAPE_RIGHT_RAIL : 0);
        int top = controls.getPaddingTop() + dp(landscape ? 0 : (recordingMode || isPolaroid() ? 116 : 66));
        int bottom = controls.getPaddingBottom() + dp(landscape ? 0 : 190);
        livePreview.setViewfinderInsets(Math.max(left, lightInset), Math.max(top, lightInset),
            Math.max(right, lightInset), Math.max(bottom, lightInset));
        image.setPadding(left, top, right, bottom);
    }

    private void updateGrid() {
        boolean live = !reviewing && !capturing && emptyPreview.getVisibility() == View.GONE;
        framingGrid.setVisibility((gridEnabled || isPolaroid()) && live ? View.VISIBLE : View.GONE);
    }

    private int effectivePhotoFormat() { return photoFile == FILE_RAW ? 0 : photoIndex; }

    private boolean isPolaroid() { return !recordingMode && effectivePhotoFormat() == PHOTO_POLAROID; }

    private void editPolaroidCaption() {
        EditText editor = new EditText(this);
        editor.setSingleLine(true);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        editor.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        editor.setFilters(new InputFilter[]{new InputFilter.LengthFilter(64)});
        editor.setHint(getString(R.string.optional_text));
        editor.setText(polaroidCaption);
        editor.setSelection(editor.length());
        LinearLayout field = column();
        field.setPadding(dp(24), dp(4), dp(24), dp(4));
        editor.setTextColor(INK);
        editor.setHintTextColor(MUTED);
        editor.setBackground(controlSurface(false));
        editor.setMinHeight(dp(48));
        editor.setPadding(dp(14), dp(8), dp(14), dp(8));
        field.addView(editor, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(getString(R.string.polaroid_caption))
            .setView(field)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.apply), (window, button) -> {
                polaroidCaption = editor.getText().toString().trim();
                updateControls();
                framingGrid.invalidate();
            }).create();
        editor.requestFocus();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        editor.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            return true;
        });
        showGlassDialog(dialog);
    }

    private void focusAt(float x, float y) {
        if (busy || reviewing || isStarLapse() || !framing.contains((int) x, (int) y)) return;
        if (livePreview.focusAt(x, y, focused -> {
            showFocusMarker(focused ? 0xff9de4b6 : 0xffffbc72, focused ? getString(R.string.focus_acquired) : getString(R.string.focus_failed));
            focusMarker.postDelayed(hideFocusMarker, 900);
        })) {
            focusMarker.removeCallbacks(hideFocusMarker);
            int margin = dp(27);
            focusMarker.setTranslationX(Math.max(framing.left, Math.min(framing.right - 2 * margin, x - margin)));
            focusMarker.setTranslationY(Math.max(framing.top, Math.min(framing.bottom - 2 * margin, y - margin)));
            showFocusMarker(0xffffdd90, getString(R.string.focusing));
            updateAutofocusButton();
        }
    }

    private final Runnable hideFocusMarker = () -> focusMarker.setVisibility(View.GONE);

    private void resetFocus() {
        if (manualFocus != null && supportsManualFocus()) {
            toggleManualFocus();
            return;
        }
        if (!livePreview.resetFocus()) return;
        clearFocusUi();
        updateAutofocusButton();
        updateShootingTools();
    }

    private void clearFocusUi() {
        focusMarker.removeCallbacks(hideFocusMarker);
        focusMarker.setVisibility(View.GONE);
        autofocusButton.setVisibility(View.GONE);
        flashButton.setVisibility(View.GONE);
        adjustmentButton.setVisibility(View.GONE);
        lapseLookButton.setVisibility(View.GONE);
        microphoneButton.setVisibility(View.GONE);
        focusZoomButton.setVisibility(View.GONE);
        manualFocusPanel.setVisibility(View.GONE);
    }

    private boolean isStarLapse() {
        return recordingMode && videoProfile == VideoProfile.TIMELAPSE && starLapse && starPreset != null;
    }

    private boolean supportsVideoAudio() {
        return recordingMode && !videoRates.isEmpty() && fps < 120
            && !videoProfile.isSlowMotion() && videoProfile != VideoProfile.TIMELAPSE;
    }

    private VideoMicrophone selectedMicrophone() {
        return microphones.stream().filter(option -> option.key().equals(microphoneKey)).findFirst().orElse(null);
    }

    private void refreshMicrophones() {
        microphones = VideoMicrophone.available(this, audioManager);
        updateMicrophoneIcon();
    }

    private void updateMicrophoneIcon() {
        VideoMicrophone selected = selectedMicrophone();
        boolean supported = supportsVideoAudio();
        VideoMicrophone.Kind kind = supported && selected != null ? selected.kind() : VideoMicrophone.Kind.OFF;
        styleViewfinderIcon(microphoneButton, kind.icon, kind != VideoMicrophone.Kind.OFF);
        String description = supported
            ? (selected == null ? getString(R.string.input_disconnected) : selected.label())
            : getString(R.string.profile_no_audio);
        microphoneButton.setEnabled(supported);
        microphoneButton.setContentDescription(getString(R.string.microphone_description, description));
    }

    private void chooseMicrophone() {
        refreshMicrophones();
        List<VideoMicrophone> options = microphones;
        String[] labels = options.stream().map(VideoMicrophone::label).toArray(String[]::new);
        chooseFrom(microphoneButton, labels, options.indexOf(selectedMicrophone()), which -> {
            microphoneKey = options.get(which).key();
            updateMicrophoneIcon();
            showModeHint();
        });
    }

    private boolean canAdjust() { return currentCamera != null && !isStarLapse() && (!recordingMode || fps < 120); }

    private boolean canSetIso() {
        return canAdjust() && (!recordingMode || videoProfile != VideoProfile.ACTION)
            && ActionExposure.supported(currentCamera);
    }

    private boolean canSetContrast() {
        return canAdjust() && (recordingMode || photoFile != FILE_RAW)
            && CameraAdjustments.supportsContrast(currentCamera);
    }

    private boolean canSetShutter() {
        return canSetIso() && (!recordingMode || videoProfile == VideoProfile.STANDARD || videoProfile == VideoProfile.HORIZON);
    }

    private int exposureFps() { return recordingMode ? fps : 0; }

    private boolean canSetWhiteBalance() {
        return recordingMode && canAdjust() && videoProfile != VideoProfile.TIMELAPSE;
    }

    private float maxFocusDistance() {
        if (currentCamera == null) return 0;
        Float distance = currentCamera.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        return distance == null ? 0f : distance;
    }

    private boolean supportsManualFocus() {
        if (recordingMode || maxFocusDistance() <= 0) return false;
        int[] modes = currentCamera.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        return modes != null && Arrays.stream(modes).anyMatch(mode ->
            mode == android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_OFF);
    }

    private void updateManualFocusUi() {
        boolean manual = supportsManualFocus() && manualFocus != null;
        livePreview.setManualFocus(CAMERA_IDS[lensIndex], manual ? manualFocus : null);
        boolean visible = manual && resumed && !reviewing && !capturing && emptyPreview.getVisibility() == View.GONE;
        livePreview.setFocusMagnified(visible && focusMagnified);
        manualFocusPanel.setVisibility(visible ? View.VISIBLE : View.GONE);
        focusZoomButton.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) {
            focusSlider.setProgress(Math.round(100 * manualFocus / maxFocusDistance()));
            styleViewfinderLabel(focusZoomButton, focusMagnified);
        }
    }

    private void toggleManualFocus() {
        if (!supportsManualFocus()) return;
        Float measured = livePreview.measuredFocus();
        manualFocus = manualFocus == null ? Math.min(maxFocusDistance(), measured == null ? 0f : measured) : null;
        focusMagnified = manualFocus != null;
        updateManualFocusUi();
        updateAutofocusButton();
    }

    private WhiteBalance currentWhiteBalance() {
        return canSetWhiteBalance() && WhiteBalance.available(currentCamera).contains(whiteBalance)
            ? whiteBalance : WhiteBalance.AUTO;
    }

    private CameraAdjustments currentAdjustments() {
        if (!canAdjust()) return CameraAdjustments.DEFAULT;
        int activeFlash = CameraAdjustments.supportsFlash(currentCamera)
            ? (recordingMode ? (videoTorch ? CameraAdjustments.TORCH : CameraAdjustments.OFF) : photoFlash)
            : CameraAdjustments.OFF;
        Range<Integer> evRange = currentCamera.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        int compensation = evRange == null ? 0 : evRange.clamp(ev);
        int activeIso = canSetIso() && (recordingMode || activeFlash == CameraAdjustments.OFF) ? iso : 0;
        long shutter = manualShutter;
        if (canSetShutter() && selectedShutter > 0) shutter = selectedShutter;
        return new CameraAdjustments(compensation, canSetContrast() ? contrast : 0, activeIso,
            activeIso == 0 ? 0 : shutter, activeFlash);
    }

    private void updateShootingTools() {
        CameraAdjustments settings = currentAdjustments();
        long cadenceLimit = 1_000_000_000L / (recordingMode ? Math.max(1, fps) : 15);
        livePreview.setAdjustments(CAMERA_IDS[lensIndex], settings.limitShutter(cadenceLimit));
        livePreview.setWhiteBalance(CAMERA_IDS[lensIndex], currentWhiteBalance());
        updateManualFocusUi();
        updateZebra();
        updateLevel();
        boolean live = resumed && !reviewing && !capturing && !cleanRecording && emptyPreview.getVisibility() == View.GONE;
        boolean zoomCoversTools = !landscape && zoomPanel != null && zoomPanel.getVisibility() == View.VISIBLE;
        microphoneButton.setVisibility(live && recordingMode && !zoomCoversTools ? View.VISIBLE : View.GONE);
        if (live && recordingMode) updateMicrophoneIcon();
        boolean toggleVisible = live && recordingMode && videoProfile == VideoProfile.TIMELAPSE && starPreset != null;
        lapseLookButton.setVisibility(toggleVisible ? View.VISIBLE : View.GONE);
        if (toggleVisible) {
            styleViewfinderIcon(lapseLookButton,
                isStarLapse() ? R.drawable.ic_lapse_stars : R.drawable.ic_lapse_day, true);
            lapseLookButton.setContentDescription(isStarLapse() ? getString(R.string.star_switch_normal)
                : getString(R.string.normal_switch_star));
        }
        boolean visible = live && canAdjust();
        flashButton.setVisibility(visible && lensIndex != 3 && CameraAdjustments.supportsFlash(currentCamera)
            ? View.VISIBLE : View.GONE);
        adjustmentButton.setVisibility(visible && !zoomCoversTools ? View.VISIBLE : View.GONE);
        if (!visible) return;
        if ((adjustment == Adjustment.ISO && !canSetIso()) || (adjustment == Adjustment.CONTRAST && !canSetContrast())) {
            adjustment = Adjustment.BRIGHTNESS;
        }

        String flash = switch (settings.flash()) {
            case CameraAdjustments.AUTO -> "AUTO";
            case CameraAdjustments.ON, CameraAdjustments.TORCH -> "ON";
            default -> "OFF";
        };
        labelWithState(flashButton, "⚡︎", flash);
        styleViewfinderLabel(flashButton, settings.flash() != CameraAdjustments.OFF);
        flashButton.setContentDescription(getString(recordingMode ? R.string.torch_description : R.string.flash_description, flash));

        android.util.Rational evStep = currentCamera.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        float exposure = evStep == null ? 0 : settings.ev() * evStep.floatValue();
        String reading = switch (adjustment) {
            case BRIGHTNESS -> getString(R.string.ev_reading, exposure);
            case CONTRAST -> getString(R.string.contrast_reading, settings.contrast());
            case ISO -> settings.iso() == 0 ? "ISO Auto" : "ISO " + settings.iso();
        };
        boolean active = switch (adjustment) {
            case BRIGHTNESS -> settings.ev() != 0;
            case CONTRAST -> settings.contrast() != 0;
            case ISO -> settings.iso() != 0;
        };
        styleViewfinderLabel(adjustmentButton, active);
        adjustmentButton.setPaddingRelative(dp(12), 0, dp(12), 0);
        Drawable tools = getDrawable(R.drawable.ic_adjustments);
        tools.setTint(active ? VIEWFINDER_ACTIVE : INK);
        tools.setBounds(0, 0, dp(15), dp(15));
        SpannableString summary = new SpannableString(reading + "\u2002\uFFFC\u2009▾");
        int iconPosition = reading.length() + 1;
        summary.setSpan(new ImageSpan(tools, ImageSpan.ALIGN_CENTER), iconPosition, iconPosition + 1,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        adjustmentButton.setText(summary);
        adjustmentButton.setContentDescription(getString(R.string.adjustments_description, reading));
    }

    private void cycleFlash() {
        if (recordingMode) {
            videoTorch = !videoTorch;
        } else {
            photoFlash = (photoFlash + 1) % 3;
            if (photoFlash != CameraAdjustments.OFF) {
                clearManualIso();
                bracketEnabled = false;
            }
        }
        updateShootingTools();
    }

    private record MenuReset(String setting, Runnable action) {}
    private record SettingRow(String label, Runnable select, MenuReset reset) {}

    private void chooseAdjustment() {
        List<SettingRow> rows = new java.util.ArrayList<>();
        int selected = adjustment == Adjustment.BRIGHTNESS ? 0 : -1;
        String exposure = getString(R.string.ev_adjustment);
        var evStep = currentCamera.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        String exposureValue = ev == 0 || evStep == null ? exposure
            : exposure + " · " + getString(R.string.ev_reading, currentAdjustments().ev() * evStep.floatValue());
        rows.add(new SettingRow(exposureValue, () -> selectAdjustment(Adjustment.BRIGHTNESS),
            ev == 0 ? null : new MenuReset(exposure, () -> ev = 0)));
        if (canSetContrast()) {
            if (adjustment == Adjustment.CONTRAST) selected = rows.size();
            String label = getString(R.string.contrast);
            rows.add(new SettingRow(contrast == 0 ? label : getString(R.string.contrast_reading, contrast),
                () -> selectAdjustment(Adjustment.CONTRAST),
                contrast == 0 ? null : new MenuReset(label, () -> contrast = 0)));
        }
        if (canSetIso()) {
            if (adjustment == Adjustment.ISO) selected = rows.size();
            rows.add(new SettingRow(manualIsoSelected ? "ISO " + iso : "ISO", () -> selectAdjustment(Adjustment.ISO),
                manualIsoSelected ? new MenuReset("ISO", this::resetIso) : null));
        }
        if (canSetShutter()) {
            String label = getString(R.string.shutter_speed);
            String value = shutterChoices().stream().filter(choice -> choice.nanos() == selectedShutter)
                .findFirst().map(ShutterSpeed::label).orElse(formatDecimal(selectedShutter / 1_000_000_000d) + " s");
            rows.add(new SettingRow(selectedShutter == 0 ? label : getString(R.string.shutter_choice, value),
                this::chooseShutter, selectedShutter == 0 ? null : new MenuReset(label, this::resetShutter)));
        }
        if (!recordingMode) {
            String label = getString(R.string.self_timer);
            rows.add(new SettingRow(selfTimerSeconds == 0 ? label : getString(R.string.timer_count, selfTimerSeconds),
                this::chooseSelfTimer, selfTimerSeconds == 0 ? null : new MenuReset(label, () -> setSelfTimer(0))));
        }
        if (canSetWhiteBalance()) {
            String label = getString(R.string.white_balance);
            rows.add(new SettingRow(whiteBalance == WhiteBalance.AUTO ? label
                : getString(R.string.wb_choice, whiteBalance.label(this)), this::chooseWhiteBalance,
                whiteBalance == WhiteBalance.AUTO ? null : new MenuReset(label, () -> whiteBalance = WhiteBalance.AUTO)));
        }
        if (supportsManualFocus()) {
            String label = getString(R.string.manual_focus);
            rows.add(new SettingRow(manualFocus == null ? label : getString(R.string.restore_af),
                this::toggleManualFocus, manualFocus == null ? null : new MenuReset(label, this::toggleManualFocus)));
        }
        if (canSetWhiteBalance()) {
            String label = getString(R.string.highlight_zebra);
            rows.add(new SettingRow(zebraEnabled ? getString(R.string.zebra_off) : label,
                () -> { zebraEnabled = !zebraEnabled; updateZebra(); },
                zebraEnabled ? new MenuReset(label, () -> zebraEnabled = false) : null));
        }
        if (canBracket()) {
            String label = getString(R.string.bracketing_option);
            rows.add(new SettingRow(bracketEnabled ? getString(R.string.bracketing_off) : label,
                this::toggleBracketing, bracketEnabled ? new MenuReset(label, this::toggleBracketing) : null));
        }
        rows.add(new SettingRow(getString(R.string.reset_adjustments), () -> {
            ev = 0;
            contrast = 0;
            clearManualIso();
            updateShootingTools();
            showModeHint();
        }, null));
        String[] labels = rows.stream().map(SettingRow::label).toArray(String[]::new);
        MenuReset[] resets = rows.stream().map(SettingRow::reset).toArray(MenuReset[]::new);
        chooseFrom(adjustmentButton, labels, selected, which -> rows.get(which).select().run(), resets);
    }

    private void selectAdjustment(Adjustment option) {
        adjustment = option;
        updateShootingTools();
    }

    private void toggleBracketing() {
        bracketEnabled = !bracketEnabled;
        if (bracketEnabled) photoFlash = CameraAdjustments.OFF;
        updateShootingTools();
        status.setText(getString(bracketEnabled ? R.string.bracketing_option : R.string.bracketing_disabled));
        status.setVisibility(View.VISIBLE);
    }

    private void clearManualIso() {
        iso = 0;
        meteredIso = 0;
        manualIsoSelected = false;
        manualShutter = 0;
        selectedShutter = 0;
    }

    private void resetIso() {
        if (selectedShutter == 0) clearManualIso();
        else {
            iso = meteredIso;
            manualIsoSelected = false;
        }
    }

    private void resetShutter() {
        if (manualIsoSelected) selectedShutter = 0;
        else clearManualIso();
    }

    private List<ShutterSpeed> shutterChoices() {
        return ShutterSpeed.choices(currentCamera, exposureFps(), !recordingMode && lensIndex == 0);
    }

    private void chooseShutter() {
        List<ShutterSpeed> choices = shutterChoices();
        int selected = 0;
        for (int i = 1; i < choices.size(); i++) {
            if (choices.get(i).nanos() == selectedShutter) selected = i;
        }
        String[] labels = choices.stream().map(ShutterSpeed::label).toArray(String[]::new);
        chooseFrom(adjustmentButton, labels, selected, which -> {
            ShutterSpeed choice = choices.get(which);
            if (choice.nanos() == 0) {
                resetShutter();
            } else {
                if (!startManualExposure()) return;
                selectedShutter = choice.nanos();
                adjustment = Adjustment.ISO;
            }
            updateShootingTools();
            status.setText(getString(R.string.shutter_choice, choice.label()));
            status.setVisibility(View.VISIBLE);
        });
    }

    private void chooseSelfTimer() {
        int[] seconds = {0, 2, 10};
        chooseFrom(adjustmentButton, new String[]{getString(R.string.off), "2 s", "10 s"},
            java.util.Arrays.binarySearch(seconds, selfTimerSeconds), which -> setSelfTimer(seconds[which]));
    }

    private void setSelfTimer(int seconds) {
        if (seconds == 0) cancelSelfTimer();
        selfTimerSeconds = seconds;
        status.setText(seconds == 0 ? getString(R.string.timer_off) : getString(R.string.timer_count, seconds));
        status.setVisibility(View.VISIBLE);
    }

    private void chooseWhiteBalance() {
        List<WhiteBalance> choices = WhiteBalance.available(currentCamera);
        chooseFrom(adjustmentButton, choices.stream().map(choice -> choice.label(this)).toArray(String[]::new),
            choices.indexOf(currentWhiteBalance()), which -> {
                whiteBalance = choices.get(which);
                updateShootingTools();
                status.setText(getString(R.string.wb_choice, whiteBalance.label(this)));
                status.setVisibility(View.VISIBLE);
            });
    }

    private void updateZebra() {
        zebraOverlay.setRunning(resumed && !reviewing && zebraEnabled && canSetWhiteBalance()
            && emptyPreview.getVisibility() == View.GONE && (!capturing || cleanRecording));
    }

    private void updateLevel() {
        boolean visible = levelOverlay.supported() && resumed && !reviewing && !capturing && !cleanRecording
            && emptyPreview.getVisibility() == View.GONE;
        levelOverlay.setRunning(visible && levelEnabled);
        levelButton.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) {
            styleViewfinderIcon(levelButton, R.drawable.ic_level, levelEnabled);
            levelButton.setContentDescription(levelEnabled ? getString(R.string.level_off) : getString(R.string.level_on));
        }
    }

    private boolean canBracket() {
        return !recordingMode && photoFile == FILE_JPEG && currentCamera != null && ActionExposure.supported(currentCamera);
    }

    private boolean startManualExposure() {
        if (iso > 0) return true;
        ActionExposure measured = livePreview.exposure();
        if (measured == null) {
            status.setText(getString(R.string.metering_wait));
            status.setVisibility(View.VISIBLE);
            return false;
        }
        manualShutter = measured.timeNs();
        iso = measured.iso();
        meteredIso = iso;
        ev = 0;
        if (!recordingMode) photoFlash = CameraAdjustments.OFF;
        return true;
    }

    private void adjustBy(int steps) {
        if (busy || reviewing || capturing || adjustmentButton.getVisibility() != View.VISIBLE) return;
        switch (adjustment) {
            case BRIGHTNESS -> {
                Range<Integer> range = currentCamera.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
                if (range == null) return;
                clearManualIso();
                ev = range.clamp(ev + steps);
            }
            case CONTRAST -> contrast = Math.max(-5, Math.min(5, contrast + steps));
            case ISO -> {
                Range<Integer> range = currentCamera.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
                if (!startManualExposure()) return;
                double adjusted = iso * Math.pow(2, steps / 3.0);
                iso = range.clamp((int) Math.round(adjusted / 10) * 10);
                manualIsoSelected = iso != meteredIso;
                if (!manualIsoSelected && selectedShutter == 0) clearManualIso();
            }
        }
        updateShootingTools();
    }

    private void labelWithState(TextView button, String title, String state) {
        SpannableString text = new SpannableString(title + "\n" + state);
        text.setSpan(new RelativeSizeSpan(.58f), title.length() + 1, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        button.setText(text);
    }

    private void updateAutofocusButton() {
        boolean visible = resumed && !reviewing && !capturing && !cleanRecording && !isStarLapse()
            && emptyPreview.getVisibility() == View.GONE && livePreview.hasFocusControl();
        autofocusButton.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) return;

        if (supportsManualFocus() && manualFocus != null) {
            labelWithState(autofocusButton, "AF", "OFF");
            styleViewfinderLabel(autofocusButton, false);
            autofocusButton.setEnabled(true);
            autofocusButton.setContentDescription(getString(R.string.manual_focus_restore));
            return;
        }
        boolean locked = livePreview.focusPoint() != null;
        styleViewfinderLabel(autofocusButton, !locked);
        if (locked) {
            labelWithState(autofocusButton, "AF", "OFF");
            autofocusButton.setContentDescription(getString(R.string.af_off_restore));
        } else {
            labelWithState(autofocusButton, "AF", "ON");
            autofocusButton.setContentDescription(getString(R.string.af_continuous));
        }
        autofocusButton.setEnabled(locked);
    }

    private void showFocusMarker(int color, String message) {
        focusMarker.setBackground(glass(12, Color.TRANSPARENT, Color.TRANSPARENT, color));
        focusMarker.setContentDescription(message);
        focusMarker.setVisibility(View.VISIBLE);
    }

    private void styleMode(TextView view, boolean selected) {
        view.setSelected(selected);
        view.setTextColor(selected ? 0xff112131 : INK);
        view.setBackground(selected ? ripple(luminousSurface(24, 0xffe1f4ff, 0xffa6cee7, 0xeef2fbff), 24)
            : ripple(glass(24, Color.TRANSPARENT, Color.TRANSPARENT, Color.TRANSPARENT), 24));
    }

    private void styleIconControl(ImageButton view, boolean active) {
        int inset = view.getPaddingLeft();
        view.setSelected(active);
        view.setImageTintList(ColorStateList.valueOf(active ? VIEWFINDER_ACTIVE : INK));
        view.setBackground(controlSurface(active));
        view.setPadding(inset, inset, inset, inset);
    }

    private void showModeHint() {
        String rotationHint = manualRotation == 0 ? "" : " · ↻ " + manualRotation + "°";
        if (recordingMode) {
            String hint = videoRates.isEmpty() ? getString(R.string.lens_video_unavailable)
                : switch (videoProfile) {
                    case SLOW, SUPER_SLOW -> getString(R.string.slow_hint, fps / 30);
                    case ACTION -> getString(R.string.action_hint);
                    case STANDARD -> "";
                    case HORIZON -> getString(R.string.horizon_hint);
                    case TIMELAPSE -> isStarLapse()
                        ? starPreset.exposureSeconds() + " s · ISO " + starPreset.iso() + " · ∞"
                        : getString(R.string.lapse_hint);
                };
            status.setText(hint + rotationHint);
        }
        else if (photoFile == FILE_RAW && rawSize != null) {
            status.setText("DNG · " + rawSize.getWidth() + "×" + rawSize.getHeight() + rotationHint);
        } else if (photoFile == FILE_BOTH) status.setText(getString(R.string.raw_jpeg_hint) + rotationHint);
        else if (manualRotation != 0) status.setText("JPEG" + rotationHint);
        else status.setText(switch (photoIndex) {
            case PHOTO_WIDE -> "JPEG · 4096×2304";
            case PHOTO_XPAN -> getString(R.string.panoramic_jpeg);
            case PHOTO_POLAROID -> getString(R.string.framed_jpeg);
            default -> "JPEG · 4096×3072";
        });
    }

    private void chooseLens() {
        hideZoomPanel();
        String[] options = new String[REAR_LENSES.length];
        for (int i = 0; i < options.length; i++) {
            options[i] = getString(REAR_LENSES[i]) + " · " + zoomText(LENS_MAGNIFICATIONS[i]);
        }
        chooseFrom(lensChip, options, lensIndex == 3 ? -1 : lensIndex, this::selectLens);
    }

    private void selectLens(int index) {
        manualFocus = null;
        focusMagnified = false;
        zoomRatio = 1f;
        lensIndex = index;
        if (index != 3) lastRearLens = index;
        updateVideoOptions();
        reviewing = false;
        updateControls();
        refreshPreview();
        showModeHint();
    }

    private void switchCamera() {
        if (cleanRecording && recordingCameraSwitch != null) {
            if (recordingCameraSwitch.request()) {
                cameraSwitchButton.setEnabled(false);
                zoomRatio = 1f;
            }
        } else if (!busy) {
            selectLens(lensIndex == 3 ? lastRearLens : 3);
        }
    }

    private boolean canSwitchDuringVideo() {
        return videoProfile == VideoProfile.STANDARD && videoSize == VideoOptions.FULL_HD && fps == 30
            && (lensIndex == 0 || lensIndex == 3) && currentWhiteBalance() == WhiteBalance.AUTO
            && currentAdjustments().iso() == 0 && !videoTorch;
    }

    private void refreshCameraSwitchButton() {
        boolean visible = resumed && !reviewing && (!capturing && emptyPreview.getVisibility() == View.GONE
            || cleanRecording && recordingCameraSwitch != null);
        cameraSwitchButton.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) return;
        boolean front = cleanRecording ? recordingFront : lensIndex == 3;
        styleViewfinderIcon(cameraSwitchButton, R.drawable.ic_camera_flip, front);
        cameraSwitchButton.setContentDescription(getString(front ? R.string.switch_rear : R.string.switch_front));
        positionCameraSwitchButton();
    }

    private void positionCameraSwitchButton() {
        if (cameraSwitchButton == null || framing.isEmpty()) return;
        int left = cleanRecording ? controls.getPaddingLeft() + dp(19) : framing.left + dp(6);
        int bottom = cleanRecording ? controls.getHeight() - controls.getPaddingBottom() - dp(16)
            : framing.bottom - dp(6);
        cameraSwitchButton.setTranslationX(left);
        cameraSwitchButton.setTranslationY(bottom - dp(48));
    }

    private void refreshScreenFlash() {
        boolean front = capturing && recordingMode ? recordingFront : lensIndex == 3;
        boolean visible = resumed && front && !reviewing
            && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        if (!visible) setScreenFlash(false);
        boolean ready = visible && (!capturing || cleanRecording) && emptyPreview.getVisibility() == View.GONE;
        screenFlashButton.setVisibility(ready ? View.VISIBLE : View.GONE);
        if (!ready) return;
        styleViewfinderIcon(screenFlashButton, R.drawable.ic_screen_light, screenFlashOn);
        screenFlashButton.setContentDescription(screenFlashOn ? getString(R.string.screen_light_off) : getString(R.string.screen_light_on));
        positionScreenFlashButton();
    }

    private void positionScreenFlashButton() {
        if (framing.isEmpty()) return;
        int right = cleanRecording ? Math.min(framing.right, controls.getWidth() - controls.getPaddingRight())
            : framing.right;
        int top = cleanRecording ? Math.max(framing.top, controls.getPaddingTop()) : framing.top;
        int offset = cleanRecording ? dp(84) + recordTimer.getLayoutParams().width : dp(56);
        screenFlashButton.setTranslationX(Math.max(framing.left, right - offset));
        screenFlashButton.setTranslationY(top + dp(6));
    }

    private void setScreenFlash(boolean enabled) {
        if (screenFlashOn == enabled) return;
        screenFlashOn = enabled;
        WindowManager.LayoutParams params = getWindow().getAttributes();
        if (enabled) previousScreenBrightness = params.screenBrightness;
        params.screenBrightness = enabled ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL : previousScreenBrightness;
        getWindow().setAttributes(params);
        screenFlashBorder.setVisibility(enabled ? View.VISIBLE : View.GONE);
        if (landscape && status != null) status.setTextColor(enabled ? 0xff293e50 : MUTED);
        updateViewfinderArea();
    }

    private void onRecordingCameraChanged(String id) {
        hideZoomPanel();
        if (isDestroyed()) return;
        recordingFront = "1".equals(id);
        CameraManager cameraManager = getSystemService(CameraManager.class);
        try {
            livePreview.setRecordingCameraGeometry(cameraManager.getCameraCharacteristics(id));
        } catch (android.hardware.camera2.CameraAccessException unavailable) {
            android.util.Log.w("CameraX9P", "Recording preview geometry unavailable", unavailable);
        }
        cameraSwitchButton.setEnabled(true);
        refreshCameraSwitchButton();
        refreshScreenFlash();
    }

    private Range<Float> zoomRange() {
        return currentCamera == null ? null : currentCamera.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
    }

    private boolean canZoom() {
        Range<Float> range = zoomRange();
        boolean available = (!capturing && !busy) || (cleanRecording && fps < 120);
        return resumed && !reviewing && !isStarLapse() && available && emptyPreview.getVisibility() == View.GONE
            && range != null && !range.getLower().equals(range.getUpper());
    }

    private void addZoomPanel(FrameLayout preview) {
        zoomPanel = column();
        zoomPanel.setGravity(Gravity.CENTER);
        zoomPanel.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        zoomPanel.setPadding(dp(6), dp(5), dp(6), dp(5));
        zoomPanel.setBackground(luminousSurface(18, 0xdc263440, 0xef141e2a, ACTIVE_EDGE));
        zoomPanel.setVisibility(View.GONE);

        zoomValue = label("", 17, VIEWFINDER_ACTIVE, true);
        zoomValue.setGravity(Gravity.CENTER);
        zoomValue.setShadowLayer(dp(5), 0, 0, 0xff169fec);
        zoomValue.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        zoomPanel.addView(zoomValue, new LinearLayout.LayoutParams(-1, dp(30)));

        zoomRuler = new ZoomRuler(this, landscape, VIEWFINDER_ACTIVE, this::zoomFromRuler);
        zoomRuler.setContentDescription(getString(R.string.zoom_control));
        zoomRuler.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) zoomFromRuler(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        zoomRuler.setOnClickListener(view -> scheduleZoomHide(1100));
        zoomPanel.addView(zoomRuler, new LinearLayout.LayoutParams(landscape ? dp(56) : -1,
            dp(landscape ? 116 : 48)));

        zoomLenses = row();
        for (int index : new int[]{1, 0, 2}) {
            int lens = index;
            TextView button = label(zoomText(LENS_MAGNIFICATIONS[lens]), 12, INK, true);
            button.setGravity(Gravity.CENTER);
            button.setContentDescription(getString(R.string.lens_zoom, getString(REAR_LENSES[lens]),
                zoomText(LENS_MAGNIFICATIONS[lens])));
            button.setOnClickListener(view -> {
                if (busy || cleanRecording || reviewing) return;
                if (lens != lensIndex) selectLens(lens);
                else setZoomRatio(1f);
                updateZoomPanel();
                scheduleZoomHide(1600);
            });
            zoomLensButtons[lens] = button;
            zoomLenses.addView(button, new LinearLayout.LayoutParams(0, dp(42), 1));
        }
        zoomPanel.addView(zoomLenses, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(landscape ? 150 : 222), -2,
            landscape ? Gravity.LEFT | Gravity.CENTER_VERTICAL : Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        preview.addView(zoomPanel, params);
    }

    private void showZoomPanel() {
        zoomPanel.removeCallbacks(hideZoom);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) zoomPanel.getLayoutParams();
        if (landscape) params.width = dp(cleanRecording || lensIndex == 3 ? 74 : 150);
        params.leftMargin = landscape ? controls.getPaddingLeft() + dp(cleanRecording ? 16 : 114) : 0;
        params.bottomMargin = landscape ? 0 : controls.getPaddingBottom() + dp(cleanRecording ? 121 : 190);
        zoomPanel.setLayoutParams(params);
        updateZoomPanel();
        zoomPanel.setVisibility(View.VISIBLE);
        if (!landscape) {
            adjustmentButton.setVisibility(View.GONE);
            microphoneButton.setVisibility(View.GONE);
        }
    }

    private void scheduleZoomHide(int delay) {
        if (zoomPanel == null || zoomPanel.getVisibility() != View.VISIBLE) return;
        zoomPanel.removeCallbacks(hideZoom);
        android.view.accessibility.AccessibilityManager accessibility =
            getSystemService(android.view.accessibility.AccessibilityManager.class);
        int timeout = accessibility.getRecommendedTimeoutMillis(delay,
            android.view.accessibility.AccessibilityManager.FLAG_CONTENT_CONTROLS
                | android.view.accessibility.AccessibilityManager.FLAG_CONTENT_TEXT);
        zoomPanel.postDelayed(hideZoom, timeout);
    }

    private void hideZoomPanel() {
        if (zoomPanel == null || zoomPanel.getVisibility() != View.VISIBLE) return;
        zoomPanel.removeCallbacks(hideZoom);
        zoomPanel.setVisibility(View.GONE);
        if (!landscape) updateShootingTools();
    }

    private void zoomFromRuler(int progress) {
        if (!canZoom()) return;
        Range<Float> range = zoomRange();
        double fraction = (double) progress / zoomRuler.getMax();
        setZoomRatio((float) (range.getLower() * Math.pow(range.getUpper() / range.getLower(), fraction)));
        if (zoomRuler.isDragging()) zoomPanel.removeCallbacks(hideZoom);
        else scheduleZoomHide(1100);
    }

    private void updateZoomPanel() {
        Range<Float> range = zoomRange();
        String value = zoomText(zoomRatio * LENS_MAGNIFICATIONS[lensIndex]);
        zoomValue.setText(value);
        zoomRuler.setStateDescription(value);
        zoomRuler.setEnabled(range != null && !range.getLower().equals(range.getUpper()));
        if (zoomRuler.isEnabled()) {
            double fraction = Math.log(range.clamp(zoomRatio) / range.getLower())
                / Math.log(range.getUpper() / range.getLower());
            zoomRuler.setProgress((int) Math.round(fraction * zoomRuler.getMax()));
            zoomRuler.invalidate();
        }
        zoomLenses.setVisibility(cleanRecording || lensIndex == 3 ? View.GONE : View.VISIBLE);
        for (int i = 0; i < zoomLensButtons.length; i++) {
            TextView button = zoomLensButtons[i];
            button.setSelected(i == lensIndex);
            button.setTextColor(i == lensIndex ? VIEWFINDER_ACTIVE : INK);
        }
    }

    private String zoomText(float magnification) { return formatDecimal(magnification) + "×"; }

    private void setZoomRatio(float requested) {
        Range<Float> range = zoomRange();
        if (range == null || !Float.isFinite(requested)) return;
        zoomRatio = range.clamp(requested);
        livePreview.setZoomRatio(CAMERA_IDS[lensIndex], zoomRatio);
        updateZoomLabel();
        if (zoomPanel.getVisibility() == View.VISIBLE) updateZoomPanel();
    }

    private void updateZoomLabel() {
        if (lensIndex == 3) {
            lensChip.setText(getString(R.string.lenses));
            lensChip.setContentDescription(getString(R.string.choose_rear_lens));
            return;
        }
        String label = zoomText(zoomRatio * LENS_MAGNIFICATIONS[lensIndex]);
        lensChip.setText(label + "  ▾");
        lensChip.setContentDescription(getString(R.string.lens_zoom, getString(REAR_LENSES[lensIndex]), label));
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if ((key == KeyEvent.KEYCODE_ZOOM_IN || key == KeyEvent.KEYCODE_ZOOM_OUT) && canZoom()
                && (dropdown == null || !dropdown.isShowing())) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                setZoomRatio(zoomRatio * (key == KeyEvent.KEYCODE_ZOOM_IN ? 1.12f : 1f / 1.12f));
                showZoomPanel();
                scheduleZoomHide(1100);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void chooseFormat() {
        if (recordingMode) {
            List<VideoOptions.Format> formats = availableVideoFormats();
            if (isStarLapse() || formats.size() < 2) return;
            String[] options = formats.stream().map(format -> format.label(this)).toArray(String[]::new);
            chooseFrom(formatChip, options, formats.indexOf(videoSize.format), which -> {
                List<VideoOptions> sizes = sizesForFormat(formats.get(which));
                if (!sizes.contains(videoSize)) {
                    selectVideoSize(sizes.contains(standardWideSize) ? standardWideSize : sizes.get(0));
                }
            });
        } else if (photoFile != FILE_RAW) {
            chooseFrom(formatChip, Arrays.stream(PHOTO_FORMATS).mapToObj(this::getString).toArray(String[]::new), photoIndex, which -> {
                photoIndex = which;
                refreshFramingChoice();
            });
        }
    }

    private List<VideoOptions.Format> availableVideoFormats() {
        return videoSizes.stream().map(size -> size.format).distinct().collect(Collectors.toList());
    }

    private List<VideoOptions> sizesForFormat(VideoOptions.Format format) {
        return videoSizes.stream().filter(size -> size.format == format).collect(Collectors.toList());
    }

    private void chooseQuality() {
        if (!recordingMode || isStarLapse()) return;
        List<VideoOptions> sizes = sizesForFormat(videoSize.format);
        if (sizes.size() < 2) return;
        String[] options = sizes.stream().map(size -> size.description).toArray(String[]::new);
        chooseFrom(qualityChip, options, sizes.indexOf(videoSize), which -> selectVideoSize(sizes.get(which)));
    }

    private void selectVideoSize(VideoOptions size) {
        videoSize = size;
        if (videoProfile == VideoProfile.STANDARD && size.format == VideoOptions.Format.WIDE) standardWideSize = size;
        updateVideoOptions();
        refreshFramingChoice();
    }

    private void refreshFramingChoice() {
        if (reviewing) {
            reviewing = false;
            refreshPreview();
        }
        updateControls();
        showModeHint();
    }

    private void choosePhotoFile() {
        if (rawSize == null) return;
        chooseFrom(fileChip, PHOTO_FILES, photoFile, which -> {
            photoFile = which;
            updateControls();
            showModeHint();
        });
    }

    private void updateVideoOptions() {
        String id = CAMERA_IDS[lensIndex];
        try {
            CameraCharacteristics camera = getSystemService(CameraManager.class).getCameraCharacteristics(id);
            currentCamera = camera;
            starPreset = StarLapse.preset(camera);
            if (starPreset == null) starLapse = false;
            Range<Float> range = zoomRange();
            zoomRatio = range == null ? 1f : range.clamp(zoomRatio);
            rawSize = RawPhoto.size(camera);
            if (rawSize == null) photoFile = FILE_JPEG;
            availableProfiles = Arrays.stream(VideoProfile.values())
                .filter(profile -> profile != VideoProfile.HORIZON || RollTrack.supported(this))
                .filter(profile -> !profile.sizes(camera, id).isEmpty()).collect(Collectors.toList());
            if (!availableProfiles.contains(videoProfile)) setVideoProfile(VideoProfile.STANDARD);
            videoSizes = videoProfile.sizes(camera, id);
            if (!videoSizes.isEmpty() && !videoSizes.contains(videoSize)) {
                List<VideoOptions> sameFormat = sizesForFormat(videoSize.format);
                videoSize = sameFormat.isEmpty() ? videoSizes.get(0) : sameFormat.get(0);
            }
            videoRates = videoSizes.isEmpty() ? List.of() : videoProfile.rates(camera, videoSize);
            if (!videoRates.isEmpty() && !videoRates.contains(fps)) fps = videoRates.get(0);
        } catch (Exception unavailable) {
            currentCamera = null;
            starPreset = null;
            zoomRatio = 1f;
            rawSize = null;
            photoFile = FILE_JPEG;
            availableProfiles = List.of();
            videoSizes = List.of();
            videoRates = List.of();
        }
    }

    private void setVideoProfile(VideoProfile profile) {
        if (videoProfile == profile) return;
        if (videoProfile == VideoProfile.STANDARD) {
            standardSize = videoSize;
            standardFps = fps;
        }
        videoProfile = profile;
        if (profile == VideoProfile.STANDARD) {
            videoSize = standardSize;
            fps = standardFps;
        } else {
            videoSize = profile == VideoProfile.SUPER_SLOW || profile == VideoProfile.TIMELAPSE
                ? VideoOptions.FULL_HD : VideoOptions.UHD;
            fps = profile == VideoProfile.ACTION ? 60 : 120;
        }
    }

    private void chooseVideoProfile() {
        if (availableProfiles.isEmpty()) return;
        String cameraId = CAMERA_IDS[lensIndex];
        String[] options = availableProfiles.stream().map(profile -> {
            String title = profile.label(this);
            if (!profile.isSlowMotion()) return title;
            int maxFps = profile.sizes(currentCamera, cameraId).stream()
                .flatMap(size -> profile.rates(currentCamera, size).stream())
                .mapToInt(Integer::intValue).max().orElse(0);
            return maxFps > 0 ? getString(R.string.profile_max_fps, title, maxFps) : title;
        }).toArray(String[]::new);
        chooseFrom(profileChip, options, availableProfiles.indexOf(videoProfile), which -> {
            setVideoProfile(availableProfiles.get(which));
            updateVideoOptions();
            if (reviewing) {
                reviewing = false;
                refreshPreview();
            }
            updateControls();
            showModeHint();
        });
    }

    private void chooseFps() {
        if (isStarLapse()) return;
        if (videoProfile == VideoProfile.TIMELAPSE) {
            int[] intervals = {500, 1000, 2000};
            String[] choices = Arrays.stream(intervals).mapToObj(this::lapseLabel).toArray(String[]::new);
            chooseFrom(fpsChip, choices, Arrays.binarySearch(intervals, lapseIntervalMs), which -> {
                lapseIntervalMs = intervals[which];
                updateControls();
                showModeHint();
            });
            return;
        }
        if (videoRates.isEmpty()) return;
        String[] options = videoRates.stream().map(rate -> rate + " fps").toArray(String[]::new);
        chooseFrom(fpsChip, options, videoRates.indexOf(fps), which -> {
            fps = videoRates.get(which);
            updateControls();
            showModeHint();
        });
    }

    private String lapseLabel(int interval) {
        return getString(R.string.interval_seconds, formatDecimal(interval / 1000d));
    }

    private String formatDecimal(double value) {
        var format = java.text.NumberFormat.getNumberInstance(getResources().getConfiguration().getLocales().get(0));
        format.setMaximumFractionDigits(2);
        format.setGroupingUsed(false);
        return format.format(value);
    }

    private void chooseFrom(View anchor, String[] entries, int selected, java.util.function.IntConsumer select) {
        chooseFrom(anchor, entries, selected, select, null);
    }

    private void chooseFrom(View anchor, String[] entries, int selected, java.util.function.IntConsumer select,
            MenuReset[] resets) {
        if (dropdown != null) dropdown.dismiss();
        ListPopupWindow popup = new ListPopupWindow(this);
        popup.setAnchorView(anchor);
        popup.setDropDownGravity(Gravity.START);
        popup.setModal(true);
        Rect available = new Rect();
        anchor.getWindowVisibleDisplayFrame(available);
        Paint menuText = new Paint(Paint.ANTI_ALIAS_FLAG);
        menuText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        menuText.setTextSize(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,
            15, getResources().getDisplayMetrics()));
        float longestLabel = 0;
        for (String entry : entries) longestLabel = Math.max(longestLabel, menuText.measureText(entry));
        boolean hasReset = resets != null && Arrays.stream(resets).anyMatch(java.util.Objects::nonNull);
        int contentWidth = (int) Math.ceil(longestLabel) + dp(hasReset ? 114 : 66);
        int menuWidth = Math.min(Math.max(Math.max(anchor.getWidth(), dp(244)), contentWidth),
            available.width() - dp(24));
        int menuHeight = Math.min(dp(12 + entries.length * 51), available.height() - dp(16));
        int[] anchorLocation = new int[2];
        anchor.getLocationOnScreen(anchorLocation);
        int anchorBottom = anchorLocation[1] + anchor.getHeight();
        int preferredTop = anchorBottom + dp(6);
        if (landscape && anchorLocation[0] + anchor.getWidth() + dp(8) + menuWidth <= available.right) {
            popup.setHorizontalOffset(anchor.getWidth() + dp(8));
            preferredTop = anchorLocation[1] + (anchor.getHeight() - menuHeight) / 2;
        } else if (preferredTop + menuHeight > available.bottom - dp(8)) {
            preferredTop = anchorLocation[1] - dp(6) - menuHeight;
        }
        int menuTop = Math.max(available.top + dp(8), Math.min(preferredTop, available.bottom - dp(8) - menuHeight));
        popup.setWidth(menuWidth);
        popup.setHeight(menuHeight);
        popup.setVerticalOffset(menuTop - anchorBottom);
        popup.setBackgroundDrawable(luminousSurface(24, PANEL_LIGHT, PANEL_DARK, 0x7195b7d5));
        popup.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, entries) {
            @Override public View getView(int position, View recycled, android.view.ViewGroup parent) {
                if (!hasReset) {
                    CheckedTextView item = recycled == null ? new CheckedTextView(CameraX9PActivity.this) : (CheckedTextView) recycled;
                    styleDropdownEntry(item, getItem(position), position == selected);
                    return item;
                }
                LinearLayout row = recycled == null ? dropdownRowWithReset() : (LinearLayout) recycled;
                CheckedTextView item = (CheckedTextView) row.getChildAt(0);
                styleDropdownEntry(item, getItem(position), position == selected);
                item.setOnClickListener(view -> {
                    popup.dismiss();
                    select.accept(position);
                });
                ImageButton button = (ImageButton) row.getChildAt(1);
                MenuReset reset = resets[position];
                button.setVisibility(reset == null ? View.GONE : View.VISIBLE);
                if (reset == null) {
                    button.setOnClickListener(null);
                } else {
                    button.setContentDescription(getString(R.string.reset_menu_setting, reset.setting()));
                    button.setOnClickListener(view -> {
                        popup.dismiss();
                        reset.action().run();
                        updateShootingTools();
                        showModeHint();
                    });
                }
                return row;
            }
        });
        popup.setOnItemClickListener((parent, item, position, id) -> {
            popup.dismiss();
            select.accept(position);
        });
        dropdown = popup;
        popup.show();
        ListView list = popup.getListView();
        if (list != null) {
            list.setPadding(dp(6), dp(6), dp(6), dp(6));
            list.setDivider(new ColorDrawable(Color.TRANSPARENT));
            list.setDividerHeight(dp(3));
            list.setSelector(new ColorDrawable(Color.TRANSPARENT));
            list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
            if (hasReset) list.setItemsCanFocus(true);
            if (selected >= 0) list.setItemChecked(selected, true);
        }
    }

    private LinearLayout dropdownRowWithReset() {
        LinearLayout row = row();
        CheckedTextView entry = new CheckedTextView(this);
        entry.setFocusable(true);
        row.addView(entry, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton reset = new ImageButton(this);
        reset.setImageResource(R.drawable.ic_reset);
        reset.setImageTintList(ColorStateList.valueOf(VIEWFINDER_ACTIVE));
        reset.setScaleType(ImageView.ScaleType.FIT_CENTER);
        reset.setBackground(controlSurface(false));
        reset.setPadding(dp(13), dp(13), dp(13), dp(13));
        LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        resetParams.setMarginStart(dp(4));
        row.addView(reset, resetParams);
        return row;
    }

    private void styleDropdownEntry(CheckedTextView item, String label, boolean checked) {
        item.setText(label);
        item.setTextSize(15);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setTypeface(Typeface.create(checked ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        item.setTextColor(checked ? VIEWFINDER_ACTIVE : INK);
        item.setChecked(checked);
        item.setMinHeight(dp(48));
        item.setSingleLine(false);
        item.setMaxLines(2);
        item.setEllipsize(android.text.TextUtils.TruncateAt.END);
        item.setPadding(dp(14), dp(6), dp(14), dp(6));
        Drawable check = checked ? getDrawable(R.drawable.ic_choice) : null;
        if (check != null) check.setTint(VIEWFINDER_ACTIVE);
        item.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, check, null);
        item.setCompoundDrawablePadding(dp(8));
        item.setBackground(ripple(checked ? glass(16, 0xb83e6079, 0xcd21374e, 0x4985ccfc)
            : glass(16, Color.TRANSPARENT, Color.TRANSPARENT, Color.TRANSPARENT), 16));
    }

    private void showGlassDialog(AlertDialog dialog) {
        dialog.show();
        dialog.getWindow().setBackgroundDrawable(luminousSurface(30, PANEL_LIGHT, PANEL_DARK, 0x7195b7d5));
        dialog.getWindow().setDimAmount(.42f);
        Rect bounds = getWindowManager().getCurrentWindowMetrics().getBounds();
        dialog.getWindow().setLayout(Math.min(dp(460), bounds.width() - dp(32)),
            WindowManager.LayoutParams.WRAP_CONTENT);
        for (int id : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            Button button = dialog.getButton(id);
            if (button == null) continue;
            boolean primary = id == AlertDialog.BUTTON_POSITIVE;
            button.setAllCaps(false);
            button.setTextSize(14);
            button.setTextColor(primary ? 0xff15283a : INK);
            button.setBackground(primary ? ripple(luminousSurface(22, 0xffe4f5ff, 0xff9dc8e7, 0xffe7f6ff), 22)
                : ripple(glass(22, Color.TRANSPARENT, Color.TRANSPARENT, Color.TRANSPARENT), 22));
            button.setPadding(dp(14), 0, dp(14), 0);
            addPressFeedback(button);
        }
    }

    private void showPreviewMessage(String title, String hint) {
        previewTitle.setText(title);
        previewHint.setText(hint);
        emptyPreview.setVisibility(View.VISIBLE);
        clearFocusUi();
        updateGrid();
    }

    private void refreshPreview() {
        if (!resumed || capturing) return;
        texture.setVisibility(reviewing ? View.INVISIBLE : View.VISIBLE);
        image.setVisibility(reviewing ? View.VISIBLE : View.GONE);
        if (reviewing) {
            livePreview.stop();
            clearFocusUi();
            emptyPreview.setVisibility(View.GONE);
        } else if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            emptyPreview.setOnClickListener(v -> refreshPreview());
            livePreview.start(CAMERA_IDS[lensIndex]);
            livePreview.setZoomRatio(CAMERA_IDS[lensIndex], zoomRatio);
        } else {
            showPreviewMessage(getString(R.string.camera_access), getString(R.string.enable_viewfinder));
            emptyPreview.setOnClickListener(v -> requestPermissions(new String[]{Manifest.permission.CAMERA}, 1));
        }
    }

    private void toggleReview() {
        if (original == null && galleryPreview != null) original = galleryPreview.bitmap();
        if (original == null) return;
        reviewing = !reviewing;
        updateControls();
        refreshPreview();
        if (reviewing) render();
    }

    private void openGallery() {
        hideZoomPanel();
        Intent oppoPhotos = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY)
            .setPackage("com.coloros.gallery3d").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(oppoPhotos);
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            try {
                startActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY));
            } catch (ActivityNotFoundException | SecurityException noGallery) {
                status.setText(getString(R.string.photos_unavailable));
                status.setVisibility(View.VISIBLE);
            }
        }
    }

    private void refreshGalleryPreview(Bitmap capturePreview) {
        GalleryPreview previous = galleryPreview;
        worker.execute(() -> {
            GalleryPreview latest;
            try {
                latest = GalleryPreview.load(getContentResolver(), getPackageName(),
                    previous == null ? null : previous.uri(), previous == null ? null : previous.bitmap());
            } catch (RuntimeException unavailable) {
                android.util.Log.w("CameraX9P", "Gallery thumbnail unavailable", unavailable);
                return;
            }
            if (latest != null && latest.bitmap() == null && capturePreview != null) {
                latest = new GalleryPreview(latest.uri(), latest.video(), capturePreview);
            }
            GalleryPreview result = latest;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                galleryPreview = result;
                showGalleryPreview();
            });
        });
    }

    private void showGalleryPreview() {
        Bitmap bitmap = galleryPreview == null ? null : galleryPreview.bitmap();
        int inset = bitmap == null ? dp(landscape ? 9 : 12) : dp(2);
        reviewChip.setPadding(inset, inset, inset, inset);
        reviewChip.setScaleType(bitmap == null ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
        if (bitmap == null) reviewChip.setImageResource(R.drawable.ic_album);
        else reviewChip.setImageBitmap(bitmap);
        reviewChip.setContentDescription(bitmap == null ? getString(R.string.open_photos)
            : galleryPreview.video() ? getString(R.string.open_photos_video) : getString(R.string.open_photos_photo));
    }

    private void openColor() {
        LinearLayout panel = landscape ? row() : column();
        panel.setGravity(Gravity.TOP);
        panel.setPadding(dp(20), dp(8), dp(20), dp(12));

        LinearLayout monochromeCard = colorCard();
        Switch blackAndWhite = colorSwitch(getString(R.string.monochrome), monochrome);
        blackAndWhite.setOnCheckedChangeListener((button, enabled) -> {
            monochrome = enabled;
            updateMonochromePreview();
        });
        monochromeCard.addView(blackAndWhite);
        monochromeCard.addView(label(getString(R.string.monochrome_scope), 12, MUTED, false));

        LinearLayout lutCard = colorCard();
        lutCard.addView(label("LUT", 17, INK, true));
        TextView source = label(lutName == null ? getString(R.string.no_cube) : lutName, 13, MUTED, false);
        source.setMaxLines(1);
        source.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        lutCard.addView(source);
        TextView level = label(getString(R.string.lut_intensity_value, intensity), 14, INK, true);
        level.setPadding(0, dp(10), 0, 0);
        SeekBar slider = new SeekBar(this);
        slider.setMax(100);
        slider.setProgress(intensity);
        slider.setEnabled(lut != null);
        slider.setProgressTintList(ColorStateList.valueOf(VIEWFINDER_ACTIVE));
        slider.setProgressBackgroundTintList(ColorStateList.valueOf(0xff4d6277));
        slider.setThumbTintList(ColorStateList.valueOf(0xffd7efff));
        slider.setContentDescription(getString(R.string.lut_intensity));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                intensity = value;
                level.setText(getString(R.string.lut_intensity_value, value));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        lutCard.addView(level);
        lutCard.addView(slider, new LinearLayout.LayoutParams(-1, dp(34)));
        Switch export = colorSwitch(getString(R.string.lut_save), saveLut);
        export.setEnabled(lut != null);
        export.setOnCheckedChangeListener((button, enabled) -> saveLut = enabled);
        lutCard.addView(export);
        TextView rawNote = label(getString(R.string.raw_ungraded), 12, MUTED, false);
        lutCard.addView(rawNote);

        LinearLayout fileActions = row();
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-1, -2);
        actionParams.topMargin = dp(8);
        lutCard.addView(fileActions, actionParams);
        boolean cancelImport = lutImport != null;
        TextView importLut = chip(getString(cancelImport ? android.R.string.cancel : R.string.import_cube), () -> {});
        TextView removeLut = chip(getString(R.string.remove_lut), () -> {});
        fileActions.addView(importLut, new LinearLayout.LayoutParams(0, dp(44), 1));
        if (lut != null) {
            LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(0, dp(44), 1);
            removeParams.setMarginStart(dp(6));
            fileActions.addView(removeLut, removeParams);
        }
        TextView review = chip(reviewing ? getString(R.string.return_to_viewfinder) : getString(R.string.lut_preview), () -> {});
        LinearLayout.LayoutParams reviewParams = new LinearLayout.LayoutParams(-1, dp(44));
        reviewParams.topMargin = dp(6);
        lutCard.addView(review, reviewParams);
        review.setEnabled(original != null || (galleryPreview != null && galleryPreview.bitmap() != null));
        review.setAlpha(review.isEnabled() ? 1f : .45f);

        panel.addView(monochromeCard, landscape ? new LinearLayout.LayoutParams(0, -2, .88f)
            : new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams lutParams = landscape ? new LinearLayout.LayoutParams(0, -2, 1.42f)
            : new LinearLayout.LayoutParams(-1, -2);
        if (landscape) lutParams.setMarginStart(dp(12));
        else lutParams.topMargin = dp(10);
        panel.addView(lutCard, lutParams);

        ScrollView body = new ScrollView(this);
        body.addView(panel);
        AlertDialog shown = new AlertDialog.Builder(this).setTitle(getString(R.string.color))
            .setView(body).setPositiveButton(getString(R.string.done), null).create();
        shown.setOnDismissListener(d -> { saveColorSettings(); updateControls(); if (reviewing) render(); });
        review.setOnClickListener(view -> { toggleReview(); shown.dismiss(); });
        importLut.setOnClickListener(view -> {
            shown.dismiss();
            if (cancelImport) {
                if (lutImport != null) {
                    cancelLutImport();
                    status.setVisibility(View.GONE);
                }
                return;
            }
            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
            picker.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download"));
            startActivityForResult(picker, 30);
        });
        removeLut.setOnClickListener(view -> {
            cancelLutImport();
            lutFile().delete();
            lut = null;
            savedLutName = null;
            lutName = null;
            saveLut = false;
            shown.dismiss();
        });
        showGlassDialog(shown);
        if (landscape) {
            int width = getWindowManager().getCurrentWindowMetrics().getBounds().width();
            shown.getWindow().setLayout(Math.min(dp(560), width - dp(32)), WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    private LinearLayout colorCard() {
        LinearLayout card = column();
        card.setBackground(luminousSurface(20, 0xe92e4052, 0xf0212e3d, 0x527b9cb8));
        card.setPadding(dp(14), dp(10), dp(14), dp(12));
        return card;
    }

    private Switch colorSwitch(String text, boolean checked) {
        Switch button = new Switch(this);
        button.setText(text);
        button.setTextColor(INK);
        button.setMinHeight(dp(45));
        button.setChecked(checked);
        button.setThumbTintList(new ColorStateList(new int[][]{
            new int[]{android.R.attr.state_checked}, new int[0]}, new int[]{VIEWFINDER_ACTIVE, 0xffa2afbd}));
        button.setTrackTintList(new ColorStateList(new int[][]{
            new int[]{android.R.attr.state_checked}, new int[0]}, new int[]{0xff3d7aa3, 0xff455567}));
        return button;
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("video", recordingMode);
        out.putString("microphone", microphoneKey);
        out.putInt("photo", photoIndex);
        out.putInt("photoFile", photoFile);
        out.putInt("lapseIntervalMs", lapseIntervalMs);
        out.putBoolean("starLapse", starLapse);
        out.putFloat("zoomRatio", zoomRatio);
        out.putInt("adjustment", adjustment.ordinal());
        out.putInt("ev", ev);
        out.putInt("contrast", contrast);
        out.putInt("iso", iso);
        out.putInt("meteredIso", meteredIso);
        out.putBoolean("manualIsoSelected", manualIsoSelected);
        out.putLong("manualShutter", manualShutter);
        out.putLong("selectedShutter", selectedShutter);
        if (manualFocus != null) out.putFloat("manualFocus", manualFocus);
        out.putInt("whiteBalance", whiteBalance.ordinal());
        out.putInt("selfTimerSeconds", selfTimerSeconds);
        out.putBoolean("zebraEnabled", zebraEnabled);
        out.putBoolean("levelEnabled", levelEnabled);
        out.putBoolean("bracketEnabled", bracketEnabled);
        out.putInt("photoFlash", photoFlash);
        out.putBoolean("videoTorch", videoTorch);
        out.putString("polaroidCaption", polaroidCaption);
        out.putBoolean("grid", gridEnabled);
        out.putInt("rotation", manualRotation);
        out.putInt("lens", lensIndex);
        out.putInt("size", videoSize.ordinal());
        out.putInt("fps", fps);
        out.putInt("profile", videoProfile.ordinal());
        out.putInt("standardSize", standardSize.ordinal());
        out.putInt("standardWideSize", standardWideSize.ordinal());
        out.putInt("standardFps", standardFps);
    }

    private void pressShutter() {
        if (pendingShutter != null) {
            cancelSelfTimer();
            showModeHint();
            return;
        }
        if (recordingMode || selfTimerSeconds == 0 || reviewing || busy
                || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            capture();
            return;
        }
        long deadline = android.os.SystemClock.uptimeMillis() + 1000L * selfTimerSeconds;
        pendingShutter = new Runnable() {
            @Override public void run() {
                long left = deadline - android.os.SystemClock.uptimeMillis();
                if (left <= 0) {
                    pendingShutter = null;
                    capture();
                    return;
                }
                status.setText(getString(R.string.timer_count, (left + 999) / 1000));
                status.setVisibility(View.VISIBLE);
                status.postDelayed(this, Math.min(left, 1000));
            }
        };
        shutter.setContentDescription(getString(R.string.cancel_timer));
        pendingShutter.run();
    }

    private void cancelSelfTimer() {
        if (pendingShutter == null) return;
        status.removeCallbacks(pendingShutter);
        pendingShutter = null;
        shutter.setContentDescription(getString(R.string.take_photo));
    }

    private void capture() {
        capture(false);
    }

    private void capture(boolean burst) {
        if (busy) return;
        hideZoomPanel();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            retryCapture = true;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
            return;
        }
        if (!burst && bracketEnabled && canBracket()) {
            captureBracket();
            return;
        }
        boolean recording = recordingMode;
        boolean stars = isStarLapse();
        VideoProfile captureProfile = recording ? videoProfile : VideoProfile.STANDARD;
        int photoFormat = effectivePhotoFormat();
        int fileFormat = photoFile;
        LutGrade captureGrade = recording || fileFormat != FILE_RAW ? exportGrade() : null;
        String caption = isPolaroid() ? polaroidCaption : null;
        String id = CAMERA_IDS[lensIndex];
        if (recording && videoRates.isEmpty()) return;
        if (supportsVideoAudio() && !VideoMicrophone.OFF.equals(microphoneKey)) {
            refreshMicrophones();
            if (selectedMicrophone() == null) {
                status.setText(getString(R.string.mic_choose_input));
                return;
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 2);
                return;
            }
        }
        int w = recording ? (stars ? StarLapse.WIDTH : videoSize.width) : 4096;
        int h = recording ? (stars ? StarLapse.HEIGHT : videoSize.height) : 3072;
        Intent config=new Intent().putExtra("camera",id).putExtra("width",w).putExtra("height",h).putExtra("video",recording);
        config.putExtra("displayRotation", LivePreview.displayRotation(texture));
        config.putExtra("manualRotation", manualRotation);
        if (zoomRatio != 1f) config.putExtra("zoomRatio", zoomRatio);
        CameraAdjustments captureAdjustments = currentAdjustments();
        captureAdjustments.writeTo(config);
        if (!recording && fileFormat != FILE_JPEG) config.putExtra("dng", true);
        if (recording) config.putExtra("fps", fps);
        WhiteBalance white = currentWhiteBalance();
        if (white != WhiteBalance.AUTO) config.putExtra("awbMode", white.mode);
        if (white == WhiteBalance.LOCKED) {
            WhiteBalance.Snapshot snapshot = livePreview.lockedWhiteBalance();
            if (snapshot == null) {
                status.setText(getString(R.string.wb_wait));
                status.setVisibility(View.VISIBLE);
                return;
            }
            snapshot.write(config);
        }
        String captureMicrophone = supportsVideoAudio() ? microphoneKey : null;
        if (captureProfile.isSlowMotion()) {
            config.putExtra("playbackFps", 30).putExtra("sessionFps", true);
        } else if (captureProfile == VideoProfile.ACTION) {
            ActionExposure exposure = livePreview.exposure();
            if (exposure == null) {
                if (reviewing) {
                    reviewing = false;
                    updateControls();
                    refreshPreview();
                }
                status.setText(getString(R.string.metering_wait));
                return;
            }
            config.putExtra("actionExposureNs", exposure.timeNs()).putExtra("actionIso", exposure.iso());
        }
        if (recording && videoSize == VideoOptions.FULL_GATE) {
            config.putExtra("videoPreview", true);
        }
        if (stars) config.putExtra("starLapse", true);
        else if (captureProfile == VideoProfile.TIMELAPSE) config.putExtra("lapseIntervalMs", lapseIntervalMs);
        final RollTrack rollTrack;
        try {
            rollTrack = captureProfile == VideoProfile.HORIZON ? new RollTrack(this) : null;
        } catch (IOException unavailable) {
            status.setText(unavailable.getMessage());
            return;
        }
        java.util.concurrent.atomic.AtomicBoolean stop = recording && captureProfile.isManualRecording()
            ? new java.util.concurrent.atomic.AtomicBoolean() : null;
        boolean monitorCadence = stop != null && captureProfile == VideoProfile.STANDARD && fps == 60
            && (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            && getIntent().getBooleanExtra("measureViewfinderCadence", false);
        boolean continuousPreview = monitorCadence && getIntent().getBooleanExtra("continuousPreview", false);
        int previewInterval = continuousPreview ? 1
            : monitorCadence && getIntent().getBooleanExtra("quarterPreview", false) ? 4 : 2;
        if (monitorCadence) {
            config.putExtra("runId", getIntent().getStringExtra("runId"));
            config.putExtra("gpuRelay", getIntent().getBooleanExtra("gpuRelay", false));
        } else if (stop != null && captureProfile == VideoProfile.STANDARD && videoSize == VideoOptions.UHD
                && fps == 60 && lensIndex == 0 && GpuCameraRelay.supportedDevice()) {
            config.putExtra("gpuRelay", true);
        }
        VideoCameraSwitch switcher = recording && stop != null && canSwitchDuringVideo() ? new VideoCameraSwitch() : null;
        recordingCameraSwitch = switcher;
        recordingFront = lensIndex == 3;
        cameraSwitchButton.setEnabled(true);
        recordingStop = stop;
        java.util.concurrent.atomic.AtomicBoolean pause = recording ? new java.util.concurrent.atomic.AtomicBoolean() : null;
        recordingPaused = pause;
        CaptureFiles.begin(config);
        File captureDirectory = CaptureFiles.directory(this, config);
        int previousOrientation = getRequestedOrientation();
        boolean keepAwake = stop != null || captureAdjustments.shutterNs() >= 1_000_000_000L;
        if (keepAwake) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (stop != null) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        }
        FocusPoint point = livePreview.focusPoint();
        if (point != null) config.putExtra("focusX", point.x()).putExtra("focusY", point.y());
        if (supportsManualFocus() && manualFocus != null) config.putExtra("manualFocus", manualFocus);
        if (burst) burstStop = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean release = burstStop;
        focusMagnified = false;
        reviewing = false;
        capturing = true;
        texture.setVisibility(View.VISIBLE);
        image.setVisibility(View.GONE);
        boolean smallerPreview = monitorCadence && getIntent().getBooleanExtra("smallerPreview", false);
        int previewFps = smallerPreview ? fps / previewInterval : fps;
        Surface videoViewfinder = recording
            ? livePreview.prepareRecordingPreview(w, h, fps, smallerPreview ? 320 : 640, previewFps) : null;
        if (monitorCadence && videoViewfinder != null) {
            config.putExtra("diagnosticPreviewSize", livePreview.recordingPreviewSize().toString());
            config.putExtra("diagnosticPreviewRate", previewFps);
        }
        if (!recording) livePreview.stop();
        clearFocusUi();
        emptyPreview.setVisibility(View.GONE);
        busy = true;
        updateControls();
        status.setText(recording ? "" : burst ? getString(R.string.burst_in_progress) : getString(R.string.shot_in_progress));
        status.setVisibility(recording ? View.GONE : View.VISIBLE);
        worker.execute(() -> {
            String result;
            try {
                java.util.function.Consumer<Boolean> indicator = active -> runOnUiThread(() -> setRecordingIndicator(active));
                result = burst
                    ? CaptureProbe.burst(this, config, release::get, report -> {
                        exportPhoto(captureDirectory, report, photoFormat, fileFormat, caption, captureGrade, false);
                        int count = report.optInt("burstFrames") + 1;
                        runOnUiThread(() -> { if (!isDestroyed()) status.setText(getString(R.string.burst_count, count)); });
                    })
                    : monitorCadence
                    ? CaptureProbe.measureViewfinderCadence(this, config, videoViewfinder, indicator, () -> zoomRatio,
                        stop::get, captureMicrophone, previewInterval, pause::get)
                    : switcher == null
                    ? CaptureProbe.run(this, config, videoViewfinder, indicator, recording ? () -> zoomRatio : null,
                        stop == null ? null : stop::get, captureMicrophone, pause == null ? null : pause::get)
                    : switcher.run(this, config, videoViewfinder, indicator,
                        camera -> runOnUiThread(() -> onRecordingCameraChanged(camera)), () -> zoomRatio, stop, pause, captureMicrophone);
            } catch (RuntimeException failure) {
                android.util.Log.w("CameraX9P", "Capture failed", failure);
                result = "{}";
            } finally {
                if (rollTrack != null) rollTrack.close();
                if (videoViewfinder != null) videoViewfinder.release();
            }
            String message = getString(R.string.capture_failure);
            Bitmap bitmap=null;
            try {
                JSONObject report = new JSONObject(result);
                if (burst) {
                    int frames = report.optInt("burstFrames");
                    boolean success = report.optBoolean("success");
                    message = frames == 0 && !success ? getString(R.string.burst_failure)
                        : getResources().getQuantityString(success ? R.plurals.burst_saved : R.plurals.burst_partial, frames, frames);
                    if (!success) android.util.Log.w("CameraX9P", report.optString("error"));
                } else if (!report.optBoolean("success")) {
                    android.util.Log.w("CameraX9P", report.optString("stage") + ": " + report.optString("error"));
                    if (report.optString("stage").equals("segmentsSaved")) message = getString(R.string.segments_saved);
                    if (recording && report.optString("stage").equals("audio")) {
                        message = getString(R.string.mic_unavailable);
                    }
                    if (recording && report.optString("stage").equals("frameRate")) {
                        message = getString(R.string.measured_fps, Math.round(report.optDouble("measuredFps", 0)));
                    }
                    if (report.optBoolean("backgroundStop")) message = getString(R.string.recording_interrupted);
                } else {
                    if (recording) {
                        File clip = new File(captureDirectory, "capture-test.mp4");
                        int outputWidth = report.getInt("actualWidth");
                        int outputHeight = report.getInt("actualHeight");
                        if (report.optInt("actualRotation") % 180 != 0) {
                            int swap = outputWidth;
                            outputWidth = outputHeight;
                            outputHeight = swap;
                        }
                        File processed = null;
                        File graded = null;
                        String clipLabel = getString(R.string.clip_saved);
                        if (captureProfile.isSlowMotion()) {
                            clipLabel = report.optBoolean("frameRateOutsideTolerance")
                                ? getString(R.string.slow_saved_measured, Math.round(report.optDouble("estimatedActiveCaptureFps",
                                    report.optDouble("framesPerCaptureSecond", 0))))
                                : getString(R.string.slow_saved);
                        }
                        if (report.optBoolean("relayInterrupted")) clipLabel = getString(R.string.clip_saved_interrupted);
                        else if (report.optInt("cameraSegments") > 1) clipLabel = getString(R.string.clip_saved_switch);
                        else if (report.optBoolean("switchInterrupted")) clipLabel = getString(R.string.clip_saved_partial_switch);
                        if (captureProfile == VideoProfile.TIMELAPSE) clipLabel = stars ? getString(R.string.stars_saved) : getString(R.string.lapse_saved);
                        try {
                            if (rollTrack != null) {
                                runOnUiThread(() -> {
                                    if (!isDestroyed()) {
                                        status.setText(getString(R.string.horizon_processing));
                                        status.setVisibility(View.VISIBLE);
                                    }
                                });
                                try {
                                    processed = File.createTempFile("leveled-", ".mp4", captureDirectory);
                                    boolean portrait = outputWidth < outputHeight;
                                    int leveledWidth = portrait ? 1080 : 1920;
                                    int leveledHeight = portrait ? 1920 : 1080;
                                    RollTrack.Timeline timeline = rollTrack.timeline(report.getLong("recordingStartedNs"));
                                    VideoExport.write(this, clip, processed, timeline, portrait);
                                    VideoExport.checkVideo(processed, leveledWidth, leveledHeight, report.optBoolean("audio"));
                                    android.util.Log.i("CameraX9P", "Horizon export completed, roll span "
                                        + Math.round(timeline.degreesCovered(report.optLong("recordingMs"))) + " degrees");
                                    clip = processed;
                                    outputWidth = leveledWidth;
                                    outputHeight = leveledHeight;
                                    clipLabel = getString(R.string.horizon_saved);
                                } catch (Exception failed) {
                                    android.util.Log.w("CameraX9P", "Horizon export failed", failed);
                                    clipLabel = getString(R.string.original_saved);
                                }
                            }
                            if (captureGrade != null) {
                                showColorExport(captureGrade);
                                try {
                                    graded = File.createTempFile("graded-", ".mp4", captureDirectory);
                                    VideoExport.write(this, clip, graded, captureGrade);
                                    VideoExport.checkVideo(graded, outputWidth, outputHeight, report.optBoolean("audio"));
                                    clip = graded;
                                    clipLabel = getString(R.string.grade_applied, clipLabel, captureGrade.label(this));
                                } catch (Exception failed) {
                                    android.util.Log.w("CameraX9P", "Video LUT export failed", failed);
                                    clipLabel = getString(R.string.grade_skipped, clipLabel, captureGrade.label(this));
                                }
                            }
                            try {
                                PhotoExport.publishClip(getContentResolver(), clip);
                            } catch (IOException | RuntimeException publication) {
                                CaptureRecovery.store(this, null, null, clip, null, false);
                                throw publication;
                            }
                            if (captureGrade == null) {
                                try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
                                    retriever.setDataSource(clip.getPath());
                                    bitmap = retriever.getScaledFrameAtTime(1000000,
                                        MediaMetadataRetriever.OPTION_CLOSEST, 720, 720);
                                } catch (Exception failure) {
                                    android.util.Log.w("CameraX9P", "Could not read saved video preview", failure);
                                }
                            }
                        } finally {
                            if (processed != null) processed.delete();
                            if (graded != null) graded.delete();
                        }
                        message = clipLabel + " · " + outputWidth + "×" + outputHeight + " · "
                            + Math.round(report.getDouble("measuredFps")) + " fps";
                    }
                    else {
                        PhotoResult photo = exportPhoto(captureDirectory, report, photoFormat, fileFormat, caption, captureGrade, true);
                        message = photo.message();
                        bitmap = photo.thumbnail();
                    }
                }
            } catch (Exception failure) {
                android.util.Log.w("CameraX9P", "Capture output preparation failed", failure);
                message = getString(R.string.export_failure);
            } finally {
                CaptureFiles.finish(this, config);
            }
            Bitmap display=bitmap;
            String outcome = message;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                busy = false;
                capturing = false;
                burstStop = null;
                recordingStop = null;
                recordingPaused = null;
                recordingCameraSwitch = null;
                if (keepAwake) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                if (stop != null) setRequestedOrientation(previousOrientation);
                status.setText(outcome);
                status.setVisibility(View.VISIBLE);
                if (display != null) {
                    original = display;
                }
                if (burst) original = null;
                refreshGalleryPreview(captureGrade == null ? display : null);
                updateControls();
                refreshPreview();
                offerCaptureRecovery();
            });
        });
    }

    private record PhotoResult(String message, Bitmap thumbnail) {}

    private PhotoResult exportPhoto(File directory, JSONObject report, int photoFormat, int fileFormat, String caption,
            LutGrade grade, boolean single) throws IOException, org.json.JSONException {
        File source = new File(directory, "capture-test.jpg");
        File reframed = null;
        try {
            File output = source;
            int rotation = report.getInt("rotation");
            boolean graded = false;
            if (fileFormat != FILE_RAW && (photoFormat != 0 || rotation != 0 || grade != null)) {
                PhotoGeometry.Crop crop = photoCrop(report, photoFormat);
                reframed = File.createTempFile("reframed-", ".jpg", directory);
                if (single && grade != null) showColorExport(grade);
                try {
                    PhotoExport.reframe(source, reframed, crop, rotation, caption, grade);
                    graded = grade != null;
                } catch (IOException | RuntimeException failed) {
                    if (grade == null) throw failed;
                    android.util.Log.w("CameraX9P", "Photo LUT export failed", failed);
                    PhotoExport.reframe(source, reframed, crop, rotation, caption, null);
                }
                PhotoExport.preserveExif(source, reframed, report);
                output = reframed;
            }
            File raw = fileFormat == FILE_JPEG ? null : new File(directory, "capture-test.dng");
            File jpeg = fileFormat == FILE_RAW ? null : output;
            String dimensions;
            try {
                dimensions = PhotoExport.publish(getContentResolver(), jpeg, raw, photoFormat == PHOTO_XPAN);
            } catch (IOException | RuntimeException publication) {
                CaptureRecovery.store(this, jpeg, raw, null, null, photoFormat == PHOTO_XPAN);
                throw publication;
            }
            String message;
            if (fileFormat == FILE_RAW) {
                JSONObject dng = report.getJSONObject("raw");
                message = getString(R.string.raw_saved, dng.getInt("width"), dng.getInt("height"));
            } else if (fileFormat == FILE_BOTH) {
                message = getString(R.string.raw_jpeg_saved, dimensions);
            } else {
                message = getString(R.string.jpeg_saved, dimensions);
            }
            if (grade != null) message = getString(graded ? R.string.grade_applied : R.string.grade_skipped,
                message, grade.label(this));
            return new PhotoResult(message, single ? thumbnail(graded ? source : output) : null);
        } finally {
            if (reframed != null) reframed.delete();
        }
    }

    private static PhotoGeometry.Crop photoCrop(JSONObject report, int photoFormat) throws org.json.JSONException {
        JSONObject image = report.getJSONObject("image");
        int width = image.getInt("width"), height = image.getInt("height");
        return switch (photoFormat) {
            case PHOTO_XPAN -> PhotoGeometry.xpan(width, height);
            case PHOTO_WIDE -> PhotoGeometry.centeredCrop(width, height, 16, 9);
            default -> PhotoGeometry.centeredCrop(width, height, width, height);
        };
    }

    private void captureBracket() {
        ActionExposure measured = livePreview.exposure();
        Float focus = supportsManualFocus() ? (manualFocus != null ? manualFocus : livePreview.measuredFocus()) : null;
        if (measured == null || (supportsManualFocus() && focus == null)) {
            status.setText(getString(R.string.exposure_and_focus_wait));
            status.setVisibility(View.VISIBLE);
            return;
        }
        CameraAdjustments base = currentAdjustments();
        long baseTime = base.shutterNs() > 0 ? base.shutterNs() : measured.timeNs();
        int baseIso = base.iso() > 0 ? base.iso() : measured.iso();
        List<ExposureBracket> shots = ExposureBracket.plan(currentCamera, baseTime, baseIso);
        if (shots.isEmpty()) {
            status.setText(getString(R.string.bracket_sensor_limits));
            status.setVisibility(View.VISIBLE);
            return;
        }
        int format = effectivePhotoFormat();
        String caption = isPolaroid() ? polaroidCaption : null;
        LutGrade grade = exportGrade();
        Intent config = new Intent().putExtra("camera", CAMERA_IDS[lensIndex]).putExtra("width", 4096)
            .putExtra("height", 3072).putExtra("displayRotation", LivePreview.displayRotation(texture))
            .putExtra("manualRotation", manualRotation).putExtra("zoomRatio", zoomRatio);
        if (focus != null) config.putExtra("manualFocus", focus);
        CaptureFiles.begin(config);
        File captureDirectory = CaptureFiles.directory(this, config);
        reviewing = false;
        capturing = true;
        busy = true;
        focusMagnified = false;
        livePreview.stop();
        clearFocusUi();
        updateControls();
        status.setVisibility(View.VISIBLE);
        status.setText(getString(R.string.bracket_progress, 0));
        worker.execute(() -> {
            List<File> exports = new java.util.ArrayList<>(3);
            org.json.JSONArray results = new org.json.JSONArray();
            String outcome;
            try {
                File source = new File(captureDirectory, "capture-test.jpg");
                for (ExposureBracket shot : shots) {
                    new CameraAdjustments(0, base.contrast(), shot.iso(), shot.timeNs(), CameraAdjustments.OFF).writeTo(config);
                    JSONObject report = new JSONObject(CaptureProbe.run(this, config));
                    if (!report.optBoolean("success")) throw new IOException(report.optString("error", getString(R.string.shot_failed)));
                    report.put("bracketEv", shot.ev());
                    results.put(report);
                    File jpeg = File.createTempFile("bracket-", ".jpg", captureDirectory);
                    exports.add(jpeg);
                    int rotation = report.getInt("rotation");
                    if (format == 0 && rotation == 0 && grade == null) {
                        java.nio.file.Files.copy(source.toPath(), jpeg.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        PhotoExport.preserveExif(source, jpeg, report);
                    } else {
                        PhotoExport.reframe(source, jpeg, photoCrop(report, format), rotation, caption, grade);
                        PhotoExport.preserveExif(source, jpeg, report);
                    }
                    int progress = exports.size();
                    runOnUiThread(() -> { if (!isDestroyed()) status.setText(getString(R.string.bracket_progress, progress)); });
                }
                try {
                    PhotoExport.publishBracket(getContentResolver(), exports);
                } catch (IOException | RuntimeException publication) {
                    CaptureRecovery.store(this, null, null, null, exports, false);
                    throw publication;
                }
                outcome = getString(R.string.bracket_saved);
            } catch (Exception failure) {
                outcome = getString(R.string.bracket_failed);
                android.util.Log.w("CameraX9P", outcome, failure);
            } finally {
                CaptureFiles.finish(this, config);
            }
            try {
                java.nio.file.Files.write(new File(getFilesDir(), "bracket-result.json").toPath(),
                    results.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (IOException | org.json.JSONException failure) {
                android.util.Log.w("CameraX9P", "Bracketing report unavailable", failure);
            }
            String message = outcome;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                busy = false;
                capturing = false;
                if (results.length() == 3) original = null;
                status.setText(message);
                refreshGalleryPreview(null);
                updateControls();
                refreshPreview();
                offerCaptureRecovery();
            });
        });
    }

    private void offerCaptureRecovery() {
        if (!resumed || busy || isDestroyed() || recoveryDialog != null) return;
        File[] pending = CaptureRecovery.pending(this);
        if (pending.length == 0) return;
        recoveryDialog = new AlertDialog.Builder(this).setTitle(R.string.export_failure)
            .setMessage(R.string.capture_recovery_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.retry_gallery_export, (dialog, which) -> worker.execute(() -> {
                boolean saved = true;
                try {
                    for (File entry : pending) CaptureRecovery.retry(this, entry);
                } catch (IOException | RuntimeException failure) {
                    android.util.Log.w("CameraX9P", "Gallery export retry failed", failure);
                    saved = false;
                }
                boolean result = saved;
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    refreshGalleryPreview(null);
                    status.setText(result ? R.string.gallery_export_recovered : R.string.export_failure);
                    status.setVisibility(View.VISIBLE);
                });
            })).create();
        recoveryDialog.setOnDismissListener(dialog -> recoveryDialog = null);
        showGlassDialog(recoveryDialog);
    }
    private Bitmap thumbnail(File file) {
        BitmapFactory.Options bounds=new BitmapFactory.Options(); bounds.inJustDecodeBounds=true; BitmapFactory.decodeFile(file.getPath(),bounds);
        BitmapFactory.Options options=new BitmapFactory.Options(); options.inSampleSize=1;
        while(Math.max(bounds.outWidth,bounds.outHeight)/options.inSampleSize>640) options.inSampleSize*=2;
        return BitmapFactory.decodeFile(file.getPath(),options);
    }
    private void render() {
        if (original == null || !reviewing) return;
        Bitmap base = original;
        float amount = intensity / 100f;
        LutGrade grade = monochrome || lut != null && amount > 0 ? new LutGrade(lut, amount, monochrome) : null;
        int revision = ++previewRevision;
        worker.execute(() -> {
            try {
                Bitmap output = base.copy(Bitmap.Config.ARGB_8888, true);
                if (grade != null) grade.apply(output);
                runOnUiThread(() -> {
                    if (!isDestroyed() && reviewing && revision == previewRevision) image.setImageBitmap(output);
                    else output.recycle();
                });
            } catch (RuntimeException failure) {
                runOnUiThread(() -> {
                    if (!isDestroyed() && reviewing && revision == previewRevision) status.setText(getString(R.string.lut_preview_unavailable));
                });
            }
        });
    }
    private LutGrade exportGrade() {
        CubeLut exportLut = saveLut && intensity > 0 ? lut : null;
        return monochrome || exportLut != null ? new LutGrade(exportLut, intensity / 100f, monochrome) : null;
    }

    private void updateMonochromePreview() {
        Paint filter = null;
        if (monochrome) {
            ColorMatrix matrix = new ColorMatrix();
            matrix.setSaturation(0);
            filter = new Paint();
            filter.setColorFilter(new ColorMatrixColorFilter(matrix));
        }
        texture.setLayerPaint(filter);
    }

    private void showColorExport(LutGrade grade) {
        runOnUiThread(() -> {
            if (isDestroyed()) return;
            status.setText(getString(R.string.grading, grade.label(this)));
            status.setVisibility(View.VISIBLE);
        });
    }

    private void saveColorSettings() {
        getSharedPreferences("color", MODE_PRIVATE).edit()
            .putString("lutName", savedLutName)
            .putInt("intensity", intensity).putBoolean("saveLut", saveLut)
            .putBoolean("monochrome", monochrome).apply();
    }

    private void releaseLutPermission(Uri uri) {
        if (uri == null) return;
        try {
            getContentResolver().releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) { }
    }

    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request!=30 || result!=RESULT_OK || data==null || data.getData()==null) return;
        loadLut(data.getData(), true);
    }

    private File lutFile() { return new File(getFilesDir(), "active-lut.cube"); }

    private void cancelLutImport() {
        LutImport previous = lutImport;
        lutImport = null;
        if (previous == null) return;
        lutHandler.removeCallbacks(previous.timeout);
        previous.task.cancel(true);
        new Thread(previous.cancellation::cancel, "cancel-lut-provider").start();
    }

    private void loadLut(Uri uri, boolean imported) {
        cancelLutImport();
        LutImport job = new LutImport();
        lutImport = job;
        if (imported) {
            status.setText(getString(R.string.lut_reading));
            status.setVisibility(View.VISIBLE);
        }
        job.timeout = () -> {
            cancelLutImport();
            showLutFailure(imported);
        };
        Runnable read = () -> {
            File staged = null;
            boolean handedOff = false;
            try {
                if (uri != null) {
                    staged = File.createTempFile("incoming-lut-", ".cube", getCacheDir());
                    copyLut(uri, staged, job.cancellation);
                }
                CubeLut loaded;
                try (InputStream in = new java.io.FileInputStream(staged == null ? lutFile() : staged)) {
                    loaded = CubeLut.read(new InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
                }
                File validated = staged;
                String name = lutDisplayName(uri, job.cancellation);
                runOnUiThread(() -> applyLut(job, validated, loaded, name, imported));
                handedOff = true;
            } catch (Exception badLut) {
                android.util.Log.w("CameraX9P", "LUT import failed", badLut);
                runOnUiThread(() -> {
                    if (!finishLutImport(job)) return;
                    if (uri == null) {
                        lutFile().delete();
                        savedLutName = null;
                        saveColorSettings();
                    }
                    showLutFailure(imported);
                });
            } finally {
                if (!handedOff && staged != null) staged.delete();
                if (uri != null && !imported) releaseLutPermission(uri);
            }
        };
        try {
            job.task = LUT_IO.submit(read);
        } catch (java.util.concurrent.RejectedExecutionException providerStillBusy) {
            lutImport = null;
            showLutFailure(imported);
            return;
        }
        lutHandler.postDelayed(job.timeout, 15_000);
    }

    private String lutDisplayName(Uri uri, android.os.CancellationSignal cancellation) {
        if (uri == null) return null;
        String column = android.provider.OpenableColumns.DISPLAY_NAME;
        try (android.database.Cursor cursor = getContentResolver().query(uri, new String[]{column},
                null, null, null, cancellation)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
        } catch (RuntimeException ignored) { }
        return "LUT .cube";
    }

    private void copyLut(Uri uri, File output, android.os.CancellationSignal cancellation) throws IOException {
        try (android.content.res.AssetFileDescriptor descriptor =
                 getContentResolver().openAssetFileDescriptor(uri, "r", cancellation)) {
            if (descriptor == null) throw new IOException(getString(R.string.unreadable_file));
            try (InputStream in = descriptor.createInputStream();
                 java.io.OutputStream out = new java.io.FileOutputStream(output)) {
                cancellation.setOnCancelListener(() -> {
                    try { in.close(); } catch (IOException ignored) { }
                });
                try {
                    byte[] buffer = new byte[8192];
                    long total = 0;
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        cancellation.throwIfCanceled();
                        total += count;
                        if (total > 32L * 1024 * 1024) throw new IOException("LUT file too large");
                        out.write(buffer, 0, count);
                    }
                } finally {
                    cancellation.setOnCancelListener(null);
                }
            }
        }
    }

    private boolean finishLutImport(LutImport job) {
        if (lutImport != job || isDestroyed()) return false;
        lutImport = null;
        lutHandler.removeCallbacks(job.timeout);
        return true;
    }

    private void applyLut(LutImport job, File staged, CubeLut loaded, String name, boolean imported) {
        try {
            if (!finishLutImport(job)) return;
            if (staged != null) java.nio.file.Files.move(staged.toPath(), lutFile().toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            lut = loaded;
            lutName = name == null ? savedLutName : name;
            savedLutName = lutName;
            saveColorSettings();
            if (imported && !busy) {
                status.setText(getString(R.string.lut_loaded,
                    getString(saveLut ? R.string.saved_output_active : R.string.preview_only)));
                if (reviewing) render();
                else if (original != null || (galleryPreview != null && galleryPreview.bitmap() != null)) toggleReview();
            }
            updateControls();
        } catch (IOException failure) {
            android.util.Log.w("CameraX9P", "LUT could not be saved", failure);
            showLutFailure(imported);
        } finally {
            if (staged != null) staged.delete();
        }
    }

    private void showLutFailure(boolean imported) {
        if (!imported || isDestroyed() || busy) return;
        status.setVisibility(View.VISIBLE);
        status.setText(getString(R.string.lut_failed));
        updateControls();
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(request,permissions,results);
        if (request == 2) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) capture();
            else status.setText(getString(R.string.mic_permission));
            return;
        }
        if (request != 1) return;
        boolean shouldCapture = retryCapture;
        retryCapture = false;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED && shouldCapture) capture();
        else refreshPreview();
    }
    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        refreshGalleryPreview(null);
        refreshMicrophones();
        audioManager.registerAudioDeviceCallback(audioDevices, new android.os.Handler(getMainLooper()));
        refreshRecordingDot();
        refreshCameraSwitchButton();
        refreshScreenFlash();
        refreshPreview();
        offerCaptureRecovery();
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused) return;
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsets.Type.systemBars());
        }
    }

    private void setRecordingIndicator(boolean active) {
        hideZoomPanel();
        if (isDestroyed()) return;
        if (active && !cleanRecording) recordingStartedAt = android.os.SystemClock.elapsedRealtime();
        if (!active) recordingStartedAt = 0;
        cleanRecording = active;
        controls.setVisibility(active ? View.INVISIBLE : View.VISIBLE);
        updateViewfinderArea();
        updateZebra();
        updateLevel();
        refreshRecordingDot();
        refreshCameraSwitchButton();
        refreshScreenFlash();
    }

    private void refreshRecordingDot() {
        if (recordPulse != null) {
            recordPulse.cancel();
            recordPulse = null;
        }
        boolean visible = cleanRecording && resumed;
        recordDot.setVisibility(visible ? View.VISIBLE : View.GONE);
        recordTimer.removeCallbacks(updateRecordingTimer);
        recordTimer.setVisibility(visible ? View.VISIBLE : View.GONE);
        stopButton.setVisibility(cleanRecording && resumed && recordingStop != null ? View.VISIBLE : View.GONE);
        stopButton.setEnabled(recordingStop != null && !recordingStop.get());
        if (!cleanRecording || stopButton.isEnabled()) {
            stopButton.setText("■");
            stopButton.setContentDescription(getString(R.string.stop_recording));
        }
        if (recordDot.getVisibility() != View.VISIBLE) return;
        positionRecordingDot();
        updateRecordingTimer.run();
        recordPulse = ObjectAnimator.ofFloat(recordDot, View.ALPHA, 1f, .18f);
        recordPulse.setDuration(550);
        recordPulse.setRepeatMode(ValueAnimator.REVERSE);
        recordPulse.setRepeatCount(ValueAnimator.INFINITE);
        recordPulse.start();
    }

    private void positionRecordingDot() {
        if (recordDot == null || framing.isEmpty()) return;
        int right = Math.min(framing.right, controls.getWidth() - controls.getPaddingRight());
        int top = Math.max(framing.top, controls.getPaddingTop());
        recordDot.setTranslationX(Math.max(framing.left, right - dp(25)));
        recordDot.setTranslationY(top + dp(14));
        recordTimer.setTranslationX(Math.max(framing.left, right - dp(32) - recordTimer.getLayoutParams().width));
        recordTimer.setTranslationY(top + dp(5));
    }

    private final Runnable updateRecordingTimer = new Runnable() {
        @Override public void run() {
            if (recordingStartedAt == 0 || recordTimer.getVisibility() != View.VISIBLE) return;
            long elapsed = android.os.SystemClock.elapsedRealtime() - recordingStartedAt;
            long seconds = elapsed / 1000;
            long hours = seconds / 3600;
            String time = hours > 0
                ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, seconds / 60 % 60, seconds % 60)
                : String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
            recordTimer.setText(time);
            recordTimer.postDelayed(this, 1000 - elapsed % 1000);
        }
    };

    @Override protected void onPause() {
        if (recordingPaused != null) recordingPaused.set(true);
        hideZoomPanel();
        cancelSelfTimer();
        shutterHeld = false;
        if (burstStop != null) burstStop.set(true);
        resumed = false;
        refreshScreenFlash();
        updateZebra();
        updateLevel();
        audioManager.unregisterAudioDeviceCallback(audioDevices);
        if (recordingStop != null) recordingStop.set(true);
        refreshRecordingDot();
        if (dropdown != null) dropdown.dismiss();
        livePreview.stop();
        clearFocusUi();
        super.onPause();
    }
    @Override protected void onDestroy() {
        cancelLutImport();
        worker.shutdown();
        super.onDestroy();
    }
}
