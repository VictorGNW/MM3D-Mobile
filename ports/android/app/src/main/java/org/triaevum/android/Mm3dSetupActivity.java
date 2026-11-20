package org.triaevum.android;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public final class Mm3dSetupActivity extends Activity {
    private static final int PICK_ROM = 1;
    private static final long MAX_ROM_SIZE = 2L * 1024 * 1024 * 1024;
    private static final long MM3D_USA_TITLE_ID = 0x0004000000125500L;
    private Button select;
    private Button compile;
    private TextView status;

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
        layout.setPadding(padding, padding, padding, padding);
        layout.setOrientation(LinearLayout.VERTICAL);
        status = new TextView(this);
        boolean toolsAvailable = Mm3dLocalBuild.available(this);
        status.setText(importedRom().isFile()
            ? (toolsAvailable ? "ROM imported. Ready to compile." : "ROM imported. This APK has no compiler.")
            : "Select a decrypted Majora’s Mask 3D USA v1.1 ROM (.3ds or .cci).");
        layout.addView(status);
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
        }
        setContentView(layout);
    }

    private void launchGame() {
        startActivity(new Intent(this, TriAevumActivity.class));
        finish();
    }

    private void setBusy(boolean busy) {
        select.setEnabled(!busy);
        if (compile != null) compile.setEnabled(!busy && importedRom().isFile());
    }

    private void startBuild() {
        setBusy(true);
        status.setText("Starting the build…");
        new Thread(() -> {
            try {
                Mm3dLocalBuild.run(getApplicationContext(), importedRom(), line ->
                    runOnUiThread(() -> { if (!isDestroyed()) status.setText(line); }));
                runOnUiThread(() -> { if (!isDestroyed()) launchGame(); });
            } catch (IOException | InterruptedException error) {
                Log.e("TriAevum", "MM3D build failed", error);
                runOnUiThread(() -> {
                    if (!isDestroyed()) {
                        status.setText(error.getMessage());
                        setBusy(false);
                    }
                });
            }
        }, "Mm3dBuild").start();
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
