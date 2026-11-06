package com.laaprak.localhostloader;

import android.app.Activity;
import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.laaprak.localhostloader.runtime.LocalGatewayService;
import com.laaprak.localhostloader.runtime.NpmLockInstaller;
import com.laaprak.localhostloader.runtime.NodeRuntimeService;
import com.laaprak.localhostloader.runtime.PersistentVault;
import com.laaprak.localhostloader.runtime.PythonRuntimeService;
import com.laaprak.localhostloader.runtime.RuntimeNotifications;
import com.laaprak.localhostloader.runtime.RuntimePackInstaller;
import com.laaprak.localhostloader.runtime.RuntimeServiceSlots;
import com.laaprak.localhostloader.runtime.RuntimeState;
import com.laaprak.localhostloader.runtime.RuntimeUpdateWorker;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Android-native Localhost Loader: package manager, embedded runtimes and named local-app browser. */
public class MainActivity extends Activity {
    private static final int REQ_PICK_PACKAGE = 401;
    private static final int REQ_PICK_VAULT = 402;
    private static final int REQ_PICK_PACK = 403;
    private static final int REQ_PICK_FOLDER = 404;
    private static final String PREFS = "localhost_loader_registry";
    private static final String PREF_APPS = "apps_json";
    private static final long MAX_DOWNLOAD_BYTES = 300L * 1024L * 1024L;

    private final ArrayList<LocalApp> installedApps = new ArrayList<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SharedPreferences preferences;
    private LinearLayout root;
    private FrameLayout page;
    private WebView webView;
    private LocalApp activeApp;
    private String currentScreen = "apps";
    private TextView headerStatus;
    private volatile boolean stateReceiverRegistered = false;
    private final BroadcastReceiver runtimeStateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String id = intent.getStringExtra(RuntimeState.EXTRA_APP_ID);
            String state = intent.getStringExtra(RuntimeState.EXTRA_STATE);
            String message = intent.getStringExtra(RuntimeState.EXTRA_MESSAGE);
            if (id == null || state == null) return;
            for (LocalApp app : installedApps) if (id.equals(app.id)) { app.status = state; app.lastMessage = message == null ? "" : message; break; }
            if ("STOPPED".equals(state) || "FAILED".equals(state)) {
                RuntimeServiceSlots.release(MainActivity.this, "python", id);
                RuntimeServiceSlots.release(MainActivity.this, "node", id);
                if ("FAILED".equals(state)) unregisterGateway(id);
            }
            saveRegistry();
            if ("apps".equals(currentScreen)) render("apps");
            if ("browser".equals(currentScreen) && activeApp != null && id.equals(activeApp.id) && "FAILED".equals(state)) {
                new AlertDialog.Builder(MainActivity.this).setTitle(activeApp.name + " stopped")
                    .setMessage(message == null ? "The local server failed." : message)
                    .setPositiveButton("OK", (d, w) -> closeActiveApp()).show();
            }
        }
    };

    private final int bg = Color.rgb(10, 17, 25);
    private final int panel = Color.rgb(18, 29, 41);
    private final int panel2 = Color.rgb(23, 37, 51);
    private final int mint = Color.rgb(112, 224, 192);
    private final int white = Color.rgb(241, 247, 248);
    private final int muted = Color.rgb(151, 169, 181);
    private final int line = Color.rgb(43, 61, 76);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        w.setStatusBarColor(bg);
        w.setNavigationBarColor(bg);
        if (Build.VERSION.SDK_INT >= 30) w.setDecorFitsSystemWindows(true);
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        restoreLocalVaultCaches();
        loadRegistry();
        scheduleUpdateChecks();
        requestNotificationPermission();
        render("apps");
    }

    @Override protected void onStart() {
        super.onStart();
        if (!stateReceiverRegistered) {
            IntentFilter filter = new IntentFilter(RuntimeState.ACTION_STATE);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(runtimeStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(runtimeStateReceiver, filter);
            stateReceiverRegistered = true;
        }
    }

    @Override protected void onStop() {
        if (stateReceiverRegistered) {
            try { unregisterReceiver(runtimeStateReceiver); } catch (Exception ignored) { }
            stateReceiverRegistered = false;
        }
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (webView != null) { webView.stopLoading(); webView.destroy(); webView = null; }
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if ("browser".equals(currentScreen) && webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        if ("browser".equals(currentScreen)) {
            closeActiveApp();
            return;
        }
        if (!"apps".equals(currentScreen)) {
            render("apps");
            return;
        }
        super.onBackPressed();
    }

    private void render(String screen) {
        currentScreen = screen;
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(18), dp(16), dp(18), dp(14));
        header.setBackgroundColor(bg);

        TextView brandMark = text("LL", 15, mint, true);
        brandMark.setGravity(Gravity.CENTER);
        brandMark.setBackground(round(mint, 13, mint));
        brandMark.setTextColor(bg);
        header.addView(brandMark, new LinearLayout.LayoutParams(dp(38), dp(38)));

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.setPadding(dp(12), 0, 0, 0);
        TextView title = text("LOCALHOST LOADER", 14, white, true);
        TextView subtitle = text("LOCAL APP ENVIRONMENT", 9, muted, true);
        subtitle.setLetterSpacing(0.12f);
        brand.addView(title);
        brand.addView(subtitle);
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));

        headerStatus = text("PREVIEW ENGINE", 9, mint, true);
        headerStatus.setGravity(Gravity.CENTER);
        headerStatus.setPadding(dp(10), dp(7), dp(10), dp(7));
        headerStatus.setBackground(round(panel2, 30, line));
        header.addView(headerStatus);
        root.addView(header);

        page = new FrameLayout(this);
        root.addView(page, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(10), dp(8), dp(10), dp(10));
        nav.setBackgroundColor(Color.rgb(12, 21, 30));
        nav.addView(navButton("APPS", "apps"), new LinearLayout.LayoutParams(0, dp(48), 1));
        nav.addView(navButton("BROWSER", "browser"), new LinearLayout.LayoutParams(0, dp(48), 1));
        nav.addView(navButton("ENGINE", "runtimes"), new LinearLayout.LayoutParams(0, dp(48), 1));
        nav.addView(navButton("STORE", "store"), new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(nav);
        setContentView(root);

        if ("apps".equals(screen)) renderAppsPage();
        else if ("browser".equals(screen)) renderBrowserPage();
        else if ("store".equals(screen)) renderStorePage();
        else renderRuntimePage();
    }

    private View navButton(String label, String screen) {
        TextView b = text(label, 10, currentScreen.equals(screen) ? mint : muted, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(round(currentScreen.equals(screen) ? panel2 : bg, 12, currentScreen.equals(screen) ? line : bg));
        b.setOnClickListener(v -> {
            if ("browser".equals(screen) && activeApp == null) {
                Toast.makeText(this, "Open a local app to use the browser", Toast.LENGTH_SHORT).show();
            } else {
                render(screen);
            }
        });
        return b;
    }

    private void renderAppsPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = column(16);
        body.setPadding(dp(18), dp(10), dp(18), dp(24));

        LinearLayout hero = column(10);
        hero.setPadding(dp(18), dp(20), dp(18), dp(18));
        hero.setBackground(round(panel, 20, line));
        TextView eyebrow = text("YOUR DEVICE. YOUR LOCAL APPS.", 10, mint, true);
        eyebrow.setLetterSpacing(0.08f);
        hero.addView(eyebrow);
        hero.addView(text("Install once. Open by name.", 23, white, true));
        hero.addView(text("Import a loader package. The engine scans it, prepares its runtime, starts the server and opens it here—without asking you to type commands or remember ports.", 12, muted, false));
        int runningCount = 0;
        for (LocalApp app : installedApps) if ("RUNNING".equals(app.status) || "STARTING".equals(app.status) || "INSTALLING".equals(app.status)) runningCount++;
        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        stats.addView(statBox(String.valueOf(installedApps.size()), "INSTALLED"), new LinearLayout.LayoutParams(0, dp(66), 1));
        stats.addView(new View(this), new LinearLayout.LayoutParams(dp(10), 1));
        stats.addView(statBox(String.valueOf(runningCount), "ACTIVE APPS"), new LinearLayout.LayoutParams(0, dp(66), 1));
        stats.addView(new View(this), new LinearLayout.LayoutParams(dp(10), 1));
        stats.addView(statBox(PersistentVault.isAttached(this) ? "ON" : "OFF", "VAULT"), new LinearLayout.LayoutParams(0, dp(66), 1));
        hero.addView(stats);
        body.addView(hero);

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        Button importFile = button("IMPORT PACKAGE", mint, bg);
        importFile.setOnClickListener(v -> pickPackage());
        actionRow.addView(importFile, new LinearLayout.LayoutParams(0, dp(50), 1));
        actionRow.addView(new View(this), new LinearLayout.LayoutParams(dp(10), 1));
        Button importLink = button("PASTE LINK", panel2, white);
        importLink.setOnClickListener(v -> showLinkDialog());
        actionRow.addView(importLink, new LinearLayout.LayoutParams(0, dp(50), 1));
        body.addView(actionRow);
        Button importFolder = button("IMPORT SERVER FOLDER", panel2, white);
        importFolder.setOnClickListener(v -> pickFolder());
        body.addView(importFolder);

        LinearLayout section = new LinearLayout(this);
        section.setGravity(Gravity.CENTER_VERTICAL);
        section.addView(text("Installed apps", 18, white, true), new LinearLayout.LayoutParams(0, -2, 1));
        section.addView(text(installedApps.size() + " PACKAGES", 9, muted, true));
        body.addView(section);

        if (installedApps.isEmpty()) {
            LinearLayout empty = column(9);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(20), dp(34), dp(20), dp(34));
            empty.setBackground(round(panel, 18, line));
            TextView icon = text("＋", 32, mint, true); icon.setGravity(Gravity.CENTER); empty.addView(icon);
            TextView emptyTitle = text("Nothing installed yet", 16, white, true); emptyTitle.setGravity(Gravity.CENTER); empty.addView(emptyTitle);
            TextView emptyText = text("Choose a .zip/.llpack package or paste an HTTPS package link. Scanning itself works offline.", 12, muted, false); emptyText.setGravity(Gravity.CENTER); empty.addView(emptyText);
            body.addView(empty);
        } else {
            for (LocalApp app : installedApps) body.addView(appCard(app));
        }

        TextView foot = text("LOCALHOST LOADER  •  ANDROID RUNTIME ECOSYSTEM", 9, muted, true);
        foot.setGravity(Gravity.CENTER);
        body.addView(foot);
        scroll.addView(body);
        page.addView(scroll);
    }

    private View statBox(String value, String label) {
        LinearLayout box = column(3);
        box.setPadding(dp(12), dp(9), dp(12), dp(8));
        box.setBackground(round(panel2, 12, line));
        TextView number = text(value, 20, white, true);
        box.addView(number);
        box.addView(text(label, 8, muted, true));
        return box;
    }

    private View appCard(LocalApp app) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(13), dp(12), dp(13));
        card.setBackground(round(panel, 16, line));

        TextView appIcon = text(initials(app.name), 15, mint, true);
        appIcon.setGravity(Gravity.CENTER); appIcon.setBackground(round(panel2, 12, line));
        card.addView(appIcon, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout info = column(4); info.setPadding(dp(12), 0, dp(8), 0);
        info.addView(text(app.name, 14, white, true));
        String state = app.status == null ? "STOPPED" : app.status;
        String sub = app.runtime.toUpperCase(Locale.ROOT) + "  •  " + state;
        int stateColor = "RUNNING".equals(state) ? mint : ("FAILED".equals(state) ? Color.rgb(255, 126, 126) : muted);
        info.addView(text(sub, 9, stateColor, true));
        if (app.lastMessage != null && !app.lastMessage.isEmpty() && !"RUNNING".equals(state)) {
            info.addView(text(app.lastMessage, 10, muted, false));
        }
        card.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        TextView open = text("OPEN  ›", 10, mint, true); open.setGravity(Gravity.CENTER); card.addView(open);
        card.setOnClickListener(v -> openApp(app));
        card.setOnLongClickListener(v -> { showAppMenu(app); return true; });
        return card;
    }

    private void showAppMenu(LocalApp app) {
        String[] actions = new String[]{"Open", "Stop server", "View scan report", "View runtime log", "Remove package"};
        new AlertDialog.Builder(this).setTitle(app.name).setItems(actions, (dialog, which) -> {
            if (which == 0) openApp(app);
            else if (which == 1) stopApp(app);
            else if (which == 2) showScanReport(app);
            else if (which == 3) showAppLogs(app);
            else if (which == 4) new AlertDialog.Builder(this).setTitle("Remove " + app.name + "?")
                .setMessage("This removes the imported copy and registry entry. The original shared package archive in your selected vault is kept unless you delete it from Storage Manager.")
                .setNegativeButton("Cancel", null).setPositiveButton("Remove", (d, w) -> removeApp(app)).show();
        }).show();
    }

    private void showScanReport(LocalApp app) {
        StringBuilder details = new StringBuilder();
        details.append("Runtime: ").append(app.runtime).append(" ").append(app.runtimeVersion).append("\nEntry: ").append(app.entry)
            .append("\nHomepage: ").append(app.homepage).append("\nWeb root: ").append(app.webRoot)
            .append("\nPackage version: ").append(app.version).append("\n\nDetected dependencies:");
        if (app.dependencies.isEmpty()) details.append("\nNone detected");
        else for (int i = 0; i < Math.min(80, app.dependencies.size()); i++) details.append("\n• ").append(app.dependencies.get(i));
        details.append("\n\nRequested capabilities:");
        if (app.permissions.isEmpty()) details.append("\nNone");
        else for (String permission : app.permissions) details.append("\n• ").append(permission);
        details.append("\n\nPackage hash: ").append(app.packageHash == null ? "not recorded" : app.packageHash);
        new AlertDialog.Builder(this).setTitle(app.name + " — offline scan").setMessage(details.toString()).setPositiveButton("OK", null).show();
    }

    private void showAppLogs(LocalApp app) {
        String safe = app.id == null ? "unknown" : app.id.replaceAll("[^A-Za-z0-9._-]", "_");
        File log = new File(new File(getFilesDir(), "logs"), safe + ".log");
        String content = "No runtime log has been written for this package yet.";
        if (log.isFile()) {
            try (InputStream in = new FileInputStream(log)) {
                byte[] bytes = readTail(in, 96 * 1024);
                content = new String(bytes, StandardCharsets.UTF_8);
            } catch (Exception e) { content = "Could not read log: " + e.getMessage(); }
        }
        TextView view = text(content, 11, white, false);
        view.setTextIsSelectable(true);
        ScrollView scroller = new ScrollView(this); scroller.setPadding(dp(12), dp(12), dp(12), dp(12));
        scroller.setBackgroundColor(panel); scroller.addView(view);
        new AlertDialog.Builder(this).setTitle(app.name + " · Runtime log").setView(scroller)
            .setPositiveButton("Close", null).show();
    }

    private static byte[] readTail(InputStream in, int max) throws Exception {
        byte[] data = new byte[max]; int used = 0, n;
        while ((n = in.read(data, used, data.length - used)) != -1) {
            used += n;
            if (used == data.length) {
                // Keep the tail by shifting out the oldest bytes only when more input exists.
                int next = in.read(); if (next == -1) break;
                System.arraycopy(data, 1, data, 0, data.length - 1); data[data.length - 1] = (byte) next;
            }
        }
        return java.util.Arrays.copyOfRange(data, 0, used);
    }

    private void renderRuntimePage() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = column(14);
        body.setPadding(dp(18), dp(12), dp(18), dp(24));
        body.addView(text("Runtime & storage", 23, white, true));
        body.addView(text("The engine is independent of Termux. It provisions the embedded Python runtime, optional Node native runtime, shared caches, per-app environments and the named local-app gateway.", 12, muted, false));

        body.addView(runtimeCard("STATIC WEB ENGINE", "BUILT IN", "HTML, CSS, JavaScript and local assets are served by the loopback gateway. Apps appear by name; backend ports are not shown.", true));
        body.addView(runtimeCard("PYTHON", "EMBEDDED 3.13", "CPython is bundled in the APK through Chaquopy. Each imported Python app receives a separate site-packages directory; wheel downloads are cached and can be mirrored to your permanent vault.", true));
        body.addView(runtimeCard("NODE.JS", com.laaprak.localhostloader.runtime.NodeBridge.isAvailable() ? "NATIVE ENGINE READY" : "BUILD-TIME ENGINE REQUIRED", com.laaprak.localhostloader.runtime.NodeBridge.isAvailable() ? "Node.js native library is present. Deterministic npm lockfiles are installed without running shell commands." : "The source build can fetch Node.js for Mobile Apps and compile its JNI bridge. The runtime is not present in this local ZIP until that build step runs.", com.laaprak.localhostloader.runtime.NodeBridge.isAvailable()));

        LinearLayout vault = column(8);
        vault.setPadding(dp(16), dp(15), dp(16), dp(15));
        vault.setBackground(round(panel, 16, line));
        vault.addView(text("PERMANENT RUNTIME VAULT", 12, mint, true));
        vault.addView(text(PersistentVault.isAttached(this) ? "Attached — files are stored in your selected shared Android folder." : "Not attached — choose a shared folder to keep package archives and runtime caches outside app-private storage.", 11, muted, false));
        LinearLayout vaultActions = new LinearLayout(this);
        vaultActions.setOrientation(LinearLayout.HORIZONTAL);
        Button attach = button(PersistentVault.isAttached(this) ? "CHANGE VAULT" : "CHOOSE VAULT", mint, bg);
        attach.setOnClickListener(v -> pickVault());
        vaultActions.addView(attach, new LinearLayout.LayoutParams(0, dp(46), 1));
        vaultActions.addView(new View(this), new LinearLayout.LayoutParams(dp(8), 1));
        Button restore = button("RESTORE", panel2, white);
        restore.setOnClickListener(v -> restoreVaultPackages());
        vaultActions.addView(restore, new LinearLayout.LayoutParams(0, dp(46), 1));
        vault.addView(vaultActions);
        body.addView(vault);

        LinearLayout pack = column(8);
        pack.setPadding(dp(16), dp(15), dp(16), dp(15));
        pack.setBackground(round(panel, 16, line));
        pack.addView(text("OFFLINE RUNTIME PACKS", 12, mint, true));
        pack.addView(text("Import .llpack archives containing checksummed Python wheels, npm tarballs or portable runtime assets. The scanner checks the pack manifest and SHA-256 of each payload before installing it.", 11, muted, false));
        Button packButton = button("IMPORT .LLPACK", panel2, white);
        packButton.setOnClickListener(v -> pickRuntimePack());
        pack.addView(packButton);
        body.addView(pack);

        LinearLayout maintenance = column(9);
        maintenance.setPadding(dp(16), dp(15), dp(16), dp(15));
        maintenance.setBackground(round(panel, 16, line));
        LinearLayout maintRow = new LinearLayout(this);
        maintRow.setGravity(Gravity.CENTER_VERTICAL);
        maintRow.addView(text("UP&UPG", 14, mint, true), new LinearLayout.LayoutParams(0, -2, 1));
        Button up = button("CHECK NOW", mint, bg);
        up.setOnClickListener(v -> runUpdateCheck());
        maintRow.addView(up);
        maintenance.addView(maintRow);
        maintenance.addView(text("Automatic update-catalog checks are scheduled every 48 hours when connected. Versions are never replaced automatically: review releases, download, install, and keep older versions until you choose to delete them.", 11, muted, false));
        Button settings = button("MIRRORS & CATALOG SETTINGS", panel2, white);
        settings.setOnClickListener(v -> showEngineSettings());
        maintenance.addView(settings);
        Button storage = button("RUNTIME STORAGE MANAGER", panel2, white);
        storage.setOnClickListener(v -> showRuntimeStorageDialog());
        maintenance.addView(storage);
        body.addView(maintenance);

        LinearLayout versions = column(6);
        versions.setPadding(dp(16), dp(15), dp(16), dp(15));
        versions.setBackground(round(panel, 16, line));
        versions.addView(text("INSTALLED ENGINE VERSIONS", 12, mint, true));
        versions.addView(text("Python 3.13 · embedded with the APK\nNode.js 18.20.4 · optional native library supplied by the build workflow\nStatic gateway · app-managed loopback service\nPython wheels / npm tarballs · reusable cache, optional permanent vault", 11, muted, false));
        body.addView(versions);

        scroll.addView(body);
        page.addView(scroll);
    }

    private View runtimeCard(String name, String state, String description, boolean available) {
        LinearLayout card = column(7);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(round(panel, 16, line));
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(text(name, 11, white, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView badge = text(state, 8, available ? mint : Color.rgb(242, 184, 103), true);
        badge.setPadding(dp(8), dp(5), dp(8), dp(5)); badge.setBackground(round(panel2, 20, line)); row.addView(badge);
        card.addView(row); card.addView(text(description, 12, muted, false));
        return card;
    }

    private void renderStorePage() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = column(13); body.setPadding(dp(18), dp(12), dp(18), dp(24));
        body.addView(text("Package store", 23, white, true));
        body.addView(text("Connect a JSON catalogue. Releases are listed for review; nothing is installed or replaced without a user action.", 12, muted, false));
        Button configure = button("CONFIGURE CATALOGUE", mint, bg); configure.setOnClickListener(v -> showEngineSettings()); body.addView(configure);
        String raw = getSharedPreferences(RuntimeUpdateWorker.PREFS, MODE_PRIVATE).getString(RuntimeUpdateWorker.KEY_CACHE, "");
        if (raw.isEmpty()) {
            LinearLayout empty = column(8); empty.setPadding(dp(17), dp(20), dp(17), dp(20)); empty.setBackground(round(panel, 16, line));
            empty.addView(text("No catalogue loaded", 16, white, true));
            empty.addView(text("Add your HTTPS catalogue URL under Engine → Mirrors & Catalog Settings, then select UP&UPG → Check Now.", 12, muted, false));
            body.addView(empty);
        } else {
            try {
                JSONObject catalog = new JSONObject(raw); JSONArray items = catalog.optJSONArray("items");
                if (items == null || items.length() == 0) body.addView(text("The catalogue contains no releases.", 12, muted, false));
                else for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i); if (item == null) continue;
                    String title = item.optString("name", item.optString("id", "Package"));
                    String version = item.optString("version", "");
                    LinearLayout card = column(7); card.setPadding(dp(15), dp(14), dp(15), dp(14)); card.setBackground(round(panel, 15, line));
                    card.addView(text(title + (version.isEmpty() ? "" : "  ·  " + version), 14, white, true));
                    card.addView(text(item.optString("description", item.optString("kind", "Package release")), 11, muted, false));
                    Button install = button("DOWNLOAD & INSTALL", panel2, mint);
                    install.setOnClickListener(v -> downloadCatalogItem(item)); card.addView(install);
                    body.addView(card);
                }
            } catch (Exception e) { body.addView(text("Catalogue cache is invalid. Run UP&UPG again after checking the JSON schema.", 12, Color.rgb(255, 126, 126), false)); }
        }
        scroll.addView(body); page.addView(scroll);
    }

    private void renderBrowserPage() {
        if (activeApp == null) {
            LinearLayout empty = column(12); empty.setGravity(Gravity.CENTER); empty.setPadding(dp(24), dp(30), dp(24), dp(30));
            empty.addView(text("No app is open", 20, white, true));
            empty.addView(text("Choose an app from the Apps tab. The browser routes to its local service using the app name, not a port number.", 13, muted, false));
            page.addView(empty); return;
        }
        LinearLayout browser = new LinearLayout(this); browser.setOrientation(LinearLayout.VERTICAL);
        LinearLayout toolbar = new LinearLayout(this); toolbar.setGravity(Gravity.CENTER_VERTICAL); toolbar.setPadding(dp(12), dp(8), dp(12), dp(8)); toolbar.setBackgroundColor(panel);
        TextView back = text("‹", 25, white, true); back.setGravity(Gravity.CENTER); back.setOnClickListener(v -> { if (webView != null && webView.canGoBack()) webView.goBack(); else closeActiveApp(); });
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(38), dp(42)));
        LinearLayout appLabel = column(2); appLabel.addView(text(activeApp.name, 13, white, true)); appLabel.addView(text("LOCAL APP  ·  " + activeApp.runtime.toUpperCase(Locale.ROOT), 8, mint, true));
        toolbar.addView(appLabel, new LinearLayout.LayoutParams(0, -2, 1));
        TextView switcher = text("▦", 18, mint, true); switcher.setGravity(Gravity.CENTER); switcher.setOnClickListener(v -> showRunningApps()); toolbar.addView(switcher, new LinearLayout.LayoutParams(dp(36), dp(42)));
        TextView refresh = text("↻", 20, mint, true); refresh.setGravity(Gravity.CENTER); refresh.setOnClickListener(v -> { if (webView != null) webView.reload(); }); toolbar.addView(refresh, new LinearLayout.LayoutParams(dp(36), dp(42)));
        TextView close = text("×", 23, muted, true); close.setGravity(Gravity.CENTER); close.setOnClickListener(v -> closeActiveApp()); toolbar.addView(close, new LinearLayout.LayoutParams(dp(34), dp(42)));
        browser.addView(toolbar);

        webView = new WebView(this);
        WebSettings settings = webView.getSettings(); settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true); settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false); settings.setSupportZoom(true); settings.setBuiltInZoomControls(false); settings.setDisplayZoomControls(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW); settings.setSafeBrowsingEnabled(true); settings.setMediaPlaybackRequiresUserGesture(false);
        webView.setBackgroundColor(Color.WHITE); webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl(); String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
                String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
                if ("http".equals(scheme) && ("127.0.0.1".equals(host) || "localhost".equals(host))) return false;
                if ("https".equals(scheme)) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
                    catch (Exception ignored) { Toast.makeText(MainActivity.this, "No external browser available", Toast.LENGTH_SHORT).show(); }
                }
                return true;
            }
        });
        browser.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1)); page.addView(browser);
        int gatewayPort = LocalGatewayService.getPort();
        if (gatewayPort <= 0) {
            webView.loadDataWithBaseURL(null, "<html><body style='font-family:sans-serif;padding:32px'><h3>Preparing local app…</h3><p>Starting the private app gateway.</p></body></html>", "text/html", "UTF-8", null);
        } else {
            String route = LocalGatewayService.route(activeApp.id, activeApp.homepage);
            webView.loadUrl("http://127.0.0.1:" + gatewayPort + route);
        }
    }

    private void openApp(LocalApp app) {
        if (app == null) return;
        if (!isTrusted(app)) {
            String permissionText = app.permissions.isEmpty() ? "No extra capabilities were declared." : "Requested capabilities: " + android.text.TextUtils.join(", ", app.permissions);
            new AlertDialog.Builder(this).setTitle("Trust this local package?")
                .setMessage("“" + app.name + "” contains executable code. It will run inside Localhost Loader's app sandbox and may use the network and any files it can access through Android permissions. Only install software from developers you trust.\\n\\n" + permissionText + "\\n\\nGranting trust records this exact imported package version; changed package contents ask again.")
                .setNegativeButton("Cancel", null).setPositiveButton("Trust & Open", (d, w) -> { trust(app); startAppAfterTrust(app); }).show();
            return;
        }
        startAppAfterTrust(app);
    }

    private boolean isTrusted(LocalApp app) {
        String key = "trusted_" + (app.packageHash == null ? app.id : app.packageHash);
        return getSharedPreferences("localhost_loader_trust", MODE_PRIVATE).getBoolean(key, false);
    }
    private void trust(LocalApp app) {
        String key = "trusted_" + (app.packageHash == null ? app.id : app.packageHash);
        getSharedPreferences("localhost_loader_trust", MODE_PRIVATE).edit().putBoolean(key, true).apply();
    }

    private void startAppAfterTrust(LocalApp app) {
        if ("RUNNING".equals(app.status)) {
            activeApp = app;
            try {
                Intent gateway = new Intent(this, LocalGatewayService.class);
                ContextCompat.startForegroundService(this, gateway);
                if ("static".equals(app.runtime) && app.staticReady) {
                    Intent register = new Intent(this, LocalGatewayService.class).setAction(LocalGatewayService.ACTION_REGISTER_STATIC)
                        .putExtra(LocalGatewayService.EXTRA_ID, app.id).putExtra(LocalGatewayService.EXTRA_ROOT, app.webRoot);
                    ContextCompat.startForegroundService(this, register);
                    render("browser"); io.execute(() -> waitForGatewayAndOpen(app)); return;
                }
                if (app.backendPort > 0 && isPortReady(app.backendPort)) {
                    Intent register = new Intent(this, LocalGatewayService.class).setAction(LocalGatewayService.ACTION_REGISTER_BACKEND)
                        .putExtra(LocalGatewayService.EXTRA_ID, app.id).putExtra(LocalGatewayService.EXTRA_PORT, app.backendPort);
                    ContextCompat.startForegroundService(this, register);
                    render("browser"); io.execute(() -> waitForGatewayAndOpen(app)); return;
                }
                app.status = "STOPPED"; app.lastMessage = "The server process was reclaimed by Android; restarting it.";
                RuntimeServiceSlots.release(this, app.runtime, app.id);
            } catch (Exception reconnectError) {
                app.status = "STOPPED"; app.lastMessage = "Reconnecting local gateway.";
            }
        }
        activeApp = app; app.status = "STARTING"; app.lastMessage = "Preparing local app"; saveRegistry(); render("browser");
        try {
            Intent gateway = new Intent(this, LocalGatewayService.class);
            ContextCompat.startForegroundService(this, gateway);
            if ("static".equals(app.runtime) && app.staticReady) {
                Intent register = new Intent(this, LocalGatewayService.class).setAction(LocalGatewayService.ACTION_REGISTER_STATIC)
                    .putExtra(LocalGatewayService.EXTRA_ID, app.id).putExtra(LocalGatewayService.EXTRA_ROOT, app.webRoot);
                ContextCompat.startForegroundService(this, register);
                app.status = "RUNNING"; app.lastMessage = "Static web app is ready"; saveRegistry();
                io.execute(() -> waitForGatewayAndOpen(app));
                return;
            }
            if (!"python".equals(app.runtime) && !"node".equals(app.runtime)) throw new IllegalStateException("This package declares an unsupported runtime: " + app.runtime);
            if ("python".equals(app.runtime) && !"3.13".equals(app.runtimeVersion))
                throw new IllegalStateException("This APK includes Python 3.13. The package requests Python " + app.runtimeVersion + ". The loader will not silently substitute another runtime version.");
            if ("node".equals(app.runtime) && !("18".equals(app.runtimeVersion) || "18.20.4".equals(app.runtimeVersion)))
                throw new IllegalStateException("This APK includes Node.js 18.20.4. The package requests Node.js " + app.runtimeVersion + ". The loader will not silently substitute another runtime version.");
            if ("node".equals(app.runtime) && !com.laaprak.localhostloader.runtime.NodeBridge.isAvailable())
                throw new IllegalStateException("The Node.js native runtime is not installed in this APK. Build with the included Node mobile runtime fetch step.");
            String host = "127.0.0.1";
            int port = reservePort();
            String action = "python".equals(app.runtime) ? PythonRuntimeService.ACTION_START : NodeRuntimeService.ACTION_START;
            Intent serviceIntent = RuntimeServiceSlots.acquire(this, app.runtime, app.id, action);
            if (serviceIntent == null) throw new IllegalStateException("All four " + app.runtime + " runtime slots are busy. Stop one app before starting another.");
            serviceIntent.putExtra("root", app.rootPath).putExtra("entry", app.entry).putExtra("app_id", app.id)
                .putExtra("app_name", app.name).putExtra("port", port).putExtra("host", host)
                .putExtra("environment_json", app.launchConfig == null ? "{}" : app.launchConfig.toString());
            app.backendPort = port; app.status = "INSTALLING"; app.lastMessage = "Preparing runtime and dependencies"; saveRegistry();
            final String runtime = app.runtime;
            io.execute(() -> {
                try {
                    if ("node".equals(runtime)) {
                        File modules = new File(app.rootPath, "node_modules");
                        boolean offline = getSharedPreferences(RuntimeUpdateWorker.PREFS, MODE_PRIVATE).getBoolean("offline_only", false);
                        String registry = getSharedPreferences(RuntimeUpdateWorker.PREFS, MODE_PRIVATE).getString("npm_registry", "https://registry.npmjs.org/");
                        NpmLockInstaller.Result install = NpmLockInstaller.install(MainActivity.this, new File(app.rootPath), modules, offline, registry);
                        if (PersistentVault.isAttached(MainActivity.this)) PersistentVault.mirrorDirectory(MainActivity.this, new File(getFilesDir(), "runtime/node/tarballs"), "runtime/node/tarballs");
                        android.util.Log.i("LocalhostLoader", install.message);
                    }
                    Intent register = new Intent(MainActivity.this, LocalGatewayService.class).setAction(LocalGatewayService.ACTION_REGISTER_BACKEND)
                        .putExtra(LocalGatewayService.EXTRA_ID, app.id).putExtra(LocalGatewayService.EXTRA_PORT, port);
                    ContextCompat.startForegroundService(MainActivity.this, serviceIntent);
                    ContextCompat.startForegroundService(MainActivity.this, register);
                    if (!waitForPort(port, 90000, app)) throw new IllegalStateException(app.lastMessage == null || app.lastMessage.isEmpty() ? "The server did not open its local socket. Review the app log and its startup requirements." : app.lastMessage);
                    runOnUiThread(() -> {
                        app.status = "RUNNING"; app.lastMessage = "Local server is responding"; saveRegistry();
                        if (activeApp != null && activeApp.id.equals(app.id)) render("browser");
                        else render("apps");
                    });
                } catch (Exception e) {
                    RuntimeServiceSlots.release(MainActivity.this, runtime, app.id);
                    unregisterGateway(app.id);
                    runOnUiThread(() -> { app.status = "FAILED"; app.lastMessage = e.getMessage(); saveRegistry(); render("apps"); showError("Could not start " + app.name, e.getMessage()); });
                }
            });
        } catch (Exception e) {
            app.status = "FAILED"; app.lastMessage = e.getMessage(); saveRegistry(); render("apps"); showError("Could not start " + app.name, e.getMessage());
        }
    }

    private void waitForGatewayAndOpen(LocalApp app) {
        long deadline = System.currentTimeMillis() + 12000;
        while (System.currentTimeMillis() < deadline) {
            if (LocalGatewayService.getPort() > 0) break;
            try { Thread.sleep(150); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        runOnUiThread(() -> { if (activeApp != null && activeApp.id.equals(app.id)) render("browser"); });
    }

    private int reservePort() throws Exception {
        try (ServerSocket socket = new ServerSocket()) { socket.setReuseAddress(true); socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)); return socket.getLocalPort(); }
    }

    private boolean isPortReady(int port) {
        if (port < 1 || port > 65535) return false;
        try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress("127.0.0.1", port), 350); return true; }
        catch (Exception ignored) { return false; }
    }

    private boolean waitForPort(int port, long timeoutMs, LocalApp app) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (isPortReady(port)) return true;
            if ("FAILED".equals(app.status) || "STOPPED".equals(app.status)) return false;
            try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return false;
    }

    private void closeActiveApp() {
        if (webView != null) { webView.stopLoading(); webView.destroy(); webView = null; }
        activeApp = null; render("apps");
    }

    private void unregisterGateway(String id) {
        try {
            Intent intent = new Intent(this, LocalGatewayService.class).setAction(LocalGatewayService.ACTION_UNREGISTER).putExtra(LocalGatewayService.EXTRA_ID, id);
            ContextCompat.startForegroundService(this, intent);
        } catch (Exception ignored) { }
    }

    private void stopApp(LocalApp app) {
        if (app == null) return;
        try {
            String slot = RuntimeServiceSlots.assignedSlot(this, app.runtime, app.id);
            Intent stop = null;
            if ("python".equals(app.runtime) && !slot.isEmpty()) {
                Class<?> c = slot.equals("1") ? com.laaprak.localhostloader.runtime.PythonRuntimeService.class : slot.equals("2") ? com.laaprak.localhostloader.runtime.PythonRuntimeService2.class : slot.equals("3") ? com.laaprak.localhostloader.runtime.PythonRuntimeService3.class : com.laaprak.localhostloader.runtime.PythonRuntimeService4.class;
                stop = new Intent(this, c).setAction(PythonRuntimeService.ACTION_STOP);
            } else if ("node".equals(app.runtime) && !slot.isEmpty()) {
                Class<?> c = slot.equals("1") ? NodeRuntimeService.class : slot.equals("2") ? com.laaprak.localhostloader.runtime.NodeRuntimeService2.class : slot.equals("3") ? com.laaprak.localhostloader.runtime.NodeRuntimeService3.class : com.laaprak.localhostloader.runtime.NodeRuntimeService4.class;
                stop = new Intent(this, c).setAction(NodeRuntimeService.ACTION_STOP);
            }
            if (stop != null) ContextCompat.startForegroundService(this, stop);
            RuntimeServiceSlots.release(this, app.runtime, app.id);
            unregisterGateway(app.id);
        } catch (Exception e) { showError("Stop failed", e.getMessage()); }
        app.status = "STOPPED"; app.lastMessage = "Stopped by user"; saveRegistry();
        if (activeApp != null && activeApp.id.equals(app.id)) closeActiveApp(); else render("apps");
    }

    private void showRunningApps() {
        ArrayList<LocalApp> active = new ArrayList<>();
        for (LocalApp app : installedApps) if ("RUNNING".equals(app.status) || "STARTING".equals(app.status) || "INSTALLING".equals(app.status)) active.add(app);
        if (active.isEmpty()) { Toast.makeText(this, "No other local apps are running", Toast.LENGTH_SHORT).show(); return; }
        String[] names = new String[active.size()]; for (int i = 0; i < active.size(); i++) names[i] = active.get(i).name;
        new AlertDialog.Builder(this).setTitle("Switch local app").setItems(names, (d, which) -> openApp(active.get(which))).show();
    }

    private void pickVault() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQ_PICK_VAULT);
    }

    private void pickFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQ_PICK_FOLDER);
    }

    private void pickRuntimePack() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip"); intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, REQ_PICK_PACK);
    }

    private void restoreLocalVaultCaches() {
        if (!PersistentVault.isAttached(this)) return;
        io.execute(() -> {
            try {
                PersistentVault.restoreDirectory(this, "runtime", new File(getFilesDir(), "runtime"));
            } catch (Exception e) { android.util.Log.w("LocalhostLoader", "Vault restore deferred", e); }
        });
    }

    private void restoreVaultPackages() {
        if (!PersistentVault.isAttached(this)) { pickVault(); return; }
        Toast.makeText(this, "Restoring saved packages and runtime caches…", Toast.LENGTH_LONG).show();
        io.execute(() -> {
            ArrayList<LocalApp> restored = new ArrayList<>();
            try {
                PersistentVault.restoreDirectory(this, "runtime", new File(getFilesDir(), "runtime"));
                File stageDir = new File(getCacheDir(), "vault-package-restore"); stageDir.mkdirs();
                for (String filename : PersistentVault.listFiles(this, "packages")) {
                    if (!filename.startsWith("pkg-") || !filename.toLowerCase(Locale.ROOT).endsWith(".zip")) continue;
                    String id = filename.substring(4, filename.length() - 4);
                    File staged = new File(stageDir, filename);
                    PersistentVault.copyVaultFileTo(this, "packages/" + filename, staged);
                    LocalApp app = PackageImporter.importZip(this, staged); app.id = id; app.packageArchivePath = "packages/" + filename;
                    restored.add(app); staged.delete();
                }
                for (String folderName : PersistentVault.listDirectories(this, "packages")) {
                    if (!folderName.startsWith("folder-")) continue;
                    String id = folderName.substring("folder-".length());
                    File staged = new File(new File(getFilesDir(), "packages"), "vault-" + folderName);
                    deleteRecursively(staged);
                    PersistentVault.restoreDirectory(this, "packages/" + folderName, staged);
                    LocalApp app = PackageImporter.importDirectory(this, staged);
                    app.id = id; app.packageArchivePath = "packages/" + folderName;
                    restored.add(app);
                }
            } catch (Exception e) { runOnUiThread(() -> showError("Vault restore failed", e.getMessage())); }
            runOnUiThread(() -> {
                for (LocalApp app : restored) {
                    boolean replaced = false;
                    for (int i = 0; i < installedApps.size(); i++) if (installedApps.get(i).id.equals(app.id)) { installedApps.set(i, app); replaced = true; break; }
                    if (!replaced) installedApps.add(app);
                }
                saveRegistry(); render("apps"); Toast.makeText(this, restored.size() + " package(s) restored", Toast.LENGTH_LONG).show();
            });
        });
    }

    private void saveDirectoryToVault(LocalApp app) {
        if (!PersistentVault.isAttached(this) || app.rootPath == null) return;
        try {
            String destination = "packages/folder-" + app.id;
            PersistentVault.ensureDirectory(this, destination);
            PersistentVault.mirrorDirectory(this, new File(app.rootPath), destination);
            app.packageArchivePath = destination;
        } catch (Exception e) { android.util.Log.w("LocalhostLoader", "Folder imported but not saved in permanent vault", e); }
    }

    private void savePackageToVault(LocalApp app, File zip) {
        if (!PersistentVault.isAttached(this)) return;
        try {
            String name = "pkg-" + app.id + ".zip";
            PersistentVault.copyFileIntoVault(this, zip, "packages/" + name, "application/zip");
            app.packageArchivePath = "packages/" + name;
        } catch (Exception e) { android.util.Log.w("LocalhostLoader", "Package imported but not saved to the permanent vault", e); }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 901);
        }
    }

    private void scheduleUpdateChecks() {
        Constraints constraints = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(RuntimeUpdateWorker.class, 48, java.util.concurrent.TimeUnit.HOURS)
            .setConstraints(constraints).build();
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("localhost-loader-up-and-up", ExistingPeriodicWorkPolicy.KEEP, request);
    }

    private void runUpdateCheck() {
        String catalog = getSharedPreferences(RuntimeUpdateWorker.PREFS, MODE_PRIVATE).getString(RuntimeUpdateWorker.KEY_CATALOG, "").trim();
        if (catalog.isEmpty()) { showEngineSettings(); return; }
        Constraints constraints = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(RuntimeUpdateWorker.class).setConstraints(constraints).build();
        WorkManager.getInstance(this).enqueue(request);
        Toast.makeText(this, "UP&UPG check queued. Review the Store tab after the update notification arrives.", Toast.LENGTH_LONG).show();
    }

    private void showEngineSettings() {
        android.content.SharedPreferences prefs = getSharedPreferences(RuntimeUpdateWorker.PREFS, MODE_PRIVATE);
        LinearLayout form = column(8); form.setPadding(dp(4), dp(6), dp(4), dp(6));
        EditText catalog = settingsInput("https://your-domain/runtime-catalog.json", prefs.getString(RuntimeUpdateWorker.KEY_CATALOG, ""));
        EditText python = settingsInput("https://pypi.org/simple", prefs.getString("python_index", "https://pypi.org/simple"));
        EditText npm = settingsInput("https://registry.npmjs.org/", prefs.getString("npm_registry", "https://registry.npmjs.org/"));
        form.addView(text("Signed-by-digest HTTPS catalogue URL", 10, mint, true)); form.addView(catalog);
        form.addView(text("Python package index / mirror", 10, mint, true)); form.addView(python);
        form.addView(text("npm registry / mirror", 10, mint, true)); form.addView(npm);
        android.widget.CheckBox offline = new android.widget.CheckBox(this); offline.setText("Offline-only installs (never use network for missing dependencies)"); offline.setTextColor(white); offline.setButtonTintList(android.content.res.ColorStateList.valueOf(mint)); offline.setChecked(prefs.getBoolean("offline_only", false)); form.addView(offline);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Engine & mirror settings").setView(form)
            .setNegativeButton("Cancel", null).setPositiveButton("Save", (d, w) -> {
                String c = catalog.getText().toString().trim(), p = python.getText().toString().trim(), n = npm.getText().toString().trim();
                if ((!c.isEmpty() && !c.startsWith("https://")) || (!p.isEmpty() && !p.startsWith("https://")) || (!n.isEmpty() && !n.startsWith("https://"))) {
                    showError("HTTPS required", "Package indexes and catalogues must use HTTPS."); return;
                }
                prefs.edit().putString(RuntimeUpdateWorker.KEY_CATALOG, c).putString("python_index", p.isEmpty() ? "https://pypi.org/simple" : p)
                    .putString("npm_registry", n.isEmpty() ? "https://registry.npmjs.org/" : n).putBoolean("offline_only", offline.isChecked()).apply();
                Toast.makeText(this, "Engine settings saved", Toast.LENGTH_SHORT).show();
            }).create();
        dialog.show();
    }

    private EditText settingsInput(String hint, String value) {
        EditText edit = new EditText(this); edit.setSingleLine(true); edit.setText(value); edit.setHint(hint); edit.setTextColor(white); edit.setHintTextColor(muted);
        edit.setPadding(dp(12), dp(10), dp(12), dp(10)); edit.setBackground(round(panel2, 10, line)); return edit;
    }

    private void showRuntimeStorageDialog() {
        File runtime = new File(getFilesDir(), "runtime");
        long bytes = directorySize(runtime);
        String message = "App-private runtime cache: " + android.text.format.Formatter.formatFileSize(this, bytes) +
            "\\nPermanent vault: " + (PersistentVault.isAttached(this) ? "attached" : "not attached") +
            "\\n\\nPython 3.13 is packaged with this APK and cannot be removed independently. Old wheel/tarball caches can be cleared; saved vault copies remain untouched.";
        new AlertDialog.Builder(this).setTitle("Runtime storage")
            .setMessage(message).setNegativeButton("Close", null)
            .setPositiveButton("Clear local caches", (d, w) -> {
                deleteRecursively(new File(runtime, "python/3.13/wheelhouse"));
                deleteRecursively(new File(runtime, "node/tarballs"));
                Toast.makeText(this, "Local download caches cleared. Permanent vault copies were kept.", Toast.LENGTH_LONG).show();
            }).show();
    }

    private long directorySize(File file) {
        if (file == null || !file.exists()) return 0; if (file.isFile()) return file.length();
        long total = 0; File[] children = file.listFiles(); if (children != null) for (File f : children) total += directorySize(f); return total;
    }

    private void downloadCatalogItem(JSONObject item) {
        String url = item.optString("download_url", "");
        if (!url.startsWith("https://")) { showError("Invalid release", "Only HTTPS package downloads are allowed."); return; }
        io.execute(() -> {
            File zip = new File(getCacheDir(), "catalog-" + UUID.randomUUID() + ".zip");
            try {
                downloadFile(url, zip, 800L * 1024L * 1024L);
                String expected = item.optString("sha256", "").trim();
                if (!expected.matches("(?i)[a-f0-9]{64}")) throw new SecurityException("Catalogue releases must include a 64-character SHA-256 digest.");
                if (!expected.equalsIgnoreCase(sha256File(zip))) throw new SecurityException("Release SHA-256 does not match the catalogue.");
                String format = item.optString("format", "");
                if ("llpack".equalsIgnoreCase(format) || "runtime-pack".equalsIgnoreCase(item.optString("kind", ""))) {
                    RuntimePackInstaller.Result result = RuntimePackInstaller.install(this, zip);
                    runOnUiThread(() -> { Toast.makeText(this, result.kind + " " + result.version + " installed (" + result.installed + " files)", Toast.LENGTH_LONG).show(); render("runtimes"); });
                } else {
                    LocalApp app = PackageImporter.importZip(this, zip);
                    savePackageToVault(app, zip);
                    runOnUiThread(() -> finishImport(app));
                }
            } catch (Exception e) { runOnUiThread(() -> showError("Release installation failed", e.getMessage())); }
            finally { zip.delete(); }
        });
    }

    private static HttpURLConnection openHttpsConnection(URL start, int maxRedirects) throws Exception {
        URL current = start;
        for (int attempt = 0; attempt <= maxRedirects; attempt++) {
            if (!"https".equalsIgnoreCase(current.getProtocol())) throw new SecurityException("Package redirects must remain HTTPS.");
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setConnectTimeout(20000); connection.setReadTimeout(60000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "LocalhostLoader/1.0");
            int code = connection.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = connection.getHeaderField("Location"); connection.disconnect();
                if (location == null || attempt == maxRedirects) throw new IllegalStateException("Too many or invalid package redirects.");
                current = new URL(current, location);
                continue;
            }
            return connection;
        }
        throw new IllegalStateException("Too many package redirects.");
    }

    private static void downloadFile(String address, File destination, long limit) throws Exception {
        URL url = new URL(address); if (!"https".equalsIgnoreCase(url.getProtocol())) throw new SecurityException("HTTPS is required.");
        HttpURLConnection c = openHttpsConnection(url, 5); c.setConnectTimeout(20000); c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "LocalhostLoader/1.0");
        try {
            int code = c.getResponseCode(); if (code < 200 || code >= 300) throw new IllegalStateException("Download failed: HTTP " + code);
            if (c.getContentLengthLong() > limit) throw new IllegalStateException("Release exceeds the allowed download size.");
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(destination)) { copyLimited(in, out, limit); }
        } finally { c.disconnect(); }
    }

    private static String sha256File(File file) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) { byte[] b = new byte[32768]; int n; while ((n = in.read(b)) != -1) digest.update(b, 0, n); }
        StringBuilder value = new StringBuilder(); for (byte b : digest.digest()) value.append(String.format(Locale.ROOT, "%02x", b)); return value.toString();
    }

    private void pickPackage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivityForResult(intent, REQ_PICK_PACKAGE); }
        catch (Exception e) {
            intent.setType("application/octet-stream");
            startActivityForResult(intent, REQ_PICK_PACKAGE);
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_PICK_VAULT) {
            try {
                PersistentVault.attach(this, uri);
                Toast.makeText(this, "Permanent vault attached. Backing up installed packages and restoring saved assets…", Toast.LENGTH_LONG).show();
                io.execute(() -> {
                    for (LocalApp app : new ArrayList<>(installedApps)) {
                        if (app.packageArchivePath == null || app.packageArchivePath.isEmpty()) saveDirectoryToVault(app);
                    }
                    runOnUiThread(this::restoreVaultPackages);
                });
            } catch (Exception e) { showError("Vault could not be attached", e.getMessage()); }
            return;
        }
        if (requestCode == REQ_PICK_PACKAGE) {
            Toast.makeText(this, "Importing and scanning package…", Toast.LENGTH_SHORT).show();
            io.execute(() -> {
                File staged = new File(getCacheDir(), "package-" + UUID.randomUUID() + ".zip");
                try {
                    try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(staged)) {
                        if (in == null) throw new IllegalStateException("Cannot read selected file");
                        copyLimited(in, out, MAX_DOWNLOAD_BYTES);
                    }
                    LocalApp app = PackageImporter.importZip(this, staged);
                    savePackageToVault(app, staged);
                    staged.delete();
                    runOnUiThread(() -> finishImport(app));
                } catch (Exception e) {
                    staged.delete(); runOnUiThread(() -> showError("Package import failed", e.getMessage()));
                }
            });
            return;
        }
        if (requestCode == REQ_PICK_FOLDER) {
            Toast.makeText(this, "Copying and scanning server folder…", Toast.LENGTH_LONG).show();
            io.execute(() -> {
                File staged = new File(getFilesDir(), "packages/folder-" + UUID.randomUUID());
                try {
                    DocumentFile tree = DocumentFile.fromTreeUri(this, uri);
                    if (tree == null || !tree.isDirectory()) throw new IllegalArgumentException("The selected location is not a folder.");
                    CopyBudget budget = new CopyBudget();
                    copyDocumentTree(tree, staged, budget, 0);
                    LocalApp app = PackageImporter.importDirectory(this, staged);
                    saveDirectoryToVault(app);
                    runOnUiThread(() -> finishImport(app));
                } catch (Exception e) {
                    deleteRecursively(staged);
                    runOnUiThread(() -> showError("Folder import failed", e.getMessage()));
                }
            });
            return;
        }
        if (requestCode == REQ_PICK_PACK) {
            Toast.makeText(this, "Validating offline runtime pack…", Toast.LENGTH_LONG).show();
            io.execute(() -> {
                File staged = new File(getCacheDir(), "runtime-pack-" + UUID.randomUUID() + ".llpack");
                try {
                    try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(staged)) {
                        if (in == null) throw new IllegalStateException("Cannot read selected runtime pack");
                        copyLimited(in, out, 800L * 1024L * 1024L);
                    }
                    RuntimePackInstaller.Result result = RuntimePackInstaller.install(this, staged);
                    runOnUiThread(() -> { Toast.makeText(this, result.kind + " " + result.version + " installed", Toast.LENGTH_LONG).show(); render("runtimes"); });
                } catch (Exception e) { runOnUiThread(() -> showError("Runtime pack installation failed", e.getMessage())); }
                finally { staged.delete(); }
            });
        }
    }

    private static final class CopyBudget { int entries = 0; long bytes = 0; }

    private void copyDocumentTree(DocumentFile source, File destination, CopyBudget budget, int depth) throws Exception {
        if (depth > 32) throw new IllegalStateException("Folder nesting exceeds 32 levels.");
        if (++budget.entries > 12000) throw new IllegalStateException("Selected server folder contains more than 12,000 entries.");
        if (source.isDirectory()) {
            if (!destination.exists() && !destination.mkdirs()) throw new IllegalStateException("Cannot create imported folder.");
            for (DocumentFile child : source.listFiles()) {
                String name = child.getName();
                if (name == null || name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\")) continue;
                copyDocumentTree(child, new File(destination, name), budget, depth + 1);
            }
        } else if (source.isFile()) {
            File parent = destination.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IllegalStateException("Cannot create folder path.");
            try (InputStream in = source.getUri() == null ? null : getContentResolver().openInputStream(source.getUri());
                 OutputStream out = new FileOutputStream(destination)) {
                if (in == null) throw new IllegalStateException("Cannot read a file in the selected folder.");
                byte[] buffer = new byte[32 * 1024]; int n;
                while ((n = in.read(buffer)) != -1) {
                    budget.bytes += n;
                    if (budget.bytes > 500L * 1024L * 1024L) throw new IllegalStateException("Selected folder expands beyond the 500 MB import limit.");
                    out.write(buffer, 0, n);
                }
            }
        }
    }


    private void showLinkDialog() {
        LinearLayout form = column(8);
        form.setPadding(dp(4), dp(8), dp(4), dp(4));
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("https://example.com/server-package.zip");
        input.setTextColor(white);
        input.setHintTextColor(muted);
        input.setSelectAllOnFocus(true);
        input.setPadding(dp(12), dp(12), dp(12), dp(12));
        input.setBackground(round(panel2, 12, line));
        form.addView(input);
        form.addView(text("Use a direct HTTPS link to a ZIP package. It is downloaded, checked and scanned locally; with a vault attached the archive is kept outside app-private storage.", 11, muted, false));
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Import from link")
            .setView(form)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Download & scan", null)
            .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = input.getText().toString().trim();
            if (!value.startsWith("https://")) {
                input.setError("HTTPS package link required");
                return;
            }
            dialog.dismiss();
            downloadAndImport(value);
        }));
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(mint);
    }

    private void downloadAndImport(String link) {
        Toast.makeText(this, "Downloading package…", Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            File zip = new File(getCacheDir(), "download-" + UUID.randomUUID() + ".zip");
            try {
                downloadFile(link, zip, MAX_DOWNLOAD_BYTES);
                LocalApp app = PackageImporter.importZip(this, zip);
                savePackageToVault(app, zip);
                zip.delete();
                runOnUiThread(() -> finishImport(app));
            } catch (Exception e) {
                zip.delete(); runOnUiThread(() -> showError("Download or import failed", e.getMessage()));
            }
        });
    }

    private void finishImport(LocalApp app) {
        boolean replaced = false;
        for (int i = 0; i < installedApps.size(); i++) {
            LocalApp old = installedApps.get(i);
            if (old.id.equals(app.id)) {
                if ("RUNNING".equals(old.status) || "STARTING".equals(old.status) || "INSTALLING".equals(old.status)) stopApp(old);
                if (activeApp != null && activeApp.id.equals(old.id)) closeActiveApp();
                if (!old.packagePath.equals(app.packagePath)) deleteRecursively(new File(old.packagePath));
                installedApps.set(i, app); replaced = true; break;
            }
        }
        if (!replaced) installedApps.add(0, app);
        saveRegistry(); render("apps");
        Toast.makeText(this, app.name + " scanned and added", Toast.LENGTH_LONG).show();
    }

    private void removeApp(LocalApp app) {
        stopApp(app);
        deleteRecursively(new File(app.packagePath));
        installedApps.remove(app); saveRegistry(); render("apps");
    }

    private void loadRegistry() {
        installedApps.clear();
        String raw = preferences.getString(PREF_APPS, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                LocalApp app = LocalApp.fromJson(o);
                if (app.id == null || app.id.contains("/")) app.id = "app-" + UUID.randomUUID();
                if (new File(app.rootPath).isDirectory()) installedApps.add(app);
            }
        } catch (Exception ignored) { }
    }

    private void saveRegistry() {
        JSONArray arr = new JSONArray();
        for (LocalApp app : installedApps) arr.put(app.toJson());
        preferences.edit().putString(PREF_APPS, arr.toString()).apply();
    }

    private void showError(String title, String message) {
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message == null || message.trim().isEmpty() ? "Unknown error" : message)
            .setPositiveButton("OK", null)
            .show();
    }

    private LinearLayout column(int gapDp) {
        SpacedColumn layout = new SpacedColumn(this, dp(gapDp));
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setClipToPadding(false);
        layout.setPadding(0, 0, 0, 0);
        return layout;
    }

    private static class SpacedColumn extends LinearLayout {
        private final int gap;
        SpacedColumn(Activity context, int gap) {
            super(context);
            this.gap = gap;
        }
        @Override
        public void addView(View child, int index, ViewGroup.LayoutParams params) {
            if (params == null) params = generateDefaultLayoutParams();
            if (getChildCount() > 0 && params instanceof MarginLayoutParams) {
                MarginLayoutParams margins = (MarginLayoutParams) params;
                margins.topMargin += gap;
            }
            super.addView(child, index, params);
        }
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        return t;
    }

    private Button button(String label, int background, int foreground) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(10);
        b.setTextColor(foreground);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setAllCaps(false);
        b.setBackground(round(background, 13, background == mint ? mint : line));
        b.setPadding(dp(10), 0, dp(10), 0);
        return b;
    }

    private GradientDrawable round(int color, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        if (stroke != color) d.setStroke(dp(1), stroke);
        return d;
    }

    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }

    private String initials(String name) {
        if (name == null || name.trim().isEmpty()) return "LA";
        String[] parts = name.trim().split("\\s+");
        if (parts.length == 1) return parts[0].substring(0, 1).toUpperCase(Locale.ROOT);
        return (parts[0].substring(0, 1) + parts[1].substring(0, 1)).toUpperCase(Locale.ROOT);
    }

    private static void copyLimited(InputStream in, OutputStream out, long limit) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > limit) throw new IllegalStateException("File exceeds the 300 MB limit");
            out.write(buffer, 0, read);
        }
        out.flush();
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    // ---------------- Package model and scanner ----------------
    static class LocalApp {
        String id, name, version, runtime, runtimeVersion, entry, homepage, rootPath, webRoot, packagePath;
        String packageHash = "", packageArchivePath = "", updateUrl = "", status = "STOPPED", lastMessage = "";
        int backendPort = 0;
        boolean staticReady;
        JSONObject environment = new JSONObject();
        JSONObject launchConfig = new JSONObject();
        ArrayList<String> dependencies = new ArrayList<>();
        ArrayList<String> permissions = new ArrayList<>();

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id); o.put("name", name); o.put("version", version); o.put("runtime", runtime);
                o.put("runtimeVersion", runtimeVersion); o.put("entry", entry); o.put("homepage", homepage);
                o.put("rootPath", rootPath); o.put("webRoot", webRoot); o.put("packagePath", packagePath);
                o.put("staticReady", staticReady); o.put("packageHash", packageHash); o.put("packageArchivePath", packageArchivePath);
                o.put("updateUrl", updateUrl); o.put("status", status); o.put("lastMessage", lastMessage); o.put("backendPort", backendPort);
                o.put("environment", environment); o.put("launchConfig", launchConfig);
                JSONArray d = new JSONArray(); for (String dep : dependencies) d.put(dep); o.put("dependencies", d);
                JSONArray p = new JSONArray(); for (String permission : permissions) p.put(permission); o.put("permissions", p);
            } catch (Exception ignored) { }
            return o;
        }

        static LocalApp fromJson(JSONObject o) {
            LocalApp a = new LocalApp();
            a.id = o.optString("id"); a.name = o.optString("name", "Imported app");
            a.version = o.optString("version", "1.0"); a.runtime = o.optString("runtime", "unknown");
            a.runtimeVersion = o.optString("runtimeVersion", ""); a.entry = o.optString("entry", ""); a.homepage = o.optString("homepage", "/");
            a.rootPath = o.optString("rootPath"); a.webRoot = o.optString("webRoot", a.rootPath); a.packagePath = o.optString("packagePath", new File(a.rootPath).getParent());
            a.staticReady = o.optBoolean("staticReady", false); a.packageHash = o.optString("packageHash", "");
            a.packageArchivePath = o.optString("packageArchivePath", ""); a.updateUrl = o.optString("updateUrl", "");
            a.status = o.optString("status", "STOPPED"); a.lastMessage = o.optString("lastMessage", ""); a.backendPort = o.optInt("backendPort", 0);
            JSONObject env = o.optJSONObject("environment"); if (env != null) a.environment = env;
            JSONObject launch = o.optJSONObject("launchConfig"); if (launch != null) a.launchConfig = launch;
            JSONArray d = o.optJSONArray("dependencies"); if (d != null) for (int i = 0; i < d.length(); i++) a.dependencies.add(d.optString(i));
            JSONArray p = o.optJSONArray("permissions"); if (p != null) for (int i = 0; i < p.length(); i++) a.permissions.add(p.optString(i));
            return a;
        }
    }

    static class PackageImporter {
        private static final long MAX_UNPACKED = 500L * 1024L * 1024L;
        private static final int MAX_ENTRIES = 12000;

        static LocalApp importZip(Activity activity, File zip) throws Exception {
            File base = new File(activity.getFilesDir(), "packages");
            if (!base.exists() && !base.mkdirs()) throw new IllegalStateException("Cannot create package storage");
            File packageDir = new File(base, "pkg-" + UUID.randomUUID());
            if (!packageDir.mkdirs()) throw new IllegalStateException("Cannot create package folder");

            long expanded = 0;
            int entries = 0;
            try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
                ZipEntry entry;
                byte[] buffer = new byte[64 * 1024];
                while ((entry = zin.getNextEntry()) != null) {
                    entries++;
                    if (entries > MAX_ENTRIES) throw new IllegalStateException("Package contains too many files");
                    String name = entry.getName().replace('\\', '/');
                    if (name.startsWith("/") || name.contains("../") || name.equals("..") || name.matches("^[A-Za-z]:.*")) {
                        throw new IllegalStateException("Unsafe path in package: " + name);
                    }
                    File out = new File(packageDir, name);
                    String root = packageDir.getCanonicalPath() + File.separator;
                    String target = out.getCanonicalPath();
                    if (!target.equals(packageDir.getCanonicalPath()) && !target.startsWith(root)) {
                        throw new IllegalStateException("Package path escapes its folder");
                    }
                    if (entry.isDirectory()) {
                        if (!out.isDirectory() && !out.mkdirs()) throw new IllegalStateException("Cannot create package directory");
                    } else {
                        File parent = out.getParentFile();
                        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IllegalStateException("Cannot create package path");
                        try (OutputStream fout = new FileOutputStream(out)) {
                            int n;
                            while ((n = zin.read(buffer)) != -1) {
                                expanded += n;
                                if (expanded > MAX_UNPACKED) throw new IllegalStateException("Expanded package exceeds 500 MB");
                                fout.write(buffer, 0, n);
                            }
                        }
                    }
                    zin.closeEntry();
                }
            } catch (Exception e) {
                deleteTree(packageDir);
                throw e;
            }

            File projectRoot = findProjectRoot(packageDir);
            if (projectRoot == null) projectRoot = packageDir;
            try {
                LocalApp result = scan(projectRoot);
                result.packagePath = packageDir.getCanonicalPath();
                result.packageHash = sha256File(zip);
                return result;
            } catch (Exception e) {
                deleteTree(packageDir);
                throw e;
            }
        }

        static LocalApp importDirectory(Activity activity, File packageDir) throws Exception {
            File projectRoot = findProjectRoot(packageDir);
            if (projectRoot == null) projectRoot = packageDir;
            LocalApp result = scan(projectRoot);
            result.packagePath = packageDir.getCanonicalPath();
            result.packageHash = sha256Directory(projectRoot);
            return result;
        }

        private static String sha256Directory(File root) throws Exception {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            ArrayList<File> files = listFiles(root, 20000);
            Collections.sort(files, Comparator.comparing(f -> root.toPath().relativize(f.toPath()).toString()));
            byte[] buffer = new byte[32768];
            for (File file : files) {
                if (!file.isFile()) continue;
                String relative = root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/');
                digest.update(relative.getBytes(StandardCharsets.UTF_8));
                try (InputStream in = new FileInputStream(file)) { int n; while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n); }
            }
            StringBuilder value = new StringBuilder();
            for (byte b : digest.digest()) value.append(String.format(Locale.ROOT, "%02x", b));
            return value.toString();
        }

        private static LocalApp scan(File root) throws Exception {
            JSONObject manifest = readManifest(root);
            List<File> files = listFiles(root, 20000);
            LocalApp app = new LocalApp();
            String declaredId = manifest.optString("id", "").trim();
            app.id = declaredId.matches("[A-Za-z0-9._-]{1,80}") ? declaredId : "app-" + UUID.randomUUID().toString().substring(0, 12);
            app.name = manifest.optString("name", niceName(root.getName()));
            app.version = manifest.optString("version", "1.0");
            app.runtime = manifest.optString("runtime", "").trim().toLowerCase(Locale.ROOT);
            app.runtimeVersion = manifest.optString("runtime_version", "").trim();
            app.entry = manifest.optString("entry", "").trim();
            JSONArray startupSpec = manifest.optJSONArray("start");
            if (app.runtime.isEmpty() && startupSpec != null && startupSpec.length() > 0) {
                String command = startupSpec.optString(0, "").toLowerCase(Locale.ROOT);
                if (command.equals("python") || command.equals("python3")) app.runtime = "python";
                else if (command.equals("node") || command.equals("nodejs")) app.runtime = "node";
            }
            if (app.entry.isEmpty() && startupSpec != null && startupSpec.length() > 1) {
                String candidateEntry = startupSpec.optString(1, "").trim();
                if (!candidateEntry.startsWith("-") && candidateEntry.length() < 256) app.entry = candidateEntry;
            }
            app.homepage = manifest.optString("homepage", "/").trim();
            app.updateUrl = manifest.optString("update_url", "").trim();
            app.rootPath = root.getCanonicalPath();
            JSONObject declaredEnv = manifest.optJSONObject("environment");
            if (declaredEnv != null) app.environment = sanitizeEnvironment(declaredEnv);
            JSONArray requested = manifest.optJSONArray("permissions");
            if (requested != null) for (int i = 0; i < Math.min(requested.length(), 32); i++) {
                String permission = requested.optString(i, "").trim().toLowerCase(Locale.ROOT);
                if (permission.matches("[a-z0-9_.-]{1,48}")) app.permissions.add(permission);
            }
            JSONArray args = manifest.optJSONArray("args");
            JSONArray safeArgs = new JSONArray();
            if (startupSpec != null && startupSpec.length() > 2) {
                for (int i = 2; i < Math.min(startupSpec.length(), 34); i++) {
                    String arg = startupSpec.optString(i, ""); if (arg.length() <= 2048 && !arg.contains("\u0000")) safeArgs.put(arg);
                }
            }
            if (args != null) {
                for (int i = 0; i < Math.min(args.length(), 32 - safeArgs.length()); i++) {
                    String arg = args.optString(i, ""); if (arg.length() <= 2048 && !arg.contains("\u0000")) safeArgs.put(arg);
                }
            }
            JSONObject launch = new JSONObject();
            try { launch.put("environment", app.environment); launch.put("args", safeArgs); } catch (Exception ignored) { }
            app.launchConfig = launch;

            File requirements = findFile(files, "requirements.txt");
            File packageJson = findFile(files, "package.json");
            File index = findIndex(files, manifest.optString("web_root", ""));
            if (app.runtime.isEmpty()) {
                boolean pythonEntry = findAny(files, "app.py", "main.py", "server.py", "wsgi.py");
                boolean nodeServer = findAny(files, "server.js", "index.js");
                boolean builtStatic = index != null && (index.getParentFile().getName().equalsIgnoreCase("dist") ||
                    index.getParentFile().getName().equalsIgnoreCase("build") || index.getParentFile().getName().equalsIgnoreCase("www"));
                if (requirements != null || pythonEntry) app.runtime = "python";
                else if (nodeServer) app.runtime = "node";
                else if (index != null && (builtStatic || packageJson == null)) app.runtime = "static";
                else if (packageJson != null || findAny(files, "vite.config.js")) app.runtime = "node";
                else if (index != null) app.runtime = "static";
                else app.runtime = "unknown";
            }
            if ("python".equals(app.runtime) && app.runtimeVersion.isEmpty()) app.runtimeVersion = "3.13";
            if ("node".equals(app.runtime) && app.runtimeVersion.isEmpty()) app.runtimeVersion = "18.20.4";
            if ("python".equals(app.runtime) && app.entry.isEmpty()) app.entry = firstRelative(root, files, "app.py", "main.py", "server.py", "wsgi.py");
            if ("node".equals(app.runtime) && app.entry.isEmpty()) {
                if (packageJson != null) {
                    try (InputStream in = new FileInputStream(packageJson)) {
                        JSONObject meta = new JSONObject(new String(readAll(in, 4 * 1024 * 1024), StandardCharsets.UTF_8));
                        app.entry = meta.optString("main", "").trim();
                    } catch (Exception ignored) { }
                }
                if (app.entry.isEmpty()) app.entry = firstRelative(root, files, "server.js", "index.js");
            }

            if (requirements != null) readRequirements(requirements, app.dependencies);
            if (packageJson != null) readNodeDependencies(packageJson, app.dependencies);
            if ("python".equals(app.runtime)) scanPythonImports(root, files, app.dependencies);
            if ("node".equals(app.runtime)) scanNodeImports(root, files, app.dependencies);
            app.dependencies = unique(app.dependencies);

            JSONArray explicitDeps = manifest.optJSONArray("dependencies");
            ArrayList<String> declaredDependencies = new ArrayList<>();
            if (explicitDeps != null) for (int i = 0; i < explicitDeps.length(); i++) {
                String dep = explicitDeps.optString(i, "").trim();
                if (!dep.isEmpty() && dep.length() < 200) { app.dependencies.add(dep); declaredDependencies.add(dep); }
            }
            app.dependencies = unique(app.dependencies);
            // Import scanning is advisory: never turn guessed module names into pip packages.
            if ("python".equals(app.runtime) && requirements == null && !declaredDependencies.isEmpty()) {
                File generated = new File(root, "requirements.txt");
                try (OutputStream out = new FileOutputStream(generated)) {
                    for (String dep : unique(declaredDependencies)) out.write((dep + "\n").getBytes(StandardCharsets.UTF_8));
                }
            }

            String webRoot = manifest.optString("web_root", "").trim();
            File selectedWebRoot = webRoot.isEmpty() ? (index == null ? null : index.getParentFile()) : new File(root, webRoot);
            if (selectedWebRoot != null && selectedWebRoot.isDirectory()) {
                String rootCanonical = root.getCanonicalPath();
                String webCanonical = selectedWebRoot.getCanonicalPath();
                boolean staysInsidePackage = webCanonical.equals(rootCanonical) || webCanonical.startsWith(rootCanonical + File.separator);
                File candidate = new File(selectedWebRoot, "index.html");
                if (staysInsidePackage && candidate.isFile()) {
                    app.webRoot = webCanonical;
                    app.staticReady = "static".equals(app.runtime) || manifest.optBoolean("static", false);
                }
            }
            if ("static".equals(app.runtime) && index != null) {
                app.webRoot = index.getParentFile().getCanonicalPath();
                app.staticReady = true;
            }
            if (app.homepage.isEmpty()) app.homepage = "/";
            if (!app.homepage.startsWith("/") || app.homepage.contains("..") || app.homepage.contains("://")) throw new IllegalArgumentException("Homepage must be a package-relative path such as /.");
            if (("python".equals(app.runtime) || "node".equals(app.runtime)) && !app.entry.isEmpty()) {
                File entryFile = new File(root, app.entry).getCanonicalFile();
                if (!entryFile.getPath().startsWith(root.getCanonicalPath() + File.separator) || !entryFile.isFile()) throw new IllegalArgumentException("The manifest entry file is missing or outside the package: " + app.entry);
            }
            if (app.webRoot == null || app.webRoot.isEmpty()) app.webRoot = root.getCanonicalPath();
            if (files.isEmpty() || (manifest.length() == 0 && requirements == null && packageJson == null && index == null &&
                !findAny(files, "app.py", "main.py", "server.py", "wsgi.py", "server.js", "index.js"))) {
                throw new IllegalArgumentException("The ZIP does not contain a recognised Localhost Loader package or project files.");
            }
            return app;
        }

        private static JSONObject readManifest(File root) {
            File f = new File(root, "manifest.json");
            if (!f.isFile()) f = new File(root, "loader.json");
            if (f.isFile()) {
                try (InputStream in = new FileInputStream(f); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n);
                    return new JSONObject(out.toString("UTF-8"));
                } catch (Exception e) { return new JSONObject(); }
            }
            return readReadmeLoaderBlock(root);
        }

        private static JSONObject sanitizeEnvironment(JSONObject source) {
            JSONObject safe = new JSONObject(); Iterator<String> keys = source.keys();
            while (keys.hasNext()) { String key = keys.next(); Object value = source.opt(key);
                if (key.matches("[A-Za-z_][A-Za-z0-9_]{0,63}") && (value instanceof String || value instanceof Number || value instanceof Boolean)) {
                    String text = String.valueOf(value); if (text.length() <= 4096 && !text.contains("\u0000")) try { safe.put(key, text); } catch (Exception ignored) { }
                }
            }
            return safe;
        }

        private static JSONObject readReadmeLoaderBlock(File root) {
            File readme = new File(root, "README.md"); if (!readme.isFile()) readme = new File(root, "readme.md");
            if (!readme.isFile()) return new JSONObject();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(readme), StandardCharsets.UTF_8))) {
                boolean active = false; JSONObject result = new JSONObject(); String line;
                while ((line = in.readLine()) != null) {
                    String clean = line.trim();
                    if (clean.equals("<!-- LOCALHOST-LOADER:START -->") || clean.equalsIgnoreCase("[LOCALHOST-LOADER]") || clean.equalsIgnoreCase("## HOW TO START LOADER")) { active = true; continue; }
                    if (clean.equals("<!-- LOCALHOST-LOADER:END -->")) break;
                    if (!active) continue;
                    int split = clean.indexOf('='); if (split < 0) split = clean.indexOf(':');
                    if (split <= 0) continue;
                    String key = clean.substring(0, split).trim().toLowerCase(Locale.ROOT);
                    String value = clean.substring(split + 1).trim();
                    if (key.matches("name|id|version|runtime|runtime_version|entry|web_root|homepage|update_url")) try { result.put(key, value); } catch (Exception ignored) { }
                }
                return result;
            } catch (Exception ignored) { return new JSONObject(); }
        }

        private static String firstRelative(File root, List<File> files, String... names) {
            for (String name : names) for (File file : files) if (file.isFile() && file.getName().equalsIgnoreCase(name)) {
                try { return root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/'); } catch (Exception ignored) { }
            }
            return "";
        }

        private static void scanPythonImports(File root, List<File> files, List<String> deps) {
            java.util.Set<String> standard = new java.util.HashSet<>(java.util.Arrays.asList("os","sys","json","re","time","math","pathlib","typing","datetime","collections","itertools","functools","logging","argparse","asyncio","socket","http","urllib","hashlib","sqlite3","csv","io","email","threading","subprocess","shutil","tempfile","random","string","statistics","unittest","traceback","runpy","multiprocessing"));
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("^\\s*(?:from\\s+([A-Za-z_][\\w]*)|import\\s+([A-Za-z_][\\w]*))", java.util.regex.Pattern.MULTILINE);
            int seen = 0;
            for (File file : files) if (file.isFile() && file.getName().endsWith(".py") && file.length() < 512 * 1024 && seen++ < 120) {
                try { String content = new String(readAll(new FileInputStream(file), 512 * 1024), StandardCharsets.UTF_8); java.util.regex.Matcher m = pattern.matcher(content);
                    while (m.find()) { String module = m.group(1) == null ? m.group(2) : m.group(1); if (!standard.contains(module)) deps.add(module); }
                } catch (Exception ignored) { }
            }
        }

        private static void scanNodeImports(File root, List<File> files, List<String> deps) {
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(?:require\\s*\\(\\s*|from\\s*)['\"]([^'\"]+)['\"]");
            int seen = 0;
            for (File file : files) if (file.isFile() && (file.getName().endsWith(".js") || file.getName().endsWith(".mjs") || file.getName().endsWith(".cjs")) && file.length() < 512 * 1024 && seen++ < 150) {
                try { String content = new String(readAll(new FileInputStream(file), 512 * 1024), StandardCharsets.UTF_8); java.util.regex.Matcher m = pattern.matcher(content);
                    while (m.find()) { String name = m.group(1); if (!name.startsWith(".") && !name.startsWith("/") && !name.startsWith("node:")) deps.add(name.startsWith("@") ? name.substring(0, Math.min(name.length(), name.indexOf('/', 1) > 0 ? name.indexOf('/', 1) : name.length())) : name.split("/", 2)[0]); }
                } catch (Exception ignored) { }
            }
        }

        private static byte[] readAll(InputStream in, long max) throws Exception {
            try (InputStream stream = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] b = new byte[8192]; int n; long total = 0;
                while ((n = stream.read(b)) != -1) { total += n; if (total > max) throw new IllegalArgumentException("File too large while scanning package"); out.write(b, 0, n); }
                return out.toByteArray();
            }
        }

        private static File findProjectRoot(File base) throws Exception {
            ArrayList<File> queue = new ArrayList<>();
            ArrayList<File> fallbacks = new ArrayList<>();
            queue.add(base);
            for (int i = 0; i < queue.size(); i++) {
                File dir = queue.get(i);
                if (i > 250) break;
                boolean hasManifest = new File(dir, "manifest.json").isFile() || new File(dir, "loader.json").isFile();
                if (hasManifest) return dir;
                if (new File(dir, "index.html").isFile() || new File(dir, "requirements.txt").isFile() || new File(dir, "package.json").isFile()) fallbacks.add(dir);
                int depth = dir.getCanonicalPath().substring(base.getCanonicalPath().length()).split(File.separator.equals("\\") ? "\\\\" : "/").length;
                if (depth >= 3) continue;
                File[] children = dir.listFiles();
                if (children != null) for (File f : children) if (f.isDirectory()) queue.add(f);
            }
            return fallbacks.isEmpty() ? base : fallbacks.get(0);
        }

        private static List<File> listFiles(File root, int limit) throws Exception {
            ArrayList<File> result = new ArrayList<>();
            ArrayList<File> queue = new ArrayList<>(); queue.add(root);
            for (int i = 0; i < queue.size() && result.size() < limit; i++) {
                File dir = queue.get(i);
                File[] children = dir.listFiles();
                if (children == null) continue;
                for (File child : children) {
                    result.add(child);
                    if (child.isDirectory()) queue.add(child);
                    if (result.size() >= limit) break;
                }
            }
            return result;
        }

        private static File findFile(List<File> files, String name) {
            for (File f : files) if (f.isFile() && f.getName().equalsIgnoreCase(name)) return f;
            return null;
        }

        private static File findIndex(List<File> files, String webRoot) {
            if (webRoot != null && !webRoot.isEmpty()) {
                for (File f : files) if (f.isFile() && f.getName().equalsIgnoreCase("index.html") && f.getParentFile().getPath().endsWith(webRoot)) return f;
            }
            // Prefer common web build folders, then the shortest path.
            ArrayList<File> matches = new ArrayList<>();
            for (File f : files) if (f.isFile() && f.getName().equalsIgnoreCase("index.html")) matches.add(f);
            Collections.sort(matches, Comparator.comparingInt(f -> f.getPath().length()));
            for (File f : matches) {
                String p = f.getParent().toLowerCase(Locale.ROOT);
                if (p.endsWith("/dist") || p.endsWith("/build") || p.endsWith("/public") || p.endsWith("/www")) return f;
            }
            return matches.isEmpty() ? null : matches.get(0);
        }

        private static boolean findAny(List<File> files, String... names) {
            for (File f : files) if (f.isFile()) for (String name : names) if (f.getName().equalsIgnoreCase(name)) return true;
            return false;
        }

        private static void readRequirements(File f, List<String> deps) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#") || line.startsWith("-") || line.startsWith("git+")) continue;
                    String token = line.split("[<>=!~;\\[]", 2)[0].trim();
                    if (token.matches("[A-Za-z0-9_.-]{1,100}")) deps.add(token);
                }
            } catch (Exception ignored) { }
        }

        private static void readNodeDependencies(File f, List<String> deps) {
            try (InputStream in = new FileInputStream(f); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] b = new byte[8192]; int n;
                while ((n = in.read(b)) != -1) out.write(b, 0, n);
                JSONObject p = new JSONObject(out.toString("UTF-8"));
                addJsonKeys(p.optJSONObject("dependencies"), deps);
                addJsonKeys(p.optJSONObject("devDependencies"), deps);
            } catch (Exception ignored) { }
        }

        private static void addJsonKeys(JSONObject object, List<String> deps) {
            if (object == null) return;
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) deps.add(keys.next());
        }

        private static ArrayList<String> unique(List<String> source) {
            Set<String> seen = new HashSet<>();
            ArrayList<String> result = new ArrayList<>();
            for (String item : source) if (seen.add(item.toLowerCase(Locale.ROOT))) result.add(item);
            return result;
        }

        private static String niceName(String name) {
            return name.replace('_', ' ').replace('-', ' ').trim();
        }

        private static void deleteTree(File file) {
            if (file == null || !file.exists()) return;
            if (file.isDirectory()) {
                File[] children = file.listFiles();
                if (children != null) for (File c : children) deleteTree(c);
            }
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    // ---------------- Minimal loopback static web server ----------------
    static class StaticSiteServer {
        private final File root;
        private ServerSocket server;
        private volatile boolean running;
        private Thread acceptThread;
        private final ExecutorService clients = Executors.newCachedThreadPool();
        private int port;

        StaticSiteServer(File root) throws Exception {
            this.root = root.getCanonicalFile();
            if (!this.root.isDirectory()) throw new IllegalArgumentException("Website folder does not exist");
        }

        void start() throws Exception {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 24);
            port = server.getLocalPort();
            running = true;
            acceptThread = new Thread(() -> {
                while (running) {
                    try {
                        Socket client = server.accept();
                        client.setSoTimeout(10000);
                        clients.execute(() -> serve(client));
                    } catch (Exception e) {
                        if (running) android.util.Log.w("LocalhostLoader", "Accept error", e);
                    }
                }
            }, "LL-static-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
        }

        int getPort() { return port; }
        boolean isRunning() { return running && server != null && !server.isClosed(); }

        void stop() {
            running = false;
            try { if (server != null) server.close(); } catch (Exception ignored) { }
            clients.shutdownNow();
        }

        private void serve(Socket socket) {
            try (Socket s = socket;
                 BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
                 BufferedOutputStream out = new BufferedOutputStream(s.getOutputStream())) {
                String request = reader.readLine();
                if (request == null || request.length() > 8192) return;
                String[] parts = request.split(" ");
                String method = parts.length > 0 ? parts[0] : "GET";
                String target = parts.length > 1 ? parts[1] : "/";
                String header;
                int headerBytes = 0;
                while ((header = reader.readLine()) != null && !header.isEmpty()) {
                    headerBytes += header.length();
                    if (headerBytes > 32768) return;
                }
                if (!"GET".equals(method) && !"HEAD".equals(method)) {
                    writeText(out, "405 Method Not Allowed", "Method not allowed", "text/plain; charset=utf-8", "Allow: GET, HEAD\r\n", "HEAD".equals(method));
                    return;
                }
                File file = resolve(target);
                if (file == null) {
                    writeText(out, "403 Forbidden", "Forbidden", "text/plain; charset=utf-8", "", "HEAD".equals(method));
                    return;
                }
                if (file.isDirectory()) file = new File(file, "index.html");
                if (!file.isFile()) {
                    // SPA route fallback: an extension-less route maps to index.html.
                    String clean = target.split("\\?", 2)[0];
                    if (!clean.contains(".")) file = new File(root, "index.html");
                }
                if (!file.isFile() || !file.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator)) {
                    writeText(out, "404 Not Found", "Not found", "text/plain; charset=utf-8", "", "HEAD".equals(method));
                    return;
                }
                String mime = mime(file.getName());
                out.write(("HTTP/1.1 200 OK\r\n" +
                    "Content-Type: " + mime + "\r\n" +
                    "Content-Length: " + file.length() + "\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "X-Content-Type-Options: nosniff\r\n" +
                    "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                if (!"HEAD".equals(method)) {
                    try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
                        byte[] b = new byte[32768]; int n;
                        while ((n = in.read(b)) != -1) out.write(b, 0, n);
                    }
                }
                out.flush();
            } catch (Exception ignored) { }
        }

        private File resolve(String target) throws Exception {
            String raw = target.split("\\?", 2)[0].split("#", 2)[0];
            String decoded = URLDecoder.decode(raw, "UTF-8");
            if (decoded.indexOf('\\') >= 0 || decoded.contains("\u0000")) return null;
            while (decoded.startsWith("/")) decoded = decoded.substring(1);
            File candidate = new File(root, decoded).getCanonicalFile();
            String rootPath = root.getCanonicalPath();
            if (!candidate.getPath().equals(rootPath) && !candidate.getPath().startsWith(rootPath + File.separator)) return null;
            return candidate;
        }

        private static void writeText(OutputStream out, String status, String text, String type, String extra, boolean head) throws Exception {
            byte[] body = text.getBytes(StandardCharsets.UTF_8);
            out.write(("HTTP/1.1 " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length +
                "\r\n" + extra + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            if (!head) out.write(body);
            out.flush();
        }

        private static String mime(String name) {
            String n = name.toLowerCase(Locale.ROOT);
            if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html; charset=utf-8";
            if (n.endsWith(".js") || n.endsWith(".mjs")) return "text/javascript; charset=utf-8";
            if (n.endsWith(".css")) return "text/css; charset=utf-8";
            if (n.endsWith(".json")) return "application/json; charset=utf-8";
            if (n.endsWith(".svg")) return "image/svg+xml";
            if (n.endsWith(".png")) return "image/png";
            if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
            if (n.endsWith(".webp")) return "image/webp";
            if (n.endsWith(".gif")) return "image/gif";
            if (n.endsWith(".ico")) return "image/x-icon";
            if (n.endsWith(".wasm")) return "application/wasm";
            if (n.endsWith(".woff2")) return "font/woff2";
            if (n.endsWith(".woff")) return "font/woff";
            if (n.endsWith(".ttf")) return "font/ttf";
            if (n.endsWith(".mp4")) return "video/mp4";
            if (n.endsWith(".mp3")) return "audio/mpeg";
            if (n.endsWith(".txt")) return "text/plain; charset=utf-8";
            return "application/octet-stream";
        }
    }
}
