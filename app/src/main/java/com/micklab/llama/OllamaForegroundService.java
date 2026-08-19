package com.micklab.llama;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

/**
 * Foreground service that keeps the Llama API/WebUI server running in the background.
 * Uses a persistent notification to maintain the service.
 */
public class OllamaForegroundService extends Service {
    private static final String TAG = "OllamaForegroundService";
    
    public static final String CHANNEL_ID = "ollama_service_channel";
    public static final int NOTIFICATION_ID = 1;
    
    public static final String ACTION_START = "com.micklab.llama.START_SERVICE";
    public static final String ACTION_STOP = "com.micklab.llama.STOP_SERVICE";
    public static final String ACTION_EXIT = "com.micklab.llama.EXIT_APP";
    public static final String ACTION_DISCONNECT_ALL = "com.micklab.llama.DISCONNECT_ALL";
    // Re-read power-related prefs (keep-awake) and apply them without restarting the server.
    public static final String ACTION_APPLY_POWER = "com.micklab.llama.APPLY_POWER";

    // Persisted API-enable intent: once the user enables the server it stays enabled across process
    // death / task removal / reboot until an explicit Stop/Exit. Shared with MainActivity, the boot
    // receiver and LlamaApplication (all use the "ollama_prefs" file).
    public static final String PREFS_NAME = "ollama_prefs";
    public static final String PREF_API_ENABLED = "api_enabled";
    public static final String PREF_API_PORT = "api_port";
    // "Keep awake" high-availability toggle (default false): hold a wake lock + Wi-Fi lock continuously
    // while the API is enabled so the device does not suspend and stays instantly responsive in sleep.
    public static final String PREF_KEEP_AWAKE = "keep_awake";
    
    // Broadcast actions for communicating with MainActivity
    public static final String ACTION_LOG = "com.micklab.llama.LOG";
    public static final String ACTION_STATUS_CHANGED = "com.micklab.llama.STATUS_CHANGED";
    public static final String EXTRA_LOG_MESSAGE = "log_message";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_PORT = "port";
    
    // Process-scoped liveness used by the recovery watchdog (WatchdogReceiver). Statics reset to their
    // defaults in a freshly cold-started process, so a watchdog tick delivered to a resurrected process
    // sees sServiceRunning=false and restarts the service.
    private static volatile boolean sServiceRunning = false;
    private static volatile long sServiceStartElapsed = 0L;

    private OllamaApiServer apiServer;
    private ModelManager modelManager;
    private int port = OllamaApiServer.DEFAULT_PORT;

    // Background execution: a partial wake lock + Wi-Fi lock held (a) while a generation/model-load is
    // in progress (via the ModelManager busy listener) so a screen-off does not stall inference, and
    // (b) continuously when the keep-awake toggle is on. Reference-counted inside PowerLocks.
    private PowerLocks powerLocks;
    private boolean keepAwakeHeld = false;
    private ModelManager.BusyStateListener busyWakeListener;
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Service onCreate");
        
        createNotificationChannel();
        modelManager = ModelManager.getInstance(this);

        // Hold a wake lock (+ Wi-Fi lock) while the model is busy so inference does not stall when the
        // screen turns off. Acquire/release is balanced by the busy-state transitions. (default path)
        powerLocks = new PowerLocks(this, "Llama");
        busyWakeListener = isBusy -> {
            if (isBusy) {
                powerLocks.acquire();
            } else {
                powerLocks.release();
            }
        };
        modelManager.addBusyStateListener(busyWakeListener);
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Service onStartCommand");
        
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_STOP.equals(action)) {
                // Explicit user disable: clear the persisted intent so we do NOT auto-restart, and stop
                // the recovery alarm chain so the watchdog does not resurrect us.
                setApiEnabled(this, false);
                sServiceRunning = false;
                RecoveryScheduler.cancel(this);
                stopSelf();
                return START_NOT_STICKY;
            }
            if (ACTION_EXIT.equals(action)) {
                Log.i(TAG, "Exit action received - terminating app");
                // Explicit user exit: clear the persisted intent so we do NOT auto-restart.
                setApiEnabled(this, false);
                sServiceRunning = false;
                RecoveryScheduler.cancel(this);
                stopApiServer();
                stopSelf();
                // This is a deliberate user-requested termination. Clear any in-progress
                // generation marker first so the next launch does not misreport this orderly
                // exit (which kills the process mid-native-call) as a previous crash.
                DiagnosticsLogger.clearGenerationInProgress(this);
                // Tag this deliberate self-kill so the next launch does not confuse it with an OOM
                // SIGKILL (both appear as REASON_SIGNALED/status=9 in ApplicationExitInfo).
                DiagnosticsLogger.markIntentionalSelfKill(this, android.os.Process.myPid(), "user-exit");
                // Terminate the entire application
                android.os.Process.killProcess(android.os.Process.myPid());
                return START_NOT_STICKY;
            }
            if (ACTION_DISCONNECT_ALL.equals(action)) {
                Log.i(TAG, "Disconnect all action received");
                if (apiServer != null) {
                    apiServer.disconnectAll();
                    sendLog("API/WebUI: All connections disconnected");
                }
                return START_STICKY;
            }
            if (ACTION_APPLY_POWER.equals(action)) {
                // Keep-awake toggle changed in Settings: apply without restarting the server.
                applyKeepAwake();
                return START_STICKY;
            }
        }

        // Resolve the port. On a START_STICKY restart the intent is null (its extras are lost), so
        // fall back to the persisted port instead of silently reverting to the default.
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        port = prefs.getInt(PREF_API_PORT, OllamaApiServer.DEFAULT_PORT);
        if (intent != null && intent.hasExtra("port")) {
            port = intent.getIntExtra("port", port);
            prefs.edit().putInt(PREF_API_PORT, port).apply();
        }

        // Reaching here means we are (re)starting the server, so persist the enabled intent.
        setApiEnabled(this, true);

        // Start foreground with notification. On Android 14+ starting a specialUse FGS from certain
        // background contexts (e.g. BOOT_COMPLETED) can be disallowed; degrade gracefully rather than
        // crash so a later foreground launch can re-assert.
        try {
            Notification notification = createNotification("Llama API + WebUI Server", "Starting...");
            startForeground(NOTIFICATION_ID, notification);
        } catch (Exception e) {
            Log.w(TAG, "startForeground not allowed in this context; will retry on next launch", e);
            stopSelf();
            return START_STICKY;
        }

        // Start API/WebUI server
        startApiServer();

        // Apply the keep-awake (high-availability) preference now that the server is up.
        applyKeepAwake();

        // Mark this process's service as live and (re)arm the self-recovery alarm chain. The start
        // time is captured once per process so the proactive-recycle age reflects real process uptime.
        sServiceRunning = true;
        if (sServiceStartElapsed == 0L) {
            sServiceStartElapsed = SystemClock.elapsedRealtime();
        }
        RecoveryScheduler.ensureScheduled(this);

        return START_STICKY;
    }

    /** Whether the foreground service is live in the current process (see {@link WatchdogReceiver}). */
    public static boolean isServiceRunning() {
        return sServiceRunning;
    }

    /** Uptime of the service in the current process, or 0 if not yet started. */
    public static long serviceUptimeMs() {
        long start = sServiceStartElapsed;
        return start == 0L ? 0L : SystemClock.elapsedRealtime() - start;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Surviving a swipe-from-recents: keep serving unless the user explicitly disabled the API.
        // START_STICKY covers process death, but some OEMs kill on task removal without a sticky
        // restart, so re-request a start here as a best effort.
        if (isApiEnabled(this)) {
            try {
                Intent restart = new Intent(getApplicationContext(), OllamaForegroundService.class);
                restart.setAction(ACTION_START);
                restart.putExtra("port", port);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(restart);
                } else {
                    startService(restart);
                }
            } catch (Exception e) {
                Log.w(TAG, "onTaskRemoved restart failed", e);
            }
        }
        super.onTaskRemoved(rootIntent);
    }

    /** Persist the user's API-enable intent so the server is re-asserted after any restart. */
    public static void setApiEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_API_ENABLED, enabled).apply();
    }

    /** Whether the user has enabled the API server (persisted; default false). */
    public static boolean isApiEnabled(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_API_ENABLED, false);
    }

    /**
     * Start the foreground service iff the persisted intent says the API is enabled. Safe to call on
     * process start (app launch, boot). Failures (e.g. background-start restrictions) are swallowed so
     * a later foreground launch can re-assert.
     */
    public static void startIfEnabled(Context context) {
        if (!isApiEnabled(context)) {
            return;
        }
        try {
            Intent i = new Intent(context, OllamaForegroundService.class);
            i.setAction(ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i);
            } else {
                context.startService(i);
            }
        } catch (Exception e) {
            Log.w(TAG, "startIfEnabled failed to start service", e);
        }
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    @Override
    public void onDestroy() {
        Log.i(TAG, "Service onDestroy");
        sServiceRunning = false;
        stopApiServer();
        if (busyWakeListener != null && modelManager != null) {
            modelManager.removeBusyStateListener(busyWakeListener);
        }
        if (powerLocks != null) {
            powerLocks.releaseAll();
        }
        super.onDestroy();
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Llama API + WebUI Server",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Keeps the Llama API/WebUI server running in the background");
            channel.setShowBadge(false);
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
    
    private Notification createNotification(String title, String content) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        Intent stopIntent = new Intent(this, OllamaForegroundService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        Intent exitIntent = new Intent(this, OllamaForegroundService.class);
        exitIntent.setAction(ACTION_EXIT);
        PendingIntent exitPendingIntent = PendingIntent.getService(
            this, 1, exitIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        
        return builder
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .addAction(android.R.drawable.ic_delete, "Exit", exitPendingIntent)
            .setOngoing(true)
            .build();
    }
    
    private void updateNotification(String content) {
        Notification notification = createNotification("Llama API + WebUI Server", content);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, notification);
        }
    }
    
    private void startApiServer() {
        if (apiServer != null && apiServer.isRunning()) {
            Log.w(TAG, "API/WebUI server already running");
            return;
        }
        
        apiServer = new OllamaApiServer(this, modelManager);
        apiServer.setPort(port);
        apiServer.setListener(new OllamaApiServer.ServerListener() {
            @Override
            public void onServerStarted(int port) {
                Log.i(TAG, "API/WebUI server started on port " + port);
                updateNotification("API + WebUI on port " + port);
                sendLog("API/WebUI server started on port " + port + " (WebUI: /)");
                sendStatusChanged("running", port);
            }
            
            @Override
            public void onServerStopped() {
                Log.i(TAG, "API/WebUI server stopped");
                updateNotification("Stopped");
                sendLog("API/WebUI server stopped");
                sendStatusChanged("stopped", port);
            }
            
            @Override
            public void onServerError(String error) {
                Log.e(TAG, "API/WebUI server error: " + error);
                updateNotification("Error: " + error);
                sendLog("API/WebUI Server Error: " + error);
            }
            
            @Override
            public void onRequest(String method, String path) {
                Log.d(TAG, "Request: " + method + " " + path);
                sendLog("API/WebUI Request: " + method + " " + path);
            }
            
            @Override
            public void onModelLoading(String configName) {
                updateNotification("Loading: " + configName);
                sendLog("API/WebUI: Loading configuration: " + configName);
            }
            
            @Override
            public void onModelLoaded(String configName) {
                updateNotification("Ready: " + configName);
                sendLog("API/WebUI: Model loaded for configuration: " + configName);
            }
            
            @Override
            public void onGenerating(String configName) {
                updateNotification("Generating...");
                sendLog("API/WebUI: Generating with configuration: " + configName);
            }
        });
        
        apiServer.start();
    }
    
    private void sendLog(String message) {
        Intent intent = new Intent(ACTION_LOG);
        intent.putExtra(EXTRA_LOG_MESSAGE, message);
        sendBroadcast(intent);
    }
    
    private void sendStatusChanged(String status, int port) {
        Intent intent = new Intent(ACTION_STATUS_CHANGED);
        intent.putExtra(EXTRA_STATUS, status);
        intent.putExtra(EXTRA_PORT, port);
        sendBroadcast(intent);
    }
    
    private void stopApiServer() {
        if (apiServer != null) {
            apiServer.stop();
            apiServer = null;
        }
        // Drop the continuous keep-awake hold when the server stops.
        if (keepAwakeHeld) {
            keepAwakeHeld = false;
            if (powerLocks != null) {
                powerLocks.release();
            }
        }
    }

    /** Apply the persisted keep-awake preference: hold or drop the continuous wake/Wi-Fi lock. */
    private void applyKeepAwake() {
        if (powerLocks == null) {
            return;
        }
        boolean wanted = isKeepAwakeEnabled(this);
        if (wanted && !keepAwakeHeld) {
            keepAwakeHeld = true;
            powerLocks.acquire();
            sendLog("Keep-awake ON: holding wake + Wi-Fi lock while the API is enabled");
        } else if (!wanted && keepAwakeHeld) {
            keepAwakeHeld = false;
            powerLocks.release();
            sendLog("Keep-awake OFF");
        }
    }

    /** Whether the keep-awake (high-availability) toggle is on (persisted; default false). */
    public static boolean isKeepAwakeEnabled(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_KEEP_AWAKE, false);
    }
    
    public boolean isServerRunning() {
        return apiServer != null && apiServer.isRunning();
    }
}
