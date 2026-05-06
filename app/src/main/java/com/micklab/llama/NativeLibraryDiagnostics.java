package com.micklab.llama;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class NativeLibraryDiagnostics {

    private static final String[] IMPORTANT_LIBRARY_PREFIXES = new String[] {
            "libggml",
            "libllama"
    };

    private NativeLibraryDiagnostics() {
    }

    static void logNativeLibraryState(Context context, String tag) {
        if (context == null) {
            return;
        }

        ApplicationInfo applicationInfo = context.getApplicationInfo();
        String nativeLibraryDir = applicationInfo != null ? applicationInfo.nativeLibraryDir : null;
        String sourceApkPath = applicationInfo != null ? applicationInfo.sourceDir : null;

        List<String> nativeDirEntries = listNativeDirectoryEntries(nativeLibraryDir);
        List<String> apkEntries = listPackagedLibraryEntries(sourceApkPath);

        Log.i(tag,
                "native library diagnostics: supportedAbis=" + Arrays.toString(Build.SUPPORTED_ABIS)
                        + " nativeLibraryDir=" + safeValue(nativeLibraryDir)
                        + " nativeDirExists=" + isExistingDirectory(nativeLibraryDir)
                        + " nativeDirEntries=" + formatEntries(nativeDirEntries)
                        + " sourceApkPath=" + safeValue(sourceApkPath)
                        + " packagedApkEntries=" + formatEntries(apkEntries));

        boolean hasNativeHexagon = containsLibrary(nativeDirEntries, "libggml-hexagon.so");
        boolean hasPackagedHexagon = containsLibrary(apkEntries, "libggml-hexagon.so");
        if (!hasNativeHexagon && hasPackagedHexagon) {
            Log.w(tag,
                    "libggml-hexagon.so is packaged in the APK but missing from nativeLibraryDir. "
                            + "Hexagon backend loading will fail unless the app extracts JNI libraries on install.");
        }
    }

    static String getNativeLibraryDir(Context context) {
        ApplicationInfo applicationInfo = context != null ? context.getApplicationInfo() : null;
        return applicationInfo != null ? applicationInfo.nativeLibraryDir : null;
    }

    static String getSourceApkPath(Context context) {
        ApplicationInfo applicationInfo = context != null ? context.getApplicationInfo() : null;
        return applicationInfo != null ? applicationInfo.sourceDir : null;
    }

    private static List<String> listNativeDirectoryEntries(String nativeLibraryDir) {
        if (!isExistingDirectory(nativeLibraryDir)) {
            return Collections.emptyList();
        }

        File[] files = new File(nativeLibraryDir).listFiles();
        if (files == null || files.length == 0) {
            return Collections.emptyList();
        }

        List<String> entries = new ArrayList<>();
        for (File file : files) {
            if (file == null) {
                continue;
            }
            String name = file.getName();
            if (isImportantLibrary(name)) {
                entries.add(name);
            }
        }
        Collections.sort(entries);
        return entries;
    }

    private static List<String> listPackagedLibraryEntries(String sourceApkPath) {
        if (sourceApkPath == null || sourceApkPath.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> entries = new ArrayList<>();
        try (ZipFile zipFile = new ZipFile(sourceApkPath)) {
            Enumeration<? extends ZipEntry> zipEntries = zipFile.entries();
            while (zipEntries.hasMoreElements()) {
                ZipEntry entry = zipEntries.nextElement();
                String entryName = entry.getName();
                if (!entryName.startsWith("lib/")) {
                    continue;
                }

                int abiSeparator = entryName.indexOf('/', 4);
                if (abiSeparator <= 4 || abiSeparator >= entryName.length() - 1) {
                    continue;
                }

                String abi = entryName.substring(4, abiSeparator);
                String fileName = entryName.substring(abiSeparator + 1);
                if (!isImportantLibrary(fileName)) {
                    continue;
                }
                entries.add(abi + ":" + fileName);
            }
        } catch (IOException e) {
            Log.w("NativeLibraryDiagnostics", "Failed to inspect packaged native libraries in " + sourceApkPath, e);
            return Collections.emptyList();
        }

        Collections.sort(entries);
        return entries;
    }

    private static boolean containsLibrary(List<String> entries, String fileName) {
        for (String entry : entries) {
            if (entry == null) {
                continue;
            }
            if (entry.equals(fileName) || entry.endsWith(":" + fileName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isExistingDirectory(String path) {
        return path != null && !path.isEmpty() && new File(path).isDirectory();
    }

    private static boolean isImportantLibrary(String fileName) {
        if (fileName == null || !fileName.endsWith(".so")) {
            return false;
        }
        for (String prefix : IMPORTANT_LIBRARY_PREFIXES) {
            if (fileName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String safeValue(String value) {
        return value == null || value.isEmpty() ? "<empty>" : value;
    }

    private static String formatEntries(List<String> entries) {
        if (entries == null || entries.isEmpty()) {
            return "[<none>]";
        }
        return entries.toString();
    }
}
