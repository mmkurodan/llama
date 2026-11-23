package com.micklab.llama;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.List;

/**
 * Shared UI for model downloads: a confirmation dialog that lists every file and its size before a
 * download starts, and a progress popup shown while any model download is running (whether started
 * by the app or by the WebUI / API).
 */
public final class DownloadUi {
    private DownloadUi() {
    }

    public interface PendingCallback {
        void onResolved(List<ModelManager.PendingDownload> pending);
    }

    private static String t(Activity a, String ja, String en) {
        return Translations.get(a, ja, en);
    }

    private static boolean isAlive(Activity a) {
        return a != null && !a.isFinishing() && !a.isDestroyed();
    }

    /** Resolve the pending downloads (and their sizes) off the UI thread, then call back on it. */
    public static void resolvePendingAsync(Activity activity, ModelManager mm,
                                           ConfigurationManager.Configuration config,
                                           boolean includeProjector, PendingCallback cb) {
        new Thread(() -> {
            List<ModelManager.PendingDownload> pending = mm.getPendingDownloads(config, includeProjector);
            mm.resolveDownloadSizes(pending);
            activity.runOnUiThread(() -> {
                if (isAlive(activity)) {
                    cb.onResolved(pending);
                }
            });
        }, "download-size-check").start();
    }

    /**
     * If loading {@code configName} would download anything, ask first (showing the sizes);
     * otherwise run {@code onConfirm} immediately.
     */
    public static void confirmConfigDownloadThen(Activity activity, ModelManager mm, String configName,
                                                 boolean includeProjector, Runnable onConfirm) {
        List<ModelManager.PendingDownload> quick = mm.getPendingDownloads(configName, includeProjector);
        if (quick.isEmpty()) {
            onConfirm.run();
            return;
        }
        new Thread(() -> {
            mm.resolveDownloadSizes(quick);
            activity.runOnUiThread(() -> {
                if (isAlive(activity)) {
                    showConfirmDialog(activity, quick, onConfirm, null);
                }
            });
        }, "download-size-check").start();
    }

    /** File list with sizes plus the total, e.g. for a dialog message. */
    public static String describe(Activity a, List<ModelManager.PendingDownload> pending) {
        StringBuilder sb = new StringBuilder();
        for (ModelManager.PendingDownload d : pending) {
            sb.append("• ").append(d.fileName)
                    .append(d.projector ? " [mmproj]" : "")
                    .append("  (").append(ModelManager.formatDownloadSize(d.sizeBytes)).append(")\n");
        }
        long total = ModelManager.totalDownloadBytes(pending);
        sb.append(t(a, "合計サイズ: ", "Total size: "))
                .append(total >= 0 ? ModelManager.formatDownloadSize(total) : t(a, "不明", "Unknown"));
        return sb.toString();
    }

    public static void showConfirmDialog(Activity a, List<ModelManager.PendingDownload> pending,
                                         Runnable onConfirm, Runnable onCancel) {
        String message = t(a, "次のファイルをダウンロードします。", "The following files will be downloaded.")
                + "\n\n" + describe(a, pending) + "\n\n"
                + t(a, "大容量の通信が発生するため、可能な限り Wi-Fi での実行を推奨します。ダウンロードしますか？",
                "This uses a large amount of data; Wi-Fi is strongly recommended. Download now?");
        AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle(t(a, "モデルのダウンロード", "Download model"))
                .setMessage(message)
                .setPositiveButton(t(a, "ダウンロード", "Download"), (d, w) -> onConfirm.run())
                .setNegativeButton(t(a, "キャンセル", "Cancel"), (d, w) -> {
                    if (onCancel != null) onCancel.run();
                })
                .create();
        dialog.setOnCancelListener(d -> {
            if (onCancel != null) onCancel.run();
        });
        dialog.show();
    }

    /**
     * Progress popup bound to an activity: call {@link #start()} in onResume and {@link #stop()} in
     * onPause. It appears whenever a model download is running and closes when it finishes.
     */
    public static final class ProgressPopup implements ModelManager.DownloadStateListener {
        private final Activity activity;
        private final ModelManager mm;
        private final Handler main = new Handler(Looper.getMainLooper());
        private AlertDialog dialog;
        private ProgressBar bar;
        private TextView label;
        /** File the user chose to hide the popup for (it stays hidden until a new download). */
        private String hiddenFor;

        public ProgressPopup(Activity activity, ModelManager mm) {
            this.activity = activity;
            this.mm = mm;
        }

        public void start() {
            mm.addDownloadStateListener(this);
            onDownloadStateChanged(mm.getDownloadState());
        }

        public void stop() {
            mm.removeDownloadStateListener(this);
            main.post(this::dismiss);
        }

        @Override
        public void onDownloadStateChanged(ModelManager.DownloadState state) {
            main.post(() -> render(state));
        }

        private void render(ModelManager.DownloadState s) {
            if (!isAlive(activity)) {
                return;
            }
            if (s == null || !s.active) {
                hiddenFor = null;
                dismiss();
                return;
            }
            if (s.fileName != null && s.fileName.equals(hiddenFor)) {
                return;
            }
            if (dialog == null) {
                build();
            }
            bar.setIndeterminate(s.percent <= 0);
            bar.setProgress(Math.max(0, Math.min(100, s.percent)));
            StringBuilder text = new StringBuilder();
            text.append(s.fileName != null ? s.fileName : "").append("\n");
            long done = s.downloadedBytes();
            text.append(s.percent).append("%");
            if (s.totalBytes > 0) {
                text.append("  (").append(ModelManager.formatDownloadSize(done)).append(" / ")
                        .append(ModelManager.formatDownloadSize(s.totalBytes)).append(")");
            }
            label.setText(text.toString());
            if (!dialog.isShowing()) {
                dialog.show();
            }
        }

        private void build() {
            float d = activity.getResources().getDisplayMetrics().density;
            int pad = Math.round(20 * d);
            LinearLayout box = new LinearLayout(activity);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(pad, pad / 2, pad, 0);
            bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100);
            label = new TextView(activity);
            label.setPadding(0, Math.round(8 * d), 0, 0);
            box.addView(bar);
            box.addView(label);
            dialog = new AlertDialog.Builder(activity)
                    .setTitle(t(activity, "モデルをダウンロード中", "Downloading model"))
                    .setView(box)
                    .setCancelable(false)
                    .setPositiveButton(t(activity, "バックグラウンドで続行", "Continue in background"), (dlg, w) -> {
                        ModelManager.DownloadState cur = mm.getDownloadState();
                        hiddenFor = cur.fileName;
                    })
                    .create();
        }

        private void dismiss() {
            if (dialog != null) {
                try {
                    if (dialog.isShowing()) dialog.dismiss();
                } catch (Exception ignored) {
                }
                dialog = null;
            }
        }
    }
}
