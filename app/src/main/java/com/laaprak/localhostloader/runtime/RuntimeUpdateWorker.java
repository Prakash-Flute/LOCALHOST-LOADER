package com.laaprak.localhostloader.runtime;

import android.app.NotificationManager;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.laaprak.localhostloader.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Checks an optional HTTPS release catalogue; updates are reviewed and installed only by the user. */
public final class RuntimeUpdateWorker extends Worker {
    public static final String PREFS = "localhost_loader_settings";
    public static final String KEY_CATALOG = "catalog_url";
    public static final String KEY_CACHE = "catalog_cache";
    public static final String KEY_UPDATE_COUNT = "update_count";

    public RuntimeUpdateWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull @Override public Result doWork() {
        Context context = getApplicationContext();
        String address = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CATALOG, "").trim();
        if (address.isEmpty()) return Result.success();
        HttpURLConnection connection = null;
        try {
            URL url = new URL(address);
            if (!"https".equalsIgnoreCase(url.getProtocol())) return Result.failure();
            connection = openHttps(url, 5);
            connection.setConnectTimeout(12000); connection.setReadTimeout(18000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "LocalhostLoader/1.0");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) return Result.retry();
            if (connection.getContentLengthLong() > 5 * 1024 * 1024) return Result.failure();
            byte[] bytes;
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] b = new byte[8192]; int n; int total = 0;
                while ((n = in.read(b)) != -1) { total += n; if (total > 5 * 1024 * 1024) throw new IllegalStateException("Catalogue is too large"); out.write(b, 0, n); }
                bytes = out.toByteArray();
            }
            JSONObject catalog = new JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            if (catalog.optInt("schema", 0) != 1) return Result.failure();
            JSONArray sourceItems = catalog.optJSONArray("items");
            if (sourceItems == null) sourceItems = new JSONArray();
            Map<String, String> installed = installedVersions(context);
            JSONArray pending = new JSONArray();
            for (int i = 0; i < sourceItems.length(); i++) {
                JSONObject item = sourceItems.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("id", "").trim();
                String version = item.optString("version", "").trim();
                String download = item.optString("download_url", "").trim();
                String digest = item.optString("sha256", "").trim();
                if (id.isEmpty() || version.isEmpty() || download.isEmpty() || !digest.matches("(?i)[a-f0-9]{64}") || !"https".equals(scheme(download))) continue;
                String current = installed.get(id);
                if (current == null || compareVersions(version, current) > 0) pending.put(item);
            }
            JSONObject filtered = new JSONObject(catalog.toString());
            filtered.put("items", pending);
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_CACHE, filtered.toString())
                .putInt(KEY_UPDATE_COUNT, pending.length())
                .putLong("catalog_checked_at", System.currentTimeMillis())
                .apply();
            if (pending.length() > 0) {
                RuntimeNotifications.ensureChannel(context);
                NotificationCompat.Builder builder = new NotificationCompat.Builder(context, RuntimeNotifications.CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle("Localhost Loader · UP&UPG")
                    .setContentText(pending.length() + " update(s) available. Review and install when ready.")
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW);
                NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                if (manager != null) manager.notify(7301, builder.build());
            }
            return Result.success();
        } catch (Exception e) {
            android.util.Log.w("LocalhostLoader", "Update catalogue check failed", e);
            return Result.retry();
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static Map<String, String> installedVersions(Context context) {
        Map<String, String> result = new HashMap<>();
        try {
            JSONArray apps = new JSONArray(context.getSharedPreferences("localhost_loader_registry", Context.MODE_PRIVATE).getString("apps_json", "[]"));
            for (int i = 0; i < apps.length(); i++) {
                JSONObject app = apps.optJSONObject(i);
                if (app != null) { String id = app.optString("id", ""); String version = app.optString("version", ""); if (!id.isEmpty()) result.put(id, version); }
            }
        } catch (Exception ignored) { }
        try {
            JSONObject packs = new JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("installed_pack_versions", "{}"));
            Iterator<String> keys = packs.keys(); while (keys.hasNext()) { String id = keys.next(); result.put(id, packs.optString(id, "")); }
        } catch (Exception ignored) { }
        return result;
    }

    private static HttpURLConnection openHttps(URL start, int maxRedirects) throws Exception {
        URL current = start;
        for (int attempt = 0; attempt <= maxRedirects; attempt++) {
            if (!"https".equalsIgnoreCase(current.getProtocol())) throw new SecurityException("Catalogue redirects must remain HTTPS.");
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setConnectTimeout(12000); connection.setReadTimeout(18000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "LocalhostLoader/1.0");
            int code = connection.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = connection.getHeaderField("Location"); connection.disconnect();
                if (location == null || attempt == maxRedirects) throw new IllegalStateException("Too many or invalid catalogue redirects.");
                current = new URL(current, location); continue;
            }
            return connection;
        }
        throw new IllegalStateException("Too many catalogue redirects.");
    }

    private static String scheme(String value) { try { return new URL(value).getProtocol(); } catch (Exception e) { return ""; } }

    private static int compareVersions(String left, String right) {
        String cleanA = left.replaceFirst("^[vV]", "").split("[+]", 2)[0];
        String cleanB = right.replaceFirst("^[vV]", "").split("[+]", 2)[0];
        String[] a = cleanA.split("-", 2); String[] b = cleanB.split("-", 2);
        String[] ac = a[0].split("[.]", -1); String[] bc = b[0].split("[.]", -1);
        for (int i = 0; i < Math.max(ac.length, bc.length); i++) {
            long x = i < ac.length ? parseNumeric(ac[i]) : 0; long y = i < bc.length ? parseNumeric(bc[i]) : 0;
            int cmp = Long.compare(x, y); if (cmp != 0) return cmp;
        }
        if (a.length == 1 && b.length == 1) return 0;
        if (a.length == 1) return 1;
        if (b.length == 1) return -1;
        String[] ap = a[1].split("[.]"), bp = b[1].split("[.]");
        for (int i = 0; i < Math.max(ap.length, bp.length); i++) {
            if (i >= ap.length) return -1; if (i >= bp.length) return 1;
            int cmp;
            try { cmp = Long.compare(Long.parseLong(ap[i]), Long.parseLong(bp[i])); }
            catch (NumberFormatException e) {
                try { Long.parseLong(ap[i]); cmp = -1; } catch (NumberFormatException ignored) {
                    try { Long.parseLong(bp[i]); cmp = 1; } catch (NumberFormatException ignored2) { cmp = ap[i].compareToIgnoreCase(bp[i]); }
                }
            }
            if (cmp != 0) return cmp;
        }
        return 0;
    }

    private static long parseNumeric(String value) {
        try { return Long.parseLong(value); } catch (Exception e) { return 0; }
    }
}
