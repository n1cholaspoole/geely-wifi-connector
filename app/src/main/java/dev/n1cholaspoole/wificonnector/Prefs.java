package dev.n1cholaspoole.wificonnector;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Small wrapper around SharedPreferences. The app is intentionally dependency-free,
 * so values (including the Wi-Fi password) are stored in plain app-private prefs.
 */
public final class Prefs {

    public static final int SECURITY_WPA2 = 0;
    public static final int SECURITY_OPEN = 1;
    public static final int SECURITY_WPA3 = 2;

    public static final String DEFAULT_TRIGGER_PACKAGE = "com.spotify.music";

    private static final String FILE = "wifi_connector";
    private static final String KEY_SSID = "ssid";
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_SECURITY = "security";
    private static final String KEY_HIDDEN = "hidden";
    private static final String KEY_SPOTIFY_TRIGGER = "spotify_trigger";
    private static final String KEY_FALLBACK = "fallback";
    private static final String KEY_TARGET_PACKAGE = "target_package";

    private final Context context;

    private Prefs(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Instance-style accessor for code that needs several settings at once. */
    public static Prefs get(Context context) {
        return new Prefs(context);
    }

    private static SharedPreferences sp(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public String getSsid() {
        return getSsid(context);
    }

    public String getPassword() {
        return getPassword(context);
    }

    public int getSecurity() {
        return getSecurity(context);
    }

    public boolean isHidden() {
        return isHidden(context);
    }

    public boolean isSpotifyTriggerEnabled() {
        return isSpotifyTriggerEnabled(context);
    }

    public boolean isFallbackEnabled() {
        return isFallbackEnabled(context);
    }

    public boolean isConfigured() {
        return getSsid().length() > 0;
    }

    public static String getSsid(Context context) {
        return sp(context).getString(KEY_SSID, "");
    }

    public static String getPassword(Context context) {
        String stored = SecretStore.getPassword(context);
        if (stored != null) {
            return stored;
        }
        String legacy = sp(context).getString(KEY_PASSWORD, "");
        if (legacy.length() > 0) {
            // Move the old plain-text value into the encrypted secrets file.
            SecretStore.setPassword(context, legacy);
            sp(context).edit().remove(KEY_PASSWORD).apply();
        }
        return legacy;
    }

    public static int getSecurity(Context context) {
        return sp(context).getInt(KEY_SECURITY, SECURITY_WPA2);
    }

    public static boolean isHidden(Context context) {
        return sp(context).getBoolean(KEY_HIDDEN, false);
    }

    public static boolean isSpotifyTriggerEnabled(Context context) {
        return sp(context).getBoolean(KEY_SPOTIFY_TRIGGER, true);
    }

    public static boolean isFallbackEnabled(Context context) {
        return sp(context).getBoolean(KEY_FALLBACK, false);
    }

    public static boolean isConfigured(Context context) {
        return getSsid(context).length() > 0;
    }

    public static String getTargetPackage(Context context) {
        return sp(context).getString(KEY_TARGET_PACKAGE, DEFAULT_TRIGGER_PACKAGE);
    }

    public static void setTargetPackage(Context context, String packageName) {
        sp(context).edit().putString(KEY_TARGET_PACKAGE, packageName).apply();
    }

    public static void save(Context context, String ssid, String password, int security,
                            boolean hidden, boolean spotifyTrigger, boolean fallback) {
        SecretStore.setPassword(context, password);
        sp(context).edit()
                .putString(KEY_SSID, ssid)
                .putInt(KEY_SECURITY, security)
                .putBoolean(KEY_HIDDEN, hidden)
                .putBoolean(KEY_SPOTIFY_TRIGGER, spotifyTrigger)
                .putBoolean(KEY_FALLBACK, fallback)
                .remove(KEY_PASSWORD)
                .apply();
    }

    public static String securityLabel(int security) {
        switch (security) {
            case SECURITY_OPEN:
                return "Open";
            case SECURITY_WPA3:
                return "WPA3";
            case SECURITY_WPA2:
            default:
                return "WPA/WPA2";
        }
    }
}
