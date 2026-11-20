package org.triaevum.android;

import android.content.Context;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class Mm3dLocalBuild {
    static final String MODULE = "libtriaevum_title_aot.so";
    static final String PROFILE = "TriAevum.android.launch.json";
    private static final String ASSET = "mm3d-build-tools.zip";

    static File workspace(Context context) {
        return new File(context.getFilesDir(), "mm3d-local-build");
    }

    static File readyData(Context context) {
        File root = workspace(context);
        try {
            String key = new String(Files.readAllBytes(new File(root, "ready.txt").toPath()), StandardCharsets.US_ASCII).trim();
            if (!key.matches("[a-f0-9]{64}")) return null;
            File data = new File(root, key + "/data");
            return new File(data, MODULE).isFile() && new File(data, PROFILE).isFile() ? data : null;
        } catch (IOException missing) {
            return null;
        }
    }

    static boolean available(Context context) {
        File nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
        for (String name : new String[]{"libmm3d_python.so", "libmm3d_clang.so", "libmm3d_lld.so"}) {
            if (!new File(nativeDir, name).canExecute()) return false;
        }
        try (InputStream input = context.getAssets().open(ASSET)) {
            return true;
        } catch (IOException missing) {
            return false;
        }
    }

    static boolean run(Context context, File rom, Path cancel, Consumer<String> progress)
            throws IOException, InterruptedException {
        File nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
        File apk = new File(context.getApplicationInfo().sourceDir);
        String bundleId = String.format(Locale.ROOT, "%d-%d", apk.lastModified(), apk.length());
        Path bundle = context.getFilesDir().toPath().resolve("mm3d-tools-" + bundleId);
        Path complete = bundle.resolve(".complete");
        if (!Files.isRegularFile(complete)) {
            progress.accept("Preparing compiler files…");
            Files.createDirectories(bundle);
            try (ZipInputStream archive = new ZipInputStream(context.getAssets().open(ASSET))) {
                for (ZipEntry entry; (entry = archive.getNextEntry()) != null;) {
                    Path path = bundle.resolve(entry.getName()).normalize();
                    if (!path.startsWith(bundle)) throw new IOException("Invalid tool archive path.");
                    if (entry.isDirectory()) {
                        Files.createDirectories(path);
                    } else {
                        Files.createDirectories(path.getParent());
                        Files.copy(archive, path, StandardCopyOption.REPLACE_EXISTING);
                    }
                    if (Files.exists(cancel)) return false;
                }
            }
            Files.write(complete, bundleId.getBytes(StandardCharsets.US_ASCII));
        }
        if (Files.exists(cancel)) return false;

        ProcessBuilder build = new ProcessBuilder(
            new File(nativeDir, "libmm3d_python.so").getAbsolutePath(),
            bundle.resolve("repo/tools/android/build_mm3d_on_device.py").toString(),
            "--rom", rom.getAbsolutePath(),
            "--workspace", workspace(context).getAbsolutePath(),
            "--json-include", bundle.resolve("include").toString(),
            "--bundle-id", bundleId,
            "--cancel-file", cancel.toString(),
            "--cxx", new File(nativeDir, "libmm3d_clang.so").getAbsolutePath(),
            "--cxx-arg=--driver-mode=g++",
            "--cxx-arg=--target=aarch64-linux-android29",
            "--cxx-arg=--sysroot=" + bundle.resolve("sysroot"),
            "--cxx-arg=-resource-dir=" + bundle.resolve("clang-resource"),
            "--cxx-arg=--ld-path=" + new File(nativeDir, "libmm3d_lld.so").getAbsolutePath());
        build.environment().put("PYTHONHOME", bundle.resolve("python").toString());
        build.environment().put("PYTHONUNBUFFERED", "1");
        build.environment().put("LD_LIBRARY_PATH", nativeDir.getAbsolutePath());
        build.environment().put("LIBCAPSTONE_PATH", nativeDir.getAbsolutePath());
        build.environment().put("TMPDIR", context.getCacheDir().getAbsolutePath());
        Path log = context.getFilesDir().toPath().resolve("mm3d-build.log");
        Process process = build.redirectErrorStream(true).start();
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
             var output = Files.newBufferedWriter(log)) {
            for (String line; (line = lines.readLine()) != null;) {
                output.write(line);
                output.newLine();
                output.flush();
                if (line.startsWith("== ") || line.startsWith("[")) progress.accept(line);
            }
            int result = process.waitFor();
            if (result == 130 && Files.exists(cancel)) return false;
            if (result != 0 || readyData(context) == null) {
                throw new IOException("Build failed (exit " + result + "). See mm3d-build.log.");
            }
            return true;
        } finally {
            if (process.isAlive()) process.destroy();
        }
    }

    private Mm3dLocalBuild() {}
}
