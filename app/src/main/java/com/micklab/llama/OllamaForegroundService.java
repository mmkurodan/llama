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

    // Persisted API-enable intent: once the user enables the server it stays enabled across process
    // death / task removal / reboot until an explicit Stop/Exit. Shared with MainActivity, the boot
    // receiver and LlamaApplication (all use the "ollama_prefs" file).
    public static final String PREFS_NAME = "ollama_prefs";
    public static final String PREF_API_ENABLED = "api_enabled";
    public static final String PREF_API_PORT = "api_port";
    
    // Broadcast actions for communicating with MainActivity
    public static final String ACTION_LOG = "com.micklab.llama.LOG";
    public static final String ACTION_STATUS_CHANGED = "com.micklab.llama.STATUS_CHANGED";
    public static final String EXTRA_LOG_MESSAGE = "log_message";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_PORT = "port";
    
    private OllamaApiServer apiServer;
    private ModelManager modelManager;
    private int port = OllamaApiServer.DEFAULT_PORT;
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Service onCreate");
        
        createNotificationChannel();
        modelManager = ModelManager.getInstance(this);
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Service onStartCommand");
        
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_STOP.equals(action)) {
                // Explicit user disable: clear the persisted intent so we do NOT auto-restart.
                setApiEnabled(this, false);
                stopSelf();
                return START_NOT_STICKY;
            }
            if (ACTION_EXIT.equals(action)) {
                Log.i(TAG, "Exit action received - terminating app");
                // Explicit user exit: clear the persisted intent so we do NOT auto-restart.
                setApiEnabled(this, false);
                stopApiServer();
                stopSelf();
                // This is a deliberate user-requested termination. Clear any in-progress
                // generation marker first so the next launch does not misreport this orderly
                // exit (which kills the process mid-native-call) as a previous crash.
                DiagnosticsLogger.clearGenerationInProgress(this);
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

        return START_STICKY;
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
        stopApiServer();
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
    }
    
    public boolean isServerRunning() {
        return apiServer != null && apiServer.isRunning();
    }
}
