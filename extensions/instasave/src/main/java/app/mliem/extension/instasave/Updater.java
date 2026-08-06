package app.mliem.extension.instasave;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

// BuildConfig is generated under the module's AGP namespace (app.mliem.extension), not this
// source package. INSTASAVE_VERSION is injected there from the gradle `version` property.
import app.mliem.extension.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Checks GitHub Releases for a newer InstaSave build and installs it via PackageInstaller.
 *
 * <p>Wired in by a one line patch injection at {@code InstagramAppShell.onCreate}, mirroring how
 * {@link InstaSave#setApplication(Context)} is injected. All network and disk work happens on a
 * dedicated single thread executor; nothing blocks the main thread, and the network is touched at
 * most once per 24 hours.
 *
 * <p>The automatic background check in {@link #start} only posts a notification when it finds a
 * newer build; the download itself waits for that notification's action to be tapped, since a
 * silent check has no consent gesture of its own. {@link #checkNow}, the "check for updates now"
 * button, downloads and installs immediately instead: the tap that triggered the check already is
 * the consent, and {@code POST_NOTIFICATIONS} is not granted by default on a fresh install, which
 * previously left that button announcing an update it had no way to actually deliver.
 *
 * <p>The whole feature is silent when it cannot do anything. If the repository is private (its
 * releases API returns 404 to an unauthenticated caller), or the device is offline, or there is
 * simply no newer release, nothing is shown. A self updater that nagged about the network on
 * every launch would be worse than none.
 *
 * <p>The download itself runs an ongoing progress notification the whole time, since the APK is
 * large enough that a bare "downloading" toast with nothing after it reads as stuck. Determinate
 * whenever GitHub's response carries a content length, which it always does for a release asset;
 * an indeterminate bar with a running byte count otherwise. Switches to "installing" once the
 * bytes are all down, and is cancelled the moment {@link #handleInstallStatus} reaches a terminal
 * status or hands off to the system's own install confirmation screen. Skipped entirely when
 * {@code POST_NOTIFICATIONS} is not granted, the same permission gate {@link #notifyUpdate} and
 * {@link #beginDownloadAndInstall} answer with a toast instead.
 *
 * <p>The hard prerequisite is a stable signing key. Android rejects an update whose signature
 * differs from the installed app, so every published InstaSave APK must be signed with the same
 * key (see tools/setup_keystore.sh). When that constraint is not met the install fails with
 * INSTALL_FAILED_UPDATE_INCOMPATIBLE, which {@link #handleInstallStatus} detects and explains.
 */
public final class Updater {

    // Repository whose releases are checked. GitHub's releases API and asset downloads are
    // unauthenticated only for PUBLIC repos; a private repo answers 404 and the updater stays
    // inert. Change these two constants to point elsewhere.
    private static final String REPO_OWNER = "mliem2k";
    private static final String REPO_NAME = "instasave";

    private static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/" + REPO_OWNER + "/" + REPO_NAME + "/releases/latest";
    private static final String USER_AGENT = "InstaSave-Updater";

    // Debounce so the check runs at most once a day even though it is triggered at every launch.
    private static final String PREFS = "instasave_updater";
    private static final String KEY_LAST_CHECK = "last_check_ms";
    private static final long CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000;

    private static final String CHANNEL_ID = "instasave_updates";
    private static final int NOTIFICATION_ID = 0x1D5A;
    private static final String ACTION_DOWNLOAD_INSTALL =
            "app.mliem.extension.instasave.DOWNLOAD_INSTALL";
    private static final String ACTION_INSTALL_STATUS =
            "app.mliem.extension.instasave.INSTALL_STATUS";
    private static final String EXTRA_APK_URL = "apk_url";
    private static final String EXTRA_TAG = "tag";

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean receiverRegistered = new AtomicBoolean(false);
    private static final AtomicBoolean busy = new AtomicBoolean(false);

    private Updater() {
    }

    /**
     * Entry point injected at {@code InstagramAppShell.onCreate}. Takes the Context directly, so
     * it never depends on {@link InstaSave#setApplication} having run first; the patcher does not
     * guarantee the order of the two injections at index 0.
     */
    public static void start(final Context context) {
        if (context == null) {
            return;
        }
        final Context app = context.getApplicationContext() != null
                ? context.getApplicationContext()
                : context;
        registerReceiver(app);
        if (!Settings.autoUpdate()) {
            // The user turned automatic checks off in settings. The "check now" button still works.
            return;
        }
        IO.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    UpdateInfo info = findUpdate(app, false);
                    if (info != null && info.apkUrl != null) {
                        notifyUpdate(app, info.tag, info.apkUrl);
                    }
                } catch (Throwable t) {
                    // Never surfaced. A failed check is not the user's problem.
                    InstaSave.log("update check failed", t);
                }
            }
        });
    }

    /**
     * Checks now, on demand from the settings screen. Ignores the automatic-check preference and
     * the once-a-day debounce, and tells the user the outcome either way, since they asked.
     *
     * <p>When a newer release exists this downloads and installs it directly, rather than posting
     * a notification and waiting for it to be tapped. The automatic path in {@link #start} keeps
     * the notification, since a silent background check has no consent gesture of its own and
     * needs one before it starts pulling a large file. Tapping "check for updates now" already is
     * that gesture, so requiring a second one, through a notification whose permission is not
     * granted on a fresh install, only left this button unable to do anything on the exact path it
     * exists for.
     */
    public static void checkNow(Context context) {
        if (context == null) {
            return;
        }
        final Context app = context.getApplicationContext() != null
                ? context.getApplicationContext()
                : context;
        registerReceiver(app);
        InstaSave.toast(app, "Checking for updates");
        IO.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    UpdateInfo info = findUpdate(app, true);
                    if (info == null) {
                        InstaSave.toast(app, "InstaSave is up to date");
                    } else if (info.apkUrl == null) {
                        InstaSave.toast(app, "InstaSave " + stripV(info.tag)
                                + " is still uploading. Try again in a few minutes.");
                    } else {
                        beginDownloadAndInstall(app, info.apkUrl, info.tag);
                    }
                } catch (Throwable t) {
                    InstaSave.log("manual update check failed", t);
                    InstaSave.toast(app, "Update check failed");
                }
            }
        });
    }

    // region check

    /**
     * The release tag and download URL of a newer build, once one has been found.
     *
     * <p>{@code apkUrl} is null when a newer release exists but its APK is not downloadable yet.
     * A release that large is published first and has its asset uploaded afterwards, so there is a
     * window of minutes where the release is visible and the download would 404.
     */
    private static final class UpdateInfo {
        final String tag;
        final String apkUrl;

        UpdateInfo(String tag, String apkUrl) {
            this.tag = tag;
            this.apkUrl = apkUrl;
        }
    }

    /**
     * @param force ignore the once-a-day debounce (a manual check from settings)
     * @return the newer release's tag and APK URL, or null when there is none
     */
    private static UpdateInfo findUpdate(Context app, boolean force) throws Exception {
        long now = System.currentTimeMillis();
        long last = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_CHECK, 0L);
        if (!force && now - last < CHECK_INTERVAL_MS) {
            return null;
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(LATEST_RELEASE_API).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        // GitHub returns 403 to a request with no User-Agent.
        connection.setRequestProperty("User-Agent", USER_AGENT);
        try {
            int code = connection.getResponseCode();
            if (code == 404 || code == 401) {
                // Private repo, or no release yet. Record the check so we do not retry every
                // launch, then stay silent.
                InstaSave.log("release API returned " + code + " (private repo or no release)");
                markChecked(app, now);
                return null;
            }
            if (code < 200 || code > 299) {
                // Transient. Do NOT mark checked, so the next launch retries.
                InstaSave.log("release API HTTP " + code);
                return null;
            }

            String body = readAll(connection.getInputStream());

            JSONObject json = new JSONObject(body);
            String tag = json.optString("tag_name", "");
            String apkUrl = firstApkAssetUrl(json.optJSONArray("assets"));
            boolean newer = !tag.isEmpty() && isNewer(tag, currentVersion());
            if (newer && apkUrl == null) {
                // The release is up but its APK has not finished uploading. Deliberately not
                // marked as checked: the next launch should look again rather than go quiet for a
                // whole day over a file that landed a minute later.
                InstaSave.log("release " + tag + " has no downloadable APK asset yet");
                return new UpdateInfo(tag, null);
            }
            markChecked(app, now);
            return newer ? new UpdateInfo(tag, apkUrl) : null;
        } finally {
            connection.disconnect();
        }
    }

    private static void markChecked(Context app, long now) {
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST_CHECK, now).apply();
    }

    private static String firstApkAssetUrl(JSONArray assets) {
        if (assets == null) {
            return null;
        }
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) {
                continue;
            }
            String url = asset.optString("browser_download_url", "");
            if (isDownloadableApkAsset(
                    asset.optString("name", ""), asset.optString("state", ""), url)) {
                return url;
            }
        }
        return null;
    }

    /**
     * True only for a release asset that can actually be fetched right now.
     *
     * <p>An asset appears in the release the moment its upload begins, in state "starter", and its
     * download URL answers 404 until that upload completes. A release this size is published well
     * before its APK finishes uploading, so treating a listed asset as a downloadable one hands
     * out a URL that fails. Package visible and pure so it is unit tested on a plain JVM.
     */
    static boolean isDownloadableApkAsset(String name, String state, String url) {
        return name != null && name.endsWith(".apk")
                && "uploaded".equals(state)
                && url != null && !url.isEmpty();
    }

    /** This build's InstaSave version, injected from the gradle {@code version} property. */
    private static String currentVersion() {
        return BuildConfig.INSTASAVE_VERSION;
    }

    /**
     * True when {@code remoteTag} (optionally "v" prefixed) is a strictly higher dotted version
     * than {@code localVersion}. Package visible and pure so it is unit tested on a plain JVM.
     */
    static boolean isNewer(String remoteTag, String localVersion) {
        int[] remote = parseVersion(remoteTag);
        int[] local = parseVersion(localVersion);
        int length = Math.max(remote.length, local.length);
        for (int i = 0; i < length; i++) {
            int r = i < remote.length ? remote[i] : 0;
            int l = i < local.length ? local[i] : 0;
            if (r != l) {
                return r > l;
            }
        }
        return false;
    }

    static int[] parseVersion(String version) {
        String value = version == null ? "" : version.trim();
        if (value.startsWith("v") || value.startsWith("V")) {
            value = value.substring(1);
        }
        String[] parts = value.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].replaceAll("[^0-9].*$", ""));
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    // endregion

    // region notify

    /**
     * Posting needs POST_NOTIFICATIONS on API 33+. Instagram already holds it, but a user could
     * have revoked it, and both callers of this run off the main thread with no Activity, so it
     * cannot be requested here.
     */
    private static boolean canPostNotifications(Context app) {
        return Build.VERSION.SDK_INT < 33
                || app.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    == PackageManager.PERMISSION_GRANTED;
    }

    private static void notifyUpdate(Context app, String tag, String apkUrl) {
        if (!canPostNotifications(app)) {
            InstaSave.toast(null, "InstaSave " + stripV(tag) + " available");
            return;
        }

        NotificationManager manager =
                (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "InstaSave updates", NotificationManager.IMPORTANCE_LOW));
        }

        Intent action = new Intent(ACTION_DOWNLOAD_INSTALL)
                .setPackage(app.getPackageName())
                .putExtra(EXTRA_APK_URL, apkUrl)
                .putExtra(EXTRA_TAG, tag);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pending = PendingIntent.getBroadcast(app, 1, action, flags);

        Notification.Builder builder = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(app, CHANNEL_ID)
                : new Notification.Builder(app);
        Notification notification = builder
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("InstaSave update available")
                .setContentText("Version " + stripV(tag) + ". Tap to download and install.")
                .setAutoCancel(true)
                .addAction(new Notification.Action.Builder(null, "Download and install", pending).build())
                .build();
        manager.notify(NOTIFICATION_ID, notification);
    }

    private static String stripV(String tag) {
        return (tag.startsWith("v") || tag.startsWith("V")) ? tag.substring(1) : tag;
    }

    // endregion

    // region download and install

    private static void registerReceiver(final Context app) {
        if (!receiverRegistered.compareAndSet(false, true)) {
            return;
        }

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String action = intent.getAction();
                if (ACTION_DOWNLOAD_INSTALL.equals(action)) {
                    onDownloadRequested(app, intent);
                } else if (ACTION_INSTALL_STATUS.equals(action)) {
                    handleInstallStatus(app, intent);
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_DOWNLOAD_INSTALL);
        filter.addAction(ACTION_INSTALL_STATUS);
        // The actions are private to us. On API 33+ a receiver must declare exported state, and
        // these must not be reachable by other apps.
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            app.registerReceiver(receiver, filter);
        }
    }

    private static void onDownloadRequested(final Context app, Intent intent) {
        String url = intent.getStringExtra(EXTRA_APK_URL);
        String tag = intent.getStringExtra(EXTRA_TAG);
        if (url == null) {
            cancelNotification(app);
            return;
        }
        beginDownloadAndInstall(app, url, tag);
    }

    /**
     * Shared by the notification's "Download and install" action and by {@link #checkNow}, which
     * calls this directly instead of waiting for a tap. Cancelling a notification that was never
     * posted is a no-op, so this is safe to call from either path.
     */
    private static void beginDownloadAndInstall(final Context app, final String url, final String tag) {
        // The "install unknown apps" grant is per source. If it is missing, send the user to
        // grant it rather than letting the install silently do nothing. Leave the notification up
        // so they can tap it again after granting; dismissing it here would make "tap again"
        // impossible, and the extras survive on the same FLAG_UPDATE_CURRENT pending intent.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !app.getPackageManager().canRequestPackageInstalls()) {
            InstaSave.toast(null, "Allow installing updates for InstaSave, then tap again");
            Intent settings = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + app.getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                app.startActivity(settings);
            } catch (Throwable t) {
                InstaSave.log("could not open unknown-sources settings", t);
            }
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            return;
        }
        // Committed to the download now, so the notification (if one exists) has served its
        // purpose; the progress notification below reuses the same id.
        cancelNotification(app);
        final String versionLabel = stripV(tag == null ? "" : tag);
        InstaSave.toast(null, "Downloading InstaSave " + versionLabel);
        IO.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    File apk = download(app, url, versionLabel);
                    postProgressNotification(app, "Installing InstaSave " + versionLabel, null, 0, true);
                    install(app, apk);
                    // busy is cleared, and the notification cancelled, on the terminal install
                    // status callback.
                } catch (Throwable t) {
                    InstaSave.log("download or install failed", t);
                    InstaSave.toast(null, "InstaSave update failed");
                    cancelNotification(app);
                    busy.set(false);
                }
            }
        });
    }

    /**
     * Posts and updates an ongoing progress notification for as long as the download runs, so a
     * multi hundred megabyte APK does not sit there with no visible sign of life beyond the one
     * toast at the start. Determinate whenever the server sends a content length (GitHub's CDN
     * always does for a release asset); otherwise falls back to an indeterminate bar with a
     * running byte count, rather than pretending to a percentage it does not have.
     */
    private static void postProgressNotification(
            Context app, String title, String text, int percent, boolean indeterminate) {
        if (!canPostNotifications(app)) {
            return;
        }
        NotificationManager manager =
                (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "InstaSave updates", NotificationManager.IMPORTANCE_LOW));
        }
        Notification.Builder builder = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(app, CHANNEL_ID)
                : new Notification.Builder(app);
        builder.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, percent, indeterminate);
        if (text != null) {
            builder.setContentText(text);
        }
        manager.notify(NOTIFICATION_ID, builder.build());
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.US, "%.0f KB", kb);
        }
        return String.format(Locale.US, "%.1f MB", kb / 1024.0);
    }

    private static File download(Context app, String url, String versionLabel) throws Exception {
        File out = new File(app.getCacheDir(), "instasave_update.apk");
        String title = "Downloading InstaSave " + versionLabel;
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code > 299) {
                throw new IllegalStateException("download HTTP " + code);
            }
            long total = connection.getContentLengthLong();
            postProgressNotification(app, title, null, 0, total <= 0);
            long bytesRead = 0;
            int lastPercent = -1;
            long lastUpdateMs = System.currentTimeMillis();
            try (InputStream in = connection.getInputStream();
                 OutputStream os = new FileOutputStream(out)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                    bytesRead += read;
                    if (total > 0) {
                        int percent = (int) Math.min(100, bytesRead * 100L / total);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            postProgressNotification(app, title, percent + "%", percent, false);
                        }
                    } else {
                        long now = System.currentTimeMillis();
                        if (now - lastUpdateMs >= 500) {
                            lastUpdateMs = now;
                            postProgressNotification(
                                    app, title, humanBytes(bytesRead) + " downloaded", 0, true);
                        }
                    }
                }
                os.flush();
            }
            if (total > 0 && bytesRead != total) {
                // A connection dropped mid transfer leaves a truncated but perfectly readable
                // file, which the installer then rejects as a corrupt APK with no hint that the
                // download was the problem. Fail here, where the cause is still known.
                throw new IllegalStateException(
                        "download truncated at " + bytesRead + " of " + total + " bytes");
            }
        } finally {
            connection.disconnect();
        }
        return out;
    }

    private static void install(Context context, File apkFile) throws Exception {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();

        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());

        int sessionId = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(sessionId);
        boolean committed = false;
        try {
            long length = apkFile.length();
            try (OutputStream out = session.openWrite("instasave_update", 0, length);
                 InputStream in = new FileInputStream(apkFile)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                session.fsync(out);
            }

            Intent statusIntent = new Intent(ACTION_INSTALL_STATUS).setPackage(context.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // The installer writes EXTRA_STATUS and EXTRA_INTENT into this PendingIntent, so
                // on API 31+ it must be mutable or commit throws.
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            PendingIntent statusSender =
                    PendingIntent.getBroadcast(context, sessionId, statusIntent, flags);
            session.commit(statusSender.getIntentSender());
            committed = true;
        } finally {
            // A committed session must not be abandoned; it is now the installer's. An
            // uncommitted one (openWrite/commit threw) must be, or it leaks toward the per-uid
            // session cap. session.close only releases our handle either way.
            if (!committed) {
                try {
                    installer.abandonSession(sessionId);
                } catch (Throwable ignored) {
                    // The original failure is already propagating; nothing useful to add.
                }
            }
            session.close();
        }
    }

    private static void handleInstallStatus(Context context, Intent intent) {
        int status = intent.getIntExtra(
                PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION: {
                // The system hands back a confirmation Activity intent. A receiver has no Activity
                // context, so it must be launched as a new task. Our own progress notification has
                // served its purpose either way: the system's own confirmation UI is now the
                // visible sign of progress, or, if it failed to launch, the flow is over.
                cancelNotification(context);
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        context.startActivity(confirm);
                        // Leave busy set: the flow is still in progress until the user confirms
                        // or cancels, and a cancel arrives here later as STATUS_FAILURE_ABORTED.
                    } catch (Throwable t) {
                        InstaSave.log("could not launch install confirmation", t);
                        busy.set(false);
                    }
                } else {
                    // No confirmation intent means no further callback will ever arrive, so
                    // releasing busy here is the only way a later update can start.
                    busy.set(false);
                }
                break;
            }
            case PackageInstaller.STATUS_SUCCESS:
                busy.set(false);
                cancelNotification(context);
                InstaSave.toast(null, "InstaSave updated. Reopen to finish.");
                break;
            default: {
                busy.set(false);
                cancelNotification(context);
                String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                if (message != null && message.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE")) {
                    // The signing key differs from the installed build. This is the one failure a
                    // user can act on, so it gets its own message instead of a raw code.
                    InstaSave.toast(null,
                            "Update needs a reinstall (signing key changed). "
                                    + "Uninstall InstaSave, then install the new version.");
                } else {
                    InstaSave.toast(null, "InstaSave update failed"
                            + (message != null ? ": " + message : ""));
                }
                InstaSave.log("install status " + status + " msg=" + message);
            }
        }
    }

    // endregion

    // region helpers

    private static void cancelNotification(Context app) {
        NotificationManager manager =
                (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancel(NOTIFICATION_ID);
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            bos.write(buffer, 0, read);
        }
        return bos.toString("UTF-8");
    }

    // endregion
}
