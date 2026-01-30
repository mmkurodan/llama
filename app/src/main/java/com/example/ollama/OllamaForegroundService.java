package com.example.ollama;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * Foreground service that keeps the Ollama API server running in the background.
 * Uses a persistent notification to maintain the service.
 */
public class OllamaForegroundService extends Service {
    private static final String TAG = "OllamaForegroundService";
    
    public static final String CHANNEL_ID = "ollama_service_channel";
    public static final int NOTIFICATION_ID = 1;
    
    public static final String ACTION_START = "com.example.ollama.START_SERVICE";
    public static final String ACTION_STOP = "com.example.ollama.STOP_SERVICE";
    
    // Broadcast actions for communicating with MainActivity
    public static final String ACTION_LOG = "com.example.ollama.LOG";
    public static final String ACTION_STATUS_CHANGED = "com.example.ollama.STATUS_CHANGED";
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
                stopSelf();
                return START_NOT_STICKY;
            }
            
            port = intent.getIntExtra("port", OllamaApiServer.DEFAULT_PORT);
        }
        
        // Start foreground with notification
        Notification notification = createNotification("Ollama API Server", "Starting...");
        startForeground(NOTIFICATION_ID, notification);
        
        // Start API server
        startApiServer();
        
        return START_STICKY;
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
                "Ollama API Server",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Keeps the Ollama API server running in the background");
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
            .setOngoing(true)
            .build();
    }
    
    private void updateNotification(String content) {
        Notification notification = createNotification("Ollama API Server", content);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, notification);
        }
    }
    
    private void startApiServer() {
        if (apiServer != null && apiServer.isRunning()) {
            Log.w(TAG, "API server already running");
            return;
        }
        
        apiServer = new OllamaApiServer(this, modelManager);
        apiServer.setPort(port);
        apiServer.setListener(new OllamaApiServer.ServerListener() {
            @Override
            public void onServerStarted(int port) {
                Log.i(TAG, "API server started on port " + port);
                updateNotification("Running on port " + port);
                sendLog("API server started on port " + port);
                sendStatusChanged("running", port);
            }
            
            @Override
            public void onServerStopped() {
                Log.i(TAG, "API server stopped");
                updateNotification("Stopped");
                sendLog("API server stopped");
                sendStatusChanged("stopped", port);
            }
            
            @Override
            public void onServerError(String error) {
                Log.e(TAG, "API server error: " + error);
                updateNotification("Error: " + error);
                sendLog("API Server Error: " + error);
            }
            
            @Override
            public void onRequest(String method, String path) {
                Log.d(TAG, "Request: " + method + " " + path);
                sendLog("API Request: " + method + " " + path);
            }
            
            @Override
            public void onModelLoading(String configName) {
                updateNotification("Loading: " + configName);
                sendLog("API: Loading configuration: " + configName);
            }
            
            @Override
            public void onModelLoaded(String configName) {
                updateNotification("Ready: " + configName);
                sendLog("API: Model loaded for configuration: " + configName);
            }
            
            @Override
            public void onGenerating(String configName) {
                updateNotification("Generating...");
                sendLog("API: Generating with configuration: " + configName);
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
