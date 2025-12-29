package com.example.ollama;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private TextView logView;
    private ScrollView scrollView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        logView = findViewById(R.id.logView);
        scrollView = findViewById(R.id.scrollView);

        // Example flow: after downloading model, validate then init
        String modelPath = getFilesDir() + "/models/my-model.bin";

        // ... download logic should run before this point and write the modelPath file

        // Add checks after download and before init
        try {
            if (verifyModelFile(modelPath)) {
                appendMessage("Model checks passed, initializing model: " + modelPath);
                init(modelPath);
            } else {
                appendException("Model checks failed, will not initialize model.", null);
            }
        } catch (Exception e) {
            appendException("Exception while verifying model file", e);
        }
    }

    // Verifies that the model file exists, reports its size and first bytes (head) to the UI log.
    private boolean verifyModelFile(String modelPath) {
        File modelFile = new File(modelPath);

        if (!modelFile.exists()) {
            appendException("Model file does not exist: " + modelPath, null);
            return false;
        }

        long size = modelFile.length();
        appendMessage("Model file exists: " + modelPath);
        appendMessage("Model file size: " + size + " bytes");

        if (size == 0) {
            appendException("Model file is empty (size 0): " + modelPath, null);
            return false;
        }

        // Read head bytes (first N bytes) to do a quick sanity check and display in UI
        final int HEAD_BYTES = 64; // number of bytes to read from start of file
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(modelFile);
            byte[] head = new byte[HEAD_BYTES];
            int read = fis.read(head);
            if (read > 0) {
                appendMessage("Model head bytes (first " + read + " bytes): " + bytesToHex(head, read));
            } else {
                appendException("Unable to read head bytes from model file: " + modelPath, null);
                return false;
            }
        } catch (IOException e) {
            appendException("IOException while reading model head bytes: " + e.getMessage(), e);
            return false;
        } finally {
            if (fis != null) {
                try { fis.close(); } catch (IOException ignored) {}
            }
        }

        // Optionally: perform additional checks like minimum expected size
        final long MIN_EXPECTED_SIZE = 1024; // 1KB as a simple lower bound; adjust as needed
        if (size < MIN_EXPECTED_SIZE) {
            appendException("Model file is smaller than expected (" + size + " < " + MIN_EXPECTED_SIZE + ")", null);
            return false;
        }

        return true;
    }

    // Convert bytes to hex string for display
    private String bytesToHex(byte[] bytes, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(String.format("%02X", bytes[i]));
            if (i < length - 1) sb.append(' ');
        }
        return sb.toString();
    }

    // Placeholder for model initialization logic
    private void init(String modelPath) {
        // Initialize the model using modelPath. This method should contain the real init logic.
        appendMessage("init() called with modelPath: " + modelPath);

        // Example: try-catch around initialization to report errors to UI
        try {
            // Actual initialization code goes here
        } catch (Exception e) {
            appendException("Exception during model initialization", e);
        }
    }

    // Append regular messages to the UI log (thread-safe)
    private void appendMessage(final String message) {
        Log.i(TAG, message);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (logView != null) {
                    logView.append(message + "\n");
                    // Scroll to bottom
                    if (scrollView != null) scrollView.post(new Runnable() { public void run() { scrollView.fullScroll(ScrollView.FOCUS_DOWN); } });
                }
            }
        });
    }

    // Append exceptions to the UI log (thread-safe)
    private void appendException(final String message, final Exception e) {
        Log.e(TAG, message, e);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (logView != null) {
                    logView.append("ERROR: " + message + (e != null ? (" - " + e.getMessage()) : "") + "\n");
                    if (scrollView != null) scrollView.post(new Runnable() { public void run() { scrollView.fullScroll(ScrollView.FOCUS_DOWN); } });
                }
            }
        });
    }
}
