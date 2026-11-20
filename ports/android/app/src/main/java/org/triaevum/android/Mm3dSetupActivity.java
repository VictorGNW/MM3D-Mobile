package org.triaevum.android;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Mm3dSetupActivity extends Activity {
    private static final int PICK_ROM = 1;
    private static final long MAX_ROM_SIZE = 2L * 1024 * 1024 * 1024;
    private static final long MM3D_USA_TITLE_ID = 0x0004000000125500L;
    private static final Pattern COMPILE_COUNT = Pattern.compile("^\\[(\\d+)/(\\d+)] ");
    private static final Pattern BUILD_STAGE = Pattern.compile("^== \\[\\d{2}:\\d{2}:\\d{2}] (.+)$");
    // Initial estimates in minutes, based on the first full build on a phone.
    private enum Phase {
        TOOLS(0, 1), EXTRACT(1, 1), TRANSLATE(3, 6), GENERATE(8, 3),
        DATA(12, 1), COMPILE(14, 52), LINK(98, 1);

        final int percent;
        final long millis;

        Phase(int percent, int minutes) {
            this.percent = percent;
            this.millis = minutes * 60_000L;
        }
    }
    private Button select;
    private Button compile;
    private Button cancel;
    private Path activeCancel;
    private TextView status;
    private TextView estimate;
    private ProgressBar progress;
    private Phase phase;
    private long phaseStarted;
    private int compiled;
    private int totalSources;
    private final Runnable refreshEstimate = new Runnable() {
        @Override public void run() {
            if (phase == null) return;
            updateEstimate();
            estimate.postDelayed(this, 1_000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        File external = getExternalFilesDir(null);
        File title = new File(getApplicationInfo().nativeLibraryDir, "libtriaevum_title_aot.so");
        if (Mm3dLocalBuild.readyData(this) != null || (title.isFile() && external != null
                && new File(external, Mm3dLocalBuild.PROFILE).isFile())) {
            launchGame();
            return;
        }

        LinearLayout layout = new LinearLayout(this);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        int minimumTop = Math.round(64 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, minimumTop, padding, padding);
        layout.setOnApplyWindowInsetsListener((view, insets) -> {
            var cutout = insets.getDisplayCutout();
            int top = cutout == null ? 0 : cutout.getSafeInsetTop();
            int left = cutout == null ? 0 : cutout.getSafeInsetLeft();
            int right = cutout == null ? 0 : cutout.getSafeInsetRight();
            int bottom = cutout == null ? 0 : cutout.getSafeInsetBottom();
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                var safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = Math.max(top, safe.top);
                left = Math.max(left, safe.left);
                right = Math.max(right, safe.right);
                bottom = Math.max(bottom, safe.bottom);
            }
            view.setPadding(padding + left, Math.max(minimumTop, padding + top),
                padding + right, padding + bottom);
            return insets;
        });
        layout.setOrientation(LinearLayout.VERTICAL);
        status = new TextView(this);
        boolean toolsAvailable = Mm3dLocalBuild.available(this);
        status.setText(importedRom().isFile()
            ? (toolsAvailable ? "ROM imported. Ready to compile." : "ROM imported. This APK has no compiler.")
            : "Select a decrypted Majora’s Mask 3D USA v1.1 ROM (.3ds or .cci).");
        layout.addView(status);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setVisibility(View.GONE);
        layout.addView(progress);
        estimate = new TextView(this);
        estimate.setVisibility(View.GONE);
        layout.addView(estimate);
        select = new Button(this);
        select.setText("Select ROM");
        select.setOnClickListener(view -> {
            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            picker.addCategory(Intent.CATEGORY_OPENABLE);
            picker.setType("*/*");
            startActivityForResult(picker, PICK_ROM);
        });
        layout.addView(select);
        if (toolsAvailable) {
            compile = new Button(this);
            compile.setText("Compile game");
            compile.setEnabled(importedRom().isFile());
            compile.setOnClickListener(view -> startBuild());
            layout.addView(compile);
            cancel = new Button(this);
            cancel.setText("Cancel build");
            cancel.setVisibility(View.GONE);
            cancel.setOnClickListener(view -> cancelBuild());
            layout.addView(cancel);
        }
        setContentView(layout);
        layout.requestApplyInsets();
    }

    private void launchGame() {
        startActivity(new Intent(this, TriAevumActivity.class));
        finish();
    }

    private void setBusy(boolean busy) {
        select.setEnabled(!busy);
        if (compile != null) compile.setEnabled(!busy && importedRom().isFile());
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        progress.setIndeterminate(phase == null);
        if (!busy) stopEstimate();
    }

    private void stopEstimate() {
        phase = null;
        estimate.removeCallbacks(refreshEstimate);
        estimate.setVisibility(View.GONE);
    }

    @Override protected void onDestroy() {
        if (estimate != null) stopEstimate();
        super.onDestroy();
    }

    private void startPhase(Phase next) {
        if (phase == next) return;
        phase = next;
        phaseStarted = SystemClock.elapsedRealtime();
        updateEstimate();
    }

    private void updateEstimate() {
        Phase[] phases = Phase.values();
        int position = phase.ordinal();
        int nextPercent = position + 1 < phases.length ? phases[position + 1].percent : 100;
        long elapsed = SystemClock.elapsedRealtime() - phaseStarted;
        long remaining = Math.max(phase.millis / 10, phase.millis - elapsed);
        double fraction = Math.min(0.95, (double) elapsed / phase.millis);
        if (phase == Phase.COMPILE) {
            fraction = totalSources == 0 ? 0 : (double) compiled / totalSources;
            if (compiled >= 5 && elapsed >= 30_000) {
                remaining = elapsed * (totalSources - compiled) / compiled;
            }
        }
        for (int i = position + 1; i < phases.length; i++) remaining += phases[i].millis;
        int percent = Math.min(99, phase.percent + (int) ((nextPercent - phase.percent) * fraction));
        int minutes = (int) Math.max(1, (remaining + 59_999) / 60_000);
        progress.setIndeterminate(false);
        progress.setMax(100);
        progress.setProgress(percent);
        estimate.setText(getString(R.string.mm3d_build_estimate, percent, minutes));
        estimate.setVisibility(View.VISIBLE);
    }

    private void showBuildProgress(String line) {
        Matcher count = COMPILE_COUNT.matcher(line);
        if (count.find()) {
            int done = Integer.parseInt(count.group(1));
            int total = Integer.parseInt(count.group(2));
            if (total <= 0 || done > total) return;
            compiled = done;
            totalSources = total;
            startPhase(Phase.COMPILE);
            updateEstimate();
            status.setText(getString(R.string.mm3d_compile_progress, done, total));
        } else {
            Matcher stage = BUILD_STAGE.matcher(line);
            if (stage.matches()) {
                String name = stage.group(1);
                if (name.startsWith("Extracting")) startPhase(Phase.EXTRACT);
                else if (name.startsWith("Translating") || name.startsWith("Selecting")) startPhase(Phase.TRANSLATE);
                else if (name.startsWith("Generating")) startPhase(Phase.GENERATE);
                else if (name.startsWith("Preparing game data")) startPhase(Phase.DATA);
                else if (name.startsWith("Compiling")) startPhase(Phase.COMPILE);
                else if (name.startsWith("Linking")) startPhase(Phase.LINK);
                status.setText(name);
            } else if (line.equals("Preparing compiler files…")) {
                status.setText(line);
            }
        }
    }

    private void startBuild() {
        if (activeCancel != null) return;
        Path cancelFile = getCacheDir().toPath().resolve("mm3d-cancel-" + UUID.randomUUID());
        activeCancel = cancelFile;
        setBusy(true);
        cancel.setEnabled(true);
        cancel.setVisibility(View.VISIBLE);
        compiled = 0;
        totalSources = 0;
        startPhase(Phase.TOOLS);
        estimate.removeCallbacks(refreshEstimate);
        estimate.postDelayed(refreshEstimate, 1_000);
        status.setText("Starting the build…");
        new Thread(() -> {
            try {
                boolean complete = Mm3dLocalBuild.run(getApplicationContext(), importedRom(), cancelFile, line ->
                    runOnUiThread(() -> {
                        if (!isDestroyed() && cancel.isEnabled()) showBuildProgress(line);
                    }));
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    if (complete) launchGame();
                    else finishBuildUi("Build canceled. You can resume the build.");
                });
            } catch (IOException | InterruptedException error) {
                Log.e("TriAevum", "MM3D build failed", error);
                runOnUiThread(() -> {
                    if (!isDestroyed()) {
                        finishBuildUi(error.getMessage());
                    }
                });
            } finally {
                try {
                    Files.deleteIfExists(cancelFile);
                } catch (IOException error) {
                    Log.w("TriAevum", "Could not remove the build cancel marker", error);
                }
            }
        }, "Mm3dBuild").start();
    }

    private void cancelBuild() {
        if (activeCancel == null) return;
        try {
            Files.createFile(activeCancel);
            cancel.setEnabled(false);
            estimate.removeCallbacks(refreshEstimate);
            estimate.setVisibility(View.GONE);
            status.setText("Canceling the build…");
        } catch (IOException error) {
            Log.w("TriAevum", "Could not request build cancellation", error);
            status.setText("Could not cancel. Try again.");
        }
    }

    private void finishBuildUi(String message) {
        cancel.setVisibility(View.GONE);
        activeCancel = null;
        setBusy(false);
        status.setText(message);
    }

    private File importedRom() {
        return new File(getFilesDir(), "mm3d-import.3ds");
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_ROM || result != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        setBusy(true);
        status.setText("Copying and checking the ROM…");
        new Thread(() -> {
            String errorMessage = null;
            try {
                importRom(uri);
            } catch (IOException | SecurityException error) {
                Log.w("TriAevum", "ROM import failed", error);
                errorMessage = "Could not import the ROM. " + error.getMessage();
            }
            String resultMessage = errorMessage;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (resultMessage == null && compile != null) {
                    startBuild();
                } else {
                    status.setText(resultMessage == null ? "ROM imported. This APK has no compiler." : resultMessage);
                    setBusy(false);
                }
            });
        }, "Mm3dRomImport").start();
    }

    private void importRom(Uri uri) throws IOException {
        File temporary = File.createTempFile("mm3d-import-", ".tmp", getFilesDir());
        try {
            try (InputStream input = getContentResolver().openInputStream(uri);
                 var output = Files.newOutputStream(temporary.toPath())) {
                if (input == null) throw new IOException("Could not open the selected file.");
                byte[] buffer = new byte[1024 * 1024];
                long size = 0;
                for (int count; (count = input.read(buffer)) != -1;) {
                    size += count;
                    if (size > MAX_ROM_SIZE) throw new IOException("The file exceeds 2 GiB.");
                    output.write(buffer, 0, count);
                }
            }
            validateHeader(temporary);
            Files.move(temporary.toPath(), importedRom().toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
    }

    private static void validateHeader(File rom) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(rom, "r")) {
            input.seek(0x100);
            if (input.readInt() != 0x4e435344) throw new IOException("Expected a .3ds or .cci image.");
            input.seek(0x120);
            long partition = Integer.toUnsignedLong(Integer.reverseBytes(input.readInt())) * 0x200;
            if (partition < 0x200 || partition + 0xA00 > input.length()) {
                throw new IOException("The ROM partition is incomplete.");
            }
            input.seek(partition + 0x100);
            if (input.readInt() != 0x4e434348) throw new IOException("Invalid game partition.");
            input.seek(partition + 0x118);
            if (Long.reverseBytes(input.readLong()) != MM3D_USA_TITLE_ID) {
                throw new IOException("Expected the USA version of Majora’s Mask 3D.");
            }
            input.seek(partition + 0x18f);
            if ((input.readUnsignedByte() & 4) == 0) {
                throw new IOException("The ROM is encrypted.");
            }
            input.seek(partition + 0x20e);
            if (Short.reverseBytes(input.readShort()) != 2) {
                throw new IOException("Expected the USA v1.1 update.");
            }
        }
    }
}
