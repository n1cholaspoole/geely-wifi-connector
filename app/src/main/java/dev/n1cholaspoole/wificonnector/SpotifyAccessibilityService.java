package dev.n1cholaspoole.wificonnector;

import android.accessibilityservice.AccessibilityService;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

/**
 * Detects Spotify coming to the foreground. Android does not allow third-party apps to
 * read the foreground app list directly, so this uses an AccessibilityService, which the
 * user must enable once in system settings. Screen content is never retrieved.
 */
public class SpotifyAccessibilityService extends AccessibilityService {

    private static final String SPOTIFY_PACKAGE = "com.spotify.music";
    private static final long TRIGGER_THROTTLE_MS = 8000L;

    private long lastTriggerElapsed = 0L;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AppLog.log(this, "Spotify detection is ON (accessibility service connected).");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            return;
        }
        CharSequence packageName = event.getPackageName();
        if (packageName == null || !SPOTIFY_PACKAGE.contentEquals(packageName)) {
            return;
        }
        if (!Prefs.isSpotifyTriggerEnabled(this)) {
            return;
        }

        long now = SystemClock.elapsedRealtime();
        if (now - lastTriggerElapsed < TRIGGER_THROTTLE_MS) {
            return;
        }
        lastTriggerElapsed = now;

        AppLog.log(this, "Spotify opened. Trying to connect to the configured Wi-Fi network.");
        WifiConnector.connect(this, "Spotify opened");
    }

    @Override
    public void onInterrupt() {
        AppLog.log(this, "Spotify detection was interrupted by Android.");
    }

    @Override
    public void onDestroy() {
        AppLog.log(this, "Spotify detection service stopped.");
        super.onDestroy();
    }
}
