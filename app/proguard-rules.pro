# ============================================================================
# R8 keep rules for com.micklab.llama
#
# minifyEnabled=true (code shrink + obfuscation). shrinkResources stays off.
# The app has no Java reflection and uses org.json (no reflective model binding),
# so the only rules R8 needs are for the JNI boundary in jni_llama.cpp:
#   - native methods are bound by their mangled name Java_com_micklab_llama_LlamaNative_*
#   - a few Java methods are invoked from native via GetMethodID(name, sig)
# R8 does not fail the build for a missing JNI keep; it silently strips/renames,
# which surfaces only as a runtime UnsatisfiedLinkError / NoSuchMethodError, so
# these must be complete.
# ============================================================================

# ---- Native method binding: keep the class name + native method names ----
# (proguard-android-optimize already keeps native methods, but be explicit.)
-keepclasseswithmembernames,includedescriptorclasses class com.micklab.llama.LlamaNative {
    native <methods>;
}

# ---- Methods invoked from native via GetMethodID (jni_llama.cpp) ----
# download progress: GetObjectClass(thiz=LlamaNative) -> GetMethodID("onDownloadProgress","(I)V")
-keepclassmembers class com.micklab.llama.LlamaNative {
    void onDownloadProgress(int);
}

# token streaming: GetObjectClass(listener) -> GetMethodID("onToken"/"onComplete"/"onError")
# Keep the interface method names so any implementer's (incl. lambda) overrides keep them.
-keep interface com.micklab.llama.LlamaNative$TokenListener { *; }
-keepclassmembers class * implements com.micklab.llama.LlamaNative$TokenListener {
    void onToken(java.lang.String);
    void onComplete();
    void onError(java.lang.String);
}

# download-progress listener is reached via a normal Java call from onDownloadProgress,
# but keep it consistent (SettingsActivity registers a lambda).
-keep interface com.micklab.llama.LlamaNative$DownloadProgressListener { *; }
-keepclassmembers class * implements com.micklab.llama.LlamaNative$DownloadProgressListener {
    void onDownloadProgress(int);
}

# ---- Keep readable stack traces (obfuscation on) for crash triage via mapping.txt ----
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
