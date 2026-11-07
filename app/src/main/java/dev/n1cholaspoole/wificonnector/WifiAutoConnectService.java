package dev.n1cholaspoole.wificonnector;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

/**
 * Optional always-on fallback mode: while this foreground service runs it watches scan
 * results and asks {@link WifiConnector} to connect whenever the configured SSID appears.
 *
 * <p>On Android 10+ the system may still be the one that decides whether to connect, but
 * this service makes sure the saved configuration/suggestion is present and retries.</p>
 */
public class WifiAutoConnectService extends Service {

    private static final String CHANNEL_ID = "wifi_auto_connect";
    private static final int NOTIFICATION_ID = 1001;
    private static final long POLL_INTERVAL_MS = 60000L;
    private static final long ATTEMPT_THROTTLE_MS = 15000L;

    private Handler handler;
    private BroadcastReceiver wifiReceiver;
    private boolean receiverRegistered = false;
    private long lastAttemptElapsed = 0L;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!Prefs.isFallbackEnabled(WifiAutoConnectService.this)
                    || !Prefs.isConfigured(WifiAutoConnectService.this)) {
                stopSelf();
                return;
            }
            // Without location permission the notification already tells the user to
            // open the app; skip scans to avoid repeating errors in the log.
            if (WifiConnector.hasLocationPermission(WifiAutoConnectService.this)
                    && !WifiConnector.isConnectedToTarget(WifiAutoConnectService.this)) {
                if (WifiConnector.isTargetVisible(WifiAutoConnectService.this)) {
                    attempt("network visible");
                } else if (!WifiConnector.requestScan(WifiAutoConnectService.this)) {
                    // Wi-Fi may be off. attempt() will try to enable it (API 21-28).
                    attempt("periodic check");
                }
            }
            if (handler != null) {
                handler.postDelayed(this, POLL_INTERVAL_MS);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
        try {
            startForeground(NOTIFICATION_ID, buildNotification());
        } catch (Exception e) {
            AppLog.log(this, "Could not start the foreground notification: "
                    + e.getClass().getSimpleName());
            stopSelf();
            return;
        }
        try {
            registerWifiReceiver();
        } catch (Exception e) {
            AppLog.log(this, "Could not register Wi-Fi broadcasts: "
                    + e.getClass().getSimpleName());
        }
        handler.post(pollRunnable);
        AppLog.log(this, "Always-connect watcher started.");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Prefs.isFallbackEnabled(this) || !Prefs.isConfigured(this)) {
            AppLog.log(this, "Always-connect watcher stopping: disabled or no network configured.");
            stopSelf();
            return START_NOT_STICKY;
        }
        updateNotification();
        attempt("always-connect started");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
            handler = null;
        }
        if (receiverRegistered) {
            try {
                unregisterReceiver(wifiReceiver);
            } catch (IllegalArgumentException ignored) {
                // Already unregistered.
            }
            receiverRegistered = false;
        }
        AppLog.log(this, "Always-connect watcher stopped.");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void attempt(String reason) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastAttemptElapsed < ATTEMPT_THROTTLE_MS) {
            return;
        }
        lastAttemptElapsed = now;
        WifiConnector.connect(this, reason);
    }

    private void registerWifiReceiver() {
        wifiReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || intent.getAction() == null) {
                    return;
                }
                String action = intent.getAction();
                if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(action)) {
                    if (WifiConnector.hasLocationPermission(WifiAutoConnectService.this)
                            && !WifiConnector.isConnectedToTarget(WifiAutoConnectService.this)
                            && WifiConnector.isTargetVisible(WifiAutoConnectService.this)) {
                        attempt("network visible");
                    }
                } else if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)) {
                    int state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                            WifiManager.WIFI_STATE_UNKNOWN);
                    if (state == WifiManager.WIFI_STATE_ENABLED) {
                        AppLog.log(WifiAutoConnectService.this, "Wi-Fi turned on. Checking network...");
                        attempt("Wi-Fi enabled");
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            // These are protected system broadcasts; EXPORTED is required so they are
            // delivered on Android 13+.
            registerReceiver(wifiReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(wifiReceiver, filter);
        }
        receiverRegistered = true;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notification_channel_description));
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent openApp = new Intent(this, MainActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, openApp, pendingFlags);

        String ssid = Prefs.getSsid(this);
        String text;
        if (ssid.length() == 0) {
            text = getString(R.string.notification_text_no_network);
        } else if (!WifiConnector.hasLocationPermission(this)) {
            text = getString(R.string.notification_text_permission);
        } else {
            text = getString(R.string.notification_text, ssid);
        }

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(Notification.PRIORITY_LOW)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void updateNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification());
        }
    }
}
