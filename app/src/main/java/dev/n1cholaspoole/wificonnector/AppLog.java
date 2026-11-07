package dev.n1cholaspoole.wificonnector;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Tiny persistent ring-buffer log. It is intentionally simple: the main screen shows
 * the most recent lines, and every new line is broadcast so an open activity can refresh.
 */
public final class AppLog {

    public static final String ACTION_LOG = "dev.n1cholaspoole.wificonnector.LOG";

    private static final String FILE = "wifi_connector_log";
    private static final String KEY_LINES = "lines";
    private static final int MAX_LINES = 250;
    private static final int MAX_CHARS = 24000;

    private static final SimpleDateFormat FORMAT =
            new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);

    private AppLog() {
    }

    public static synchronized void log(Context context, String message) {
        if (context == null || message == null) {
            return;
        }
        Context app = context.getApplicationContext();
        SharedPreferences sp = app.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        String line = FORMAT.format(new Date()) + "  " + message;
        String old = sp.getString(KEY_LINES, "");

        String updated;
        if (old.length() == 0) {
            updated = line;
        } else {
            updated = old + "\n" + line;
        }

        // Trim by line count first.
        String[] lines = updated.split("\n", -1);
        if (lines.length > MAX_LINES) {
            StringBuilder sb = new StringBuilder();
            for (int i = lines.length - MAX_LINES; i < lines.length; i++) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(lines[i]);
            }
            updated = sb.toString();
        }

        // Then guard against a single very long line / lots of short lines.
        if (updated.length() > MAX_CHARS) {
            updated = updated.substring(updated.length() - MAX_CHARS);
            int firstNewline = updated.indexOf('\n');
            if (firstNewline >= 0) {
                updated = updated.substring(firstNewline + 1);
            }
        }

        sp.edit().putString(KEY_LINES, updated).apply();

        Intent intent = new Intent(ACTION_LOG);
        intent.setPackage(app.getPackageName());
        app.sendBroadcast(intent);
    }

    public static synchronized String read(Context context) {
        if (context == null) {
            return "";
        }
        return context.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .getString(KEY_LINES, "");
    }

    public static synchronized void clear(Context context) {
        if (context == null) {
            return;
        }
        Context app = context.getApplicationContext();
        app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit().remove(KEY_LINES).apply();
        Intent intent = new Intent(ACTION_LOG);
        intent.setPackage(app.getPackageName());
        app.sendBroadcast(intent);
    }
}
