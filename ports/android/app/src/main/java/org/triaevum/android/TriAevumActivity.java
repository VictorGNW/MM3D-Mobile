package org.triaevum.android;

import org.libsdl.app.SDLActivity;
import org.libsdl.app.SDLSurface;
import org.json.JSONObject;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Bundle;
import android.os.Build;
import android.util.Log;
import android.view.InputDevice;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.RelativeLayout;

/** SDL owns Surface/lifecycle. Game, renderer and title loading retain their native owners. */
public final class TriAevumActivity extends SDLActivity implements InputManager.InputDeviceListener {
    private Mm3dTouchControls touchControls;
    private InputManager inputManager;
    private boolean touchEnabled;
    private boolean touchSettingsOpen;
    private boolean touchTitleScreen;
    private boolean physicalControllerConnected;

    @Override protected SDLSurface createSDLSurface(Context context) {
        int maximumShortEdge = 720;
        File config = new File(getExternalFilesDir(null), "TriAevum.android.host.json");
        if (config.isFile()) {
            try {
                maximumShortEdge = new JSONObject(new String(Files.readAllBytes(config.toPath()), StandardCharsets.UTF_8))
                    .getInt("maximum_surface_short_edge");
                if (maximumShortEdge < 0) throw new IllegalArgumentException("Negative surface limit");
            } catch (Exception error) {
                Log.w("TriAevum", "Invalid Android host config; using 720p surface limit", error);
                maximumShortEdge = 720;
            }
        }
        return new TriAevumSurface(context, maximumShortEdge);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        SDLActivity.setWindowStyle(true);
        hideSystemBars();
        if (!mBrokenLibraries) {
            touchControls = new Mm3dTouchControls(this);
            touchControls.attachTo((RelativeLayout) mLayout);
            inputManager = getSystemService(InputManager.class);
        }
    }

    @Override protected void onStart() {
        super.onStart();
        if (inputManager != null) {
            inputManager.registerInputDeviceListener(this, null);
            refreshControllers();
        }
    }

    @Override protected void onStop() {
        if (inputManager != null) inputManager.unregisterInputDeviceListener(this);
        super.onStop();
    }

    @Override public void onInputDeviceAdded(int deviceId) { refreshControllers(); }
    @Override public void onInputDeviceRemoved(int deviceId) { refreshControllers(); }
    @Override public void onInputDeviceChanged(int deviceId) { refreshControllers(); }

    private void refreshControllers() {
        boolean connected = false;
        for (int id : inputManager.getInputDeviceIds()) {
            InputDevice device = inputManager.getInputDevice(id);
            if (device != null && !device.isVirtual()
                    && (device.supportsSource(InputDevice.SOURCE_GAMEPAD)
                        || device.supportsSource(InputDevice.SOURCE_JOYSTICK))) {
                connected = true;
                break;
            }
        }
        if (physicalControllerConnected != connected) {
            physicalControllerConnected = connected;
            Log.i("TriAevum", connected ? "Physical controller connected" : "No physical controller connected");
            applyTouchMode();
        }
    }

    private void applyTouchMode() {
        if (touchControls == null) return;
        boolean visible = touchEnabled && !physicalControllerConnected;
        ((TriAevumSurface) mSurface).setTouchOverlayVisible(visible);
        touchControls.setMode(visible, touchSettingsOpen, touchTitleScreen);
    }

    // Called by the game thread when the active title or settings visibility changes.
    public void updateMm3dTouchControls(boolean enabled, boolean settingsOpen, boolean titleScreen) {
        runOnUiThread(() -> {
            touchEnabled = enabled;
            touchSettingsOpen = settingsOpen;
            touchTitleScreen = titleScreen;
            applyTouchMode();
        });
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        if (!hasFocus && touchControls != null) touchControls.releaseInput();
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.systemBars());
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override public void onBackPressed() {
        if (touchControls == null || !touchControls.closeEditor(false)) super.onBackPressed();
    }

    @Override protected void onPause() {
        if (touchControls != null) touchControls.releaseInput();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (touchControls != null) {
            touchControls.releaseInput();
            touchControls = null;
        }
        super.onDestroy();
    }

    @Override protected String[] getLibraries() {
        return new String[] { "SDL2", "triaevum_title_bootstrap", "TriAevum" };
    }

    @Override protected String[] getArguments() {
        File local = Mm3dLocalBuild.readyData(this);
        if (local != null) {
            return new String[] {
                "--launch-profile", new File(local, Mm3dLocalBuild.PROFILE).getAbsolutePath(),
                "--title-plugin", new File(local, Mm3dLocalBuild.MODULE).getAbsolutePath()
            };
        }
        File root = getExternalFilesDir(null);
        return new String[] {
            "--launch-profile", new File(root, "TriAevum.android.launch.json").getAbsolutePath(),
            "--title-plugin", new File(getApplicationInfo().nativeLibraryDir,
                "libtriaevum_title_aot.so").getAbsolutePath()
        };
    }
}
