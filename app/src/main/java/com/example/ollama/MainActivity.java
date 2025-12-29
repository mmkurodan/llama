package com.example.ollama;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    private TextView tv;
    private Button btnDownload;

    // Keep a reference so we can clear it later
    private LlamaNative.DownloadProgressListener progressListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tv = findViewById(R.id.tv);
        btnDownload = findViewById(R.id.btnDownload);

        btnDownload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startDownload("example-model");
            }
        });
    }

    private void startDownload(final String modelName) {
        // Register download progress listener before starting download
        progressListener = new LlamaNative.DownloadProgressListener() {
            @Override
            public void onProgress(float progress) {
                final int percent = (int) (progress * 100);

                // Update TextView on UI thread
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (tv != null) {
                            tv.setText(percent + "%");
                        }
                    }
                });
            }
        };

        LlamaNative.setDownloadProgressListener(progressListener);

        // Perform download off the UI thread
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Call the Java wrapper that delegates to the native download function
                    int status = LlamaNative.downloadModel(modelName);

                    if (status == 0) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (tv != null) tv.setText("100%");
                            }
                        });
                    } else {
                        throw new RuntimeException("downloadModel returned status " + status);
                    }
                } catch (final Exception e) {
                    Log.e(TAG, "Download failed", e);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (tv != null) tv.setText("Download failed");
                        }
                    });
                } finally {
                    // Clear the listener after download finishes (whether success or failure)
                    try {
                        LlamaNative.setDownloadProgressListener(null);
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to clear download progress listener", e);
                    }
                    progressListener = null;
                }
            }
        }).start();
    }
}
