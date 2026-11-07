package dev.n1cholaspoole.wificonnector;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQUEST_PERMISSIONS = 100;
    private static final String[] SECURITY_LABELS = {
            "WPA/WPA2", "Open", "WPA3 (Android 10+)"
    };

    private EditText ssidInput;
    private EditText passwordInput;
    private Spinner securitySpinner;
    private CheckBox hiddenCheck;
    private CheckBox spotifyCheck;
    private CheckBox fallbackCheck;
    private TextView statusText;
    private TextView logText;
    private Button addNetworksButton;

    private BroadcastReceiver logReceiver;
    private boolean logReceiverRegistered = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ssidInput = findViewById(R.id.ssid_input);
        passwordInput = findViewById(R.id.password_input);
        securitySpinner = findViewById(R.id.security_spinner);
        hiddenCheck = findViewById(R.id.hidden_check);
        spotifyCheck = findViewById(R.id.spotify_check);
        fallbackCheck = findViewById(R.id.fallback_check);
        statusText = findViewById(R.id.status_text);
        logText = findViewById(R.id.log_text);
        addNetworksButton = findViewById(R.id.add_networks_button);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, SECURITY_LABELS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        securitySpinner.setAdapter(adapter);

        loadPrefs();
        setupListeners();

        if (Build.VERSION.SDK_INT >= 30) {
            addNetworksButton.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerLogReceiver();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        refreshLog();
    }

    @Override
    protected void onStop() {
        unregisterLogReceiver();
        super.onStop();
    }

    private void loadPrefs() {
        ssidInput.setText(Prefs.getSsid(this));
        passwordInput.setText(Prefs.getPassword(this));
        int security = Prefs.getSecurity(this);
        if (security < 0 || security >= SECURITY_LABELS.length) {
            security = Prefs.SECURITY_WPA2;
        }
        securitySpinner.setSelection(security);
        hiddenCheck.setChecked(Prefs.isHidden(this));
        spotifyCheck.setChecked(Prefs.isSpotifyTriggerEnabled(this));
        fallbackCheck.setChecked(Prefs.isFallbackEnabled(this));
    }

    private void setupListeners() {
        findViewById(R.id.save_button).setOnClickListener(
                v -> saveAndConnect(true));
        findViewById(R.id.save_only_button).setOnClickListener(
                v -> saveAndConnect(false));
        findViewById(R.id.permissions_button).setOnClickListener(
                v -> requestRuntimePermissions());
        findViewById(R.id.accessibility_button).setOnClickListener(
                v -> openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        findViewById(R.id.wifi_settings_button).setOnClickListener(
                v -> openSettings(Settings.ACTION_WIFI_SETTINGS));
        findViewById(R.id.location_settings_button).setOnClickListener(
                v -> openSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
        findViewById(R.id.clear_log_button).setOnClickListener(v -> {
            AppLog.clear(this);
            refreshLog();
        });
        addNetworksButton.setOnClickListener(v -> addViaSystemDialog());
    }

    private void saveAndConnect(boolean connectNow) {
        if (!saveSettings()) {
            return;
        }
        if (connectNow) {
            if (!hasFineLocationPermission()) {
                toast("Grant location permission, then tap Save & connect again.");
                requestRuntimePermissions();
            } else {
                WifiConnector.connect(this, "Manual");
            }
        }
        refreshStatus();
        refreshLog();
    }

    private boolean saveSettings() {
        return saveSettings(true);
    }

    private boolean saveSettings(boolean promptAccessibility) {
        String ssid = ssidInput.getText().toString().trim();
        if (ssid.length() == 0) {
            toast("Enter the Wi-Fi network name (SSID) first.");
            ssidInput.requestFocus();
            return false;
        }

        int security = securitySpinner.getSelectedItemPosition();
        if (security < 0 || security >= SECURITY_LABELS.length) {
            security = Prefs.SECURITY_WPA2;
        }
        String password = passwordInput.getText().toString();

        if (security != Prefs.SECURITY_OPEN
                && (password.length() < 8 || password.length() > 63)) {
            toast("WPA/WPA2/WPA3 passwords must be 8-63 characters long.");
            passwordInput.requestFocus();
            return false;
        }
        if (security == Prefs.SECURITY_WPA3 && Build.VERSION.SDK_INT < 29) {
            toast("WPA3 requires Android 10 or newer. Choose WPA/WPA2 on this device.");
            return false;
        }

        boolean hidden = hiddenCheck.isChecked();
        boolean spotify = spotifyCheck.isChecked();
        boolean fallback = fallbackCheck.isChecked();

        Prefs.save(this, ssid, password, security, hidden, spotify, fallback);
        AppLog.log(this, "Settings saved: SSID=\"" + ssid + "\", security="
                + Prefs.securityLabel(security) + ", Spotify trigger=" + spotify
                + ", always-connect=" + fallback);

        if (fallback) {
            startAutoService();
        } else {
            stopAutoService();
        }

        if (promptAccessibility && spotify && !isAccessibilityServiceEnabled()) {
            showAccessibilityDialog();
        }

        refreshStatus();
        return true;
    }

    private void addViaSystemDialog() {
        if (!saveSettings(false)) {
            return;
        }
        Prefs prefs = Prefs.get(this);
        boolean opened = WifiConnector.launchAddNetworksDialog(this, prefs);
        toast(opened
                ? "Approve the Wi-Fi network in the Android dialog."
                : "Could not open Android's Wi-Fi dialog on this device.");
        refreshStatus();
        refreshLog();
    }

    private void requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            toast("Permissions are granted at install time on this Android version.");
            return;
        }
        List<String> permissions = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (permissions.isEmpty()) {
            toast("Location and notification permissions are already granted.");
            return;
        }
        requestPermissions(permissions.toArray(new String[0]), REQUEST_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_PERMISSIONS) {
            return;
        }
        boolean fineGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        AppLog.log(this, "Permission result: location=" + (fineGranted ? "granted" : "denied"));
        if (!fineGranted) {
            toast("Location permission is required to scan for and connect to Wi-Fi networks.");
        }
        refreshStatus();
        refreshLog();
    }

    private boolean isAccessibilityServiceEnabled() {
        if (Settings.Secure.getInt(getContentResolver(),
                Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
            return false;
        }
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) {
            return false;
        }
        ComponentName component = new ComponentName(this, SpotifyAccessibilityService.class);
        String flat = component.flattenToString();
        String shortFlat = component.flattenToShortString();
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);
        while (splitter.hasNext()) {
            String item = splitter.next();
            if (item.equalsIgnoreCase(flat) || item.equalsIgnoreCase(shortFlat)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasFineLocationPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isLocationEnabled() {
        LocationManager manager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (manager == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= 28) {
            return manager.isLocationEnabled();
        }
        int mode = Settings.Secure.getInt(getContentResolver(),
                Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF);
        return mode != Settings.Secure.LOCATION_MODE_OFF;
    }

    private void refreshStatus() {
        Prefs prefs = Prefs.get(this);
        boolean accessibility = isAccessibilityServiceEnabled();
        boolean spotifyEffective = prefs.isSpotifyTriggerEnabled() && accessibility;
        String connected = WifiConnector.getConnectedSsid(this);

        StringBuilder sb = new StringBuilder();
        sb.append("Network: ")
                .append(prefs.isConfigured() ? prefs.getSsid() : "not configured")
                .append('\n');
        sb.append("Security: ").append(Prefs.securityLabel(prefs.getSecurity())).append('\n');
        sb.append("Spotify detection: ")
                .append(spotifyEffective ? "ON" : "OFF")
                .append(prefs.isSpotifyTriggerEnabled()
                        ? (accessibility ? "" : " (enable the accessibility service)")
                        : " (disabled in app)")
                .append('\n');
        sb.append("Always-connect: ")
                .append(prefs.isFallbackEnabled() ? "ON" : "OFF")
                .append('\n');
        sb.append("Wi-Fi radio: ")
                .append(WifiConnector.isWifiEnabled(this) ? "on" : "off")
                .append('\n');
        sb.append("Connected to: ")
                .append(connected == null ? "not connected" : connected)
                .append('\n');
        sb.append("Location permission: ")
                .append(hasFineLocationPermission() ? "granted" : "missing")
                .append('\n');
        sb.append("Location services: ")
                .append(isLocationEnabled() ? "on" : "off");
        statusText.setText(sb.toString());
    }

    private void refreshLog() {
        String log = AppLog.read(this);
        logText.setText(log.length() == 0 ? getString(R.string.no_events) : log);
    }

    private void showAccessibilityDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Enable Spotify detection")
                .setMessage("Android needs an accessibility service to know when Spotify "
                        + "opens. Enable \"Spotify Wi-Fi Connector\" under Settings > "
                        + "Accessibility. The service only checks which app is in the "
                        + "foreground; it never reads screen content.")
                .setPositiveButton("Open accessibility settings",
                        (dialog, which) -> openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                .setNegativeButton("Later", null)
                .show();
    }

    private void openSettings(String action) {
        try {
            startActivity(new Intent(action));
        } catch (Exception e) {
            toast("Could not open Android settings on this device.");
            AppLog.log(this, "Could not open settings action " + action + ": "
                    + e.getClass().getSimpleName());
        }
    }

    private void startAutoService() {
        Intent intent = new Intent(this, WifiAutoConnectService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            AppLog.log(this, "Always-connect watcher requested.");
        } catch (Exception e) {
            AppLog.log(this, "Could not start always-connect watcher: "
                    + e.getClass().getSimpleName());
            toast("Could not start the always-connect watcher. Check notification permission.");
        }
    }

    private void stopAutoService() {
        try {
            stopService(new Intent(this, WifiAutoConnectService.class));
        } catch (Exception e) {
            AppLog.log(this, "Could not stop always-connect watcher: "
                    + e.getClass().getSimpleName());
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerLogReceiver() {
        if (logReceiverRegistered) {
            return;
        }
        if (logReceiver == null) {
            logReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    refreshLog();
                    refreshStatus();
                }
            };
        }
        IntentFilter filter = new IntentFilter(AppLog.ACTION_LOG);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(logReceiver, filter);
        }
        logReceiverRegistered = true;
    }

    private void unregisterLogReceiver() {
        if (!logReceiverRegistered) {
            return;
        }
        try {
            unregisterReceiver(logReceiver);
        } catch (IllegalArgumentException ignored) {
            // Already unregistered.
        }
        logReceiverRegistered = false;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
