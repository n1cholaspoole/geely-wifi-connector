package dev.n1cholaspoole.wificonnector;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Restarts the always-on watcher after a reboot or an app update.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        if (!Prefs.isFallbackEnabled(context) || !Prefs.isConfigured(context)) {
            return;
        }
        Intent service = new Intent(context, WifiAutoConnectService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
            AppLog.log(context, "Always-connect service restarted after " + action + ".");
        } catch (Exception e) {
            AppLog.log(context, "Could not restart always-connect service: "
                    + e.getClass().getSimpleName());
        }
    }
}
