package dev.melee;

import org.libsdl.app.SDLActivity;

public class MeleeActivity extends SDLActivity {

    private static final String ANDROID_TUNING_PREFS = "melee_android_tuning";
    private static final String ANDROID_TUNING_VERSION_KEY = "version";
    private static final int ANDROID_TUNING_VERSION = 3;

    @Override
    protected String[] getLibraries() {
        return new String[] {
            "png16",
            "melee"
        };
    }

    @Override
    protected String getMainFunction() {
        return "SDL_main";
    }

    @Override
    public org.libsdl.app.SDLSurface createSDLSurface(android.content.Context context) {
        return new dev.encounter.aurora.AuroraSurface(context);
    }

    private TouchOverlayView mTouchOverlay;
    private android.os.Handler mTouchOverlayHandler;

    private final Runnable mTouchOverlayPoll = new Runnable() {
        @Override
        public void run() {
            if (mTouchOverlayHandler == null || isFinishing() || isDestroyed()) {
                return;
            }

            boolean gameplayActive = false;
            try {
                gameplayActive = TouchControls.nativeIsGameplayActive();
            } catch (UnsatisfiedLinkError ignored) {
                // SDL may still be loading the native library. Try again shortly.
            }

            if (gameplayActive) {
                attachTouchOverlay();
                return;
            }

            // Keep the Android View completely absent while the native launcher
            // is visible, so Choose disc, Settings, sliders, etc. receive their
            // touches directly instead of the floating stick stealing them.
            mTouchOverlayHandler.postDelayed(this, 100);
        }
    };

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        // Do this before SDL can start the native launcher so existing installs
        // also pick up the Android-specific performance/readability defaults.
        migrateAndroidTuning();
        super.onCreate(savedInstanceState);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyImmersiveMode();

        // Do NOT attach the touch controller here. The launcher must remain a
        // normal touch UI. We attach it only after native code starts polling
        // GameCube PAD input, which means Melee itself is running.
        mTouchOverlayHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        scheduleTouchOverlay();

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(android.content.Context.POWER_SERVICE);
            if (pm != null && pm.isSustainedPerformanceModeSupported()) {
                getWindow().setSustainedPerformanceMode(true);
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.preferredRefreshRate = 60.0f;
            getWindow().setAttributes(lp);
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] {
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, 100);
            }
        }
    }

    private void scheduleTouchOverlay() {
        if (mTouchOverlay != null || mTouchOverlayHandler == null) {
            return;
        }
        mTouchOverlayHandler.removeCallbacks(mTouchOverlayPoll);
        mTouchOverlayHandler.post(mTouchOverlayPoll);
    }

    private void attachTouchOverlay() {
        if (mTouchOverlay != null || mLayout == null) {
            return;
        }
        mTouchOverlay = new TouchOverlayView(this);
        mLayout.addView(mTouchOverlay, new android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        ));
        mTouchOverlay.updateControllerState();
    }

    private void migrateAndroidTuning() {
        try {
            android.content.SharedPreferences tuning = getSharedPreferences(
                ANDROID_TUNING_PREFS, android.content.Context.MODE_PRIVATE);
            int previousVersion = tuning.getInt(ANDROID_TUNING_VERSION_KEY, 0);
            if (previousVersion >= ANDROID_TUNING_VERSION) {
                return;
            }

            // v2 forced the touch scale to 1.16, which is much too large on
            // high-density phones. Undo that injected value. Fresh installs
            // start slightly smaller as well; later user changes are preserved.
            android.content.SharedPreferences touch = getSharedPreferences(
                "melee_touch_controls", android.content.Context.MODE_PRIVATE);
            boolean hadTouchScale = touch.contains("scale");
            float touchScale = touch.getFloat("scale", 1.0f);
            boolean wasV2InjectedScale = previousVersion == 2
                && touchScale >= 1.14f && touchScale <= 1.18f;
            if (!hadTouchScale || !Float.isFinite(touchScale) || wasV2InjectedScale) {
                touch.edit().putFloat("scale", 0.90f).apply();
            }

            // SDL_GetPrefPath can resolve to either the files root or an app
            // subdirectory depending on the SDL Android glue version. Migrate
            // both possible launcher.cfg locations without touching the disc
            // path or any unrelated setting.
            migrateLauncherConfig(new java.io.File(getFilesDir(), "launcher.cfg"));
            migrateLauncherConfig(
                new java.io.File(new java.io.File(getFilesDir(), "melee-pc"), "launcher.cfg"));

            tuning.edit().putInt(ANDROID_TUNING_VERSION_KEY, ANDROID_TUNING_VERSION).apply();
        } catch (Exception ignored) {
            // Native defaults still cover clean installs; never block startup
            // because an old preference file could not be migrated.
        }
    }

    private void migrateLauncherConfig(java.io.File config) {
        if (!config.isFile()) {
            return;
        }
        try {
            java.nio.file.Path path = config.toPath();
            java.util.List<String> lines = java.nio.file.Files.readAllLines(
                path, java.nio.charset.StandardCharsets.UTF_8);
            boolean sawVsync = false;
            boolean sawScale = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String trimmed = line.trim();
                if (trimmed.startsWith("vsync ")) {
                    lines.set(i, "vsync 0");
                    sawVsync = true;
                } else if (trimmed.startsWith("scale ")) {
                    float scale = 1.0f;
                    try {
                        scale = Float.parseFloat(trimmed.substring(6).trim());
                    } catch (NumberFormatException ignored) {
                    }
                    if (!Float.isFinite(scale) || scale < 1.50f) {
                        lines.set(i, "scale 1.50");
                    }
                    sawScale = true;
                }
            }
            if (!sawVsync) {
                lines.add("vsync 0");
            }
            if (!sawScale) {
                lines.add("scale 1.50");
            }
            java.nio.file.Files.write(
                path,
                lines,
                java.nio.charset.StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                java.nio.file.StandardOpenOption.WRITE);
        } catch (Exception ignored) {
            // Keep launch resilient if an old config is temporarily unreadable.
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersiveMode();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.preferredRefreshRate = 60.0f;
            getWindow().setAttributes(lp);
        }
        if (mTouchOverlay != null) {
            mTouchOverlay.updateControllerState();
        } else {
            scheduleTouchOverlay();
        }
    }

    @Override
    protected void onDestroy() {
        if (mTouchOverlayHandler != null) {
            mTouchOverlayHandler.removeCallbacks(mTouchOverlayPoll);
        }
        try {
            TouchControls.nativeSetTouchActive(false);
        } catch (UnsatisfiedLinkError ignored) {
        }
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersiveMode();
        }
    }

    private void applyImmersiveMode() {
        android.view.Window window = getWindow();
        if (window == null) {
            return;
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(android.view.WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                android.view.WindowManager.LayoutParams lp = window.getAttributes();
                lp.layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
                window.setAttributes(lp);
            }
        } else {
            int flags = android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
            window.getDecorView().setSystemUiVisibility(flags);
        }
    }

    @Override
    protected String[] getArguments() {
        android.content.Intent intent = getIntent();
        if (intent != null) {
            String[] args = intent.getStringArrayExtra("args");
            if (args != null && args.length > 0) {
                return args;
            }
            String disc = intent.getStringExtra("disc");
            if (disc != null && !disc.isEmpty()) {
                return new String[] { "--dvd", disc };
            }
            android.net.Uri data = intent.getData();
            if (data != null) {
                try {
                    getContentResolver().takePersistableUriPermission(
                        data, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {
                }
                return new String[] { "--dvd", data.toString() };
            }
        }
        return new String[0];
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (mSurface != null) {
            handleKeyEvent(mSurface, event.getKeyCode(), event, null);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
