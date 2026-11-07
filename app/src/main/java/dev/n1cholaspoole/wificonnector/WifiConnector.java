package dev.n1cholaspoole.wificonnector;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Connects to the configured network.
 *
 * <p>Two paths are used:</p>
 * <ul>
 *   <li>API 21-28 (and Android 10+ because targetSdk is 28): the legacy
 *       {@link WifiManager#addNetwork(WifiConfiguration)} + {@link WifiManager#enableNetwork}
 *       path. This gives an immediate, system-wide connection.</li>
 *   <li>API 29+: if the legacy path is rejected (some OEM builds enforce the newer
 *       restrictions anyway), the app adds a {@link android.net.wifi.WifiNetworkSuggestion}.
 *       Android owns the actual connection and may ask the user to approve the network once.</li>
 * </ul>
 */
@SuppressWarnings("deprecation")
public final class WifiConnector {

    private static final long ENABLE_RETRY_DELAY_MS = 3000L;
    private static final long ENABLE_RETRY_GUARD_MS = 15000L;

    private static long lastEnableAttempt = 0L;
    private static boolean retryScheduled = false;

    private WifiConnector() {
    }

    /**
     * Attempts to connect to the network saved in {@link Prefs}.
     *
     * @param reason short description used in the log, e.g. "Spotify opened"
     * @return a human-readable status message (also written to the app log)
     */
    public static String connect(Context context, String reason) {
        if (context == null) {
            return "No context";
        }
        Context app = context.getApplicationContext();
        String ssid = Prefs.getSsid(app);
        if (ssid.length() == 0) {
            String message = "No Wi-Fi network configured. Open the app and enter the network name.";
            AppLog.log(app, message);
            return message;
        }

        WifiManager wifi = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) {
            String message = "This device does not expose a Wi-Fi manager.";
            AppLog.log(app, message);
            return message;
        }

        if (!hasLocationPermission(app)) {
            String message = "Location permission is required for Wi-Fi scanning/connecting. "
                    + "Open the app and grant it.";
            AppLog.log(app, message);
            return message;
        }

        if (!wifi.isWifiEnabled()) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastEnableAttempt > ENABLE_RETRY_GUARD_MS) {
                lastEnableAttempt = now;
                boolean enabled = false;
                try {
                    // Works on API 21-28, and on Android 10+ because targetSdk is 28.
                    enabled = wifi.setWifiEnabled(true);
                } catch (Exception e) {
                    AppLog.log(app, "Android refused to enable Wi-Fi: " + e.getMessage());
                }
                if (enabled) {
                    String message = "Wi-Fi was off; enabling it and retrying in 3 seconds...";
                    AppLog.log(app, message);
                    scheduleEnableRetry(app, reason);
                    return message;
                }
                String message = "Wi-Fi is off and this app is not allowed to turn it on. "
                        + "Turn Wi-Fi on in Android settings.";
                AppLog.log(app, message);
                return message;
            }
            return "Wi-Fi is off";
        }

        String connectedSsid = getConnectedSsid(wifi);
        if (ssid.equals(connectedSsid)) {
            String message = "Already connected to \"" + ssid + "\".";
            AppLog.log(app, message);
            return message;
        }

        String legacyResult = tryLegacy(app, wifi, ssid, reason);
        if (legacyResult != null) {
            AppLog.log(app, legacyResult);
            return legacyResult;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            String suggestionResult = Api29.addSuggestion(app, wifi);
            AppLog.log(app, suggestionResult);
            return suggestionResult;
        }

        String message = "Could not connect automatically. Turn on Wi-Fi and connect to \""
                + ssid + "\" in Android settings.";
        AppLog.log(app, message);
        return message;
    }

    public static boolean hasLocationPermission(Context context) {
        return context != null && (Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED);
    }

    public static boolean isWifiEnabled(Context context) {
        if (context == null) {
            return false;
        }
        WifiManager wifi = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        return wifi != null && wifi.isWifiEnabled();
    }

    public static boolean isConnectedToTarget(Context context) {
        if (context == null) {
            return false;
        }
        String ssid = Prefs.getSsid(context);
        return ssid.length() > 0 && ssid.equals(getConnectedSsid(context));
    }

    public static String getConnectedSsid(Context context) {
        if (context == null) {
            return null;
        }
        WifiManager wifi = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        return wifi == null ? null : getConnectedSsid(wifi);
    }

    /**
     * Returns true when the configured SSID appears in the latest scan results.
     * Requires location permission and (on most versions) location services to be on.
     */
    @SuppressLint("MissingPermission")
    public static boolean isTargetVisible(Context context) {
        if (context == null) {
            return false;
        }
        String target = Prefs.getSsid(context);
        if (target.length() == 0) {
            return false;
        }
        WifiManager wifi = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) {
            return false;
        }
        try {
            List<ScanResult> results = wifi.getScanResults();
            if (results == null) {
                return false;
            }
            for (ScanResult result : results) {
                if (result != null && target.equals(result.SSID)) {
                    return true;
                }
            }
        } catch (Exception e) {
            AppLog.log(context, "Could not read scan results: location permission is missing.");
        }
        return false;
    }

    public static boolean requestScan(Context context) {
        if (context == null) {
            return false;
        }
        WifiManager wifi = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifi == null || !wifi.isWifiEnabled()) {
            return false;
        }
        try {
            return wifi.startScan();
        } catch (Exception e) {
            AppLog.log(context, "Could not start a Wi-Fi scan: location permission is missing.");
            return false;
        }
    }

    /**
     * Opens Android's own "add network" approval dialog on API 30+.
     * On API 29 it adds a network suggestion instead.
     */
    public static boolean launchAddNetworksDialog(Activity activity, Prefs prefs) {
        if (activity == null || prefs == null || prefs.getSsid().length() == 0) {
            return false;
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                return Api30.launch(activity, prefs);
            }
            if (Build.VERSION.SDK_INT == 29) {
                WifiManager wifi = (WifiManager) activity.getApplicationContext()
                        .getSystemService(Context.WIFI_SERVICE);
                if (wifi == null) {
                    return false;
                }
                String result = Api29.addSuggestion(activity.getApplicationContext(), wifi);
                AppLog.log(activity, result);
                return true;
            }
        } catch (Throwable t) {
            AppLog.log(activity, "Could not open Android's Wi-Fi suggestion dialog: "
                    + t.getClass().getSimpleName());
        }
        return false;
    }

    private static String tryLegacy(Context app, WifiManager wifi, String ssid, String reason) {
        Prefs prefs = Prefs.get(app);
        if (prefs.getSecurity() == Prefs.SECURITY_WPA3 && Build.VERSION.SDK_INT >= 29) {
            // WPA3 is handled by WifiNetworkSuggestion on Android 10+.
            return null;
        }

        WifiConfiguration desired = buildLegacyConfig(prefs);
        if (desired == null) {
            AppLog.log(app, "WPA3 networks require Android 10 or newer.");
            return null;
        }

        WifiConfiguration existing = findConfiguredNetwork(wifi, ssid);
        int networkId = -1;
        if (existing != null) {
            networkId = existing.networkId;
            desired.networkId = existing.networkId;
            try {
                int updated = wifi.updateNetwork(desired);
                if (updated != -1) {
                    networkId = updated;
                } else {
                    AppLog.log(app, "Could not update the saved credentials; using the "
                            + "existing saved network for \"" + ssid + "\".");
                }
            } catch (Exception ignored) {
                // Keep the existing network id; the saved password may still be correct.
            }
        } else {
            try {
                networkId = wifi.addNetwork(desired);
            } catch (Exception e) {
                AppLog.log(app, "Android refused to save the Wi-Fi network: " + e.getMessage());
                return null;
            }
        }

        if (networkId == -1) {
            AppLog.log(app, "Android rejected the legacy Wi-Fi configuration for \"" + ssid + "\".");
            return null;
        }

        try {
            wifi.disconnect();
            boolean enabled = wifi.enableNetwork(networkId, true);
            wifi.reconnect();
            if (!enabled) {
                AppLog.log(app, "Android did not allow enabling the saved Wi-Fi network.");
                return null;
            }
        } catch (Exception e) {
            AppLog.log(app, "Android refused to enable the Wi-Fi network: " + e.getMessage());
            return null;
        }

        return "Requested connection to \"" + ssid + "\" (" + reason + ").";
    }

    private static WifiConfiguration buildLegacyConfig(Prefs prefs) {
        if (prefs.getSecurity() == Prefs.SECURITY_WPA3) {
            return null;
        }
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = quote(prefs.getSsid());
        config.hiddenSSID = prefs.isHidden();
        config.priority = 1;
        if (prefs.getSecurity() == Prefs.SECURITY_OPEN) {
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        } else {
            config.preSharedKey = quote(prefs.getPassword());
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        }
        return config;
    }

    @SuppressLint("MissingPermission")
    private static WifiConfiguration findConfiguredNetwork(WifiManager wifi, String ssid) {
        try {
            List<WifiConfiguration> configs = wifi.getConfiguredNetworks();
            if (configs == null) {
                return null;
            }
            for (WifiConfiguration config : configs) {
                if (config == null) {
                    continue;
                }
                if (ssid.equals(stripQuotes(config.SSID))) {
                    return config;
                }
            }
        } catch (Exception ignored) {
            // Adding a new configuration may still work.
        }
        return null;
    }

    private static String getConnectedSsid(WifiManager wifi) {
        try {
            WifiInfo info = wifi.getConnectionInfo();
            if (info == null) {
                return null;
            }
            String ssid = stripQuotes(info.getSSID());
            if (ssid == null || ssid.length() == 0 || "<unknown ssid>".equals(ssid)) {
                return null;
            }
            return ssid;
        } catch (Exception e) {
            return null;
        }
    }

    private static void scheduleEnableRetry(final Context app, final String reason) {
        if (retryScheduled) {
            return;
        }
        retryScheduled = true;
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                retryScheduled = false;
                connect(app, reason + " retry");
            }
        }, ENABLE_RETRY_DELAY_MS);
    }

    private static String quote(String value) {
        String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + escaped + "\"";
    }

    private static String stripQuotes(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() >= 2 && value.charAt(0) == '"'
                && value.charAt(value.length() - 1) == '"') {
            String inner = value.substring(1, value.length() - 1);
            return inner.replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return value;
    }

    @android.annotation.TargetApi(29)
    private static final class Api29 {

        private Api29() {
        }

        static android.net.wifi.WifiNetworkSuggestion buildSuggestion(Prefs prefs) {
            if (prefs.getSsid().length() == 0) {
                return null;
            }
            android.net.wifi.WifiNetworkSuggestion.Builder builder =
                    new android.net.wifi.WifiNetworkSuggestion.Builder()
                            .setSsid(prefs.getSsid());
            if (prefs.getSecurity() == Prefs.SECURITY_OPEN) {
                // No passphrase is set, which makes the suggestion an open network.
            } else if (prefs.getSecurity() == Prefs.SECURITY_WPA3) {
                builder.setWpa3Passphrase(prefs.getPassword());
            } else {
                builder.setWpa2Passphrase(prefs.getPassword());
            }
            if (prefs.isHidden()) {
                setIsHiddenSsidIfSupported(builder);
            }
            return builder.build();
        }

        private static void setIsHiddenSsidIfSupported(
                android.net.wifi.WifiNetworkSuggestion.Builder builder) {
            if (Build.VERSION.SDK_INT < 30) {
                return;
            }
            try {
                builder.getClass()
                        .getMethod("setIsHiddenSsid", boolean.class)
                        .invoke(builder, true);
            } catch (Exception ignored) {
                // Hidden-SSID suggestions are only available on Android 11+.
            }
        }

        static String addSuggestion(Context app, WifiManager wifi) {
            android.net.wifi.WifiNetworkSuggestion suggestion = buildSuggestion(Prefs.get(app));
            if (suggestion == null) {
                return "Could not build a Wi-Fi network suggestion.";
            }
            int status;
            try {
                status = wifi.addNetworkSuggestions(Collections.singletonList(suggestion));
            } catch (Exception e) {
                return "Android refused the Wi-Fi suggestion: " + e.getMessage();
            }
            switch (status) {
                case WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS:
                    return "Wi-Fi suggestion added. Android will connect automatically when the "
                            + "network is in range. If Android asks, allow the suggested network.";
                case WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE:
                    return "Wi-Fi suggestion already exists. Android will connect automatically "
                            + "when the network is in range.";
                case WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED:
                    return "Android blocked Wi-Fi suggestions for this app. Connect manually in "
                            + "Android Wi-Fi settings.";
                case WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_EXCEEDS_MAX_PER_APP:
                    return "Android reports too many Wi-Fi suggestions for this app.";
                case WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_INTERNAL:
                    return "Android returned an internal error while adding the Wi-Fi suggestion.";
                case WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_NOT_ALLOWED:
                    return "Android did not allow adding the Wi-Fi suggestion.";
                default:
                    return "Wi-Fi suggestion result code: " + status;
            }
        }
    }

    @android.annotation.TargetApi(30)
    private static final class Api30 {

        private Api30() {
        }

        static boolean launch(Activity activity, Prefs prefs) {
            android.net.wifi.WifiNetworkSuggestion suggestion = Api29.buildSuggestion(prefs);
            if (suggestion == null) {
                return false;
            }
            ArrayList<android.net.wifi.WifiNetworkSuggestion> list = new ArrayList<>();
            list.add(suggestion);
            Intent intent = new Intent("android.settings.WIFI_ADD_NETWORKS");
            intent.putParcelableArrayListExtra("android.provider.extra.WIFI_NETWORK_LIST", list);
            try {
                activity.startActivity(intent);
                AppLog.log(activity, "Opened Android's Wi-Fi network approval dialog.");
                return true;
            } catch (Exception e) {
                AppLog.log(activity, "Could not open Android's Wi-Fi approval dialog: "
                        + e.getClass().getSimpleName());
                return false;
            }
        }
    }
}
