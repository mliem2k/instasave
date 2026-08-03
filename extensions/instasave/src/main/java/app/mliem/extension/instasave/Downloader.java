package app.mliem.extension.instasave;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Writes a CDN media URL to shared storage.
 *
 * <p>On API 29 and above this goes through MediaStore into {@code Download/InstaSave}, which
 * needs no runtime permission at all. Below that it falls back to a direct file write, which
 * does need WRITE_EXTERNAL_STORAGE; Instagram already declares that permission, so the only
 * failure mode is the user having revoked it, and that is reported rather than swallowed.
 */
public final class Downloader {

    private static final String FOLDER = "InstaSave";
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    /** Guards against a double tap queueing the same file twice. */
    private static final AtomicReference<String> lastQueuedUrl = new AtomicReference<>(null);

    private Downloader() {
    }

    /**
     * Queues a download and reports progress to the user via toasts. Safe to call from the
     * main thread; all network and disk work happens on a background executor.
     */
    public static void enqueue(final Context preferredContext, final MediaUrlResolver.Resolved media) {
        if (media == null || media.url == null) {
            InstaSave.toast(preferredContext, "InstaSave: could not find the media URL");
            return;
        }

        final Context context = InstaSave.context(preferredContext);
        if (context == null) {
            InstaSave.log("download dropped, no context available");
            return;
        }

        if (media.url.equals(lastQueuedUrl.getAndSet(media.url))) {
            InstaSave.log("ignoring repeat request for the same URL");
            return;
        }

        final String filename = buildFilename(media);
        InstaSave.toast(context, "InstaSave: saving " + (media.video ? "video" : "photo"));

        IO.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String location = save(context, media.url, filename, media.video);
                    InstaSave.toast(context, "InstaSave: saved to " + location);
                    InstaSave.log("saved " + filename + " to " + location);
                } catch (Throwable t) {
                    InstaSave.log("save failed for " + filename, t);
                    InstaSave.toast(context, "InstaSave: save failed, " + describe(t));
                } finally {
                    lastQueuedUrl.compareAndSet(media.url, null);
                }
            }
        });
    }

    /** Builds "{username}_{kind}_{id}_{timestamp}.{ext}", falling back where metadata is absent. */
    static String buildFilename(MediaUrlResolver.Resolved media) {
        String user = sanitize(media.username);
        String id = sanitize(media.mediaId);
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());

        StringBuilder name = new StringBuilder();
        if (user != null) {
            name.append(user).append('_');
        }
        name.append(media.video ? "video" : "photo").append('_');
        if (id != null) {
            name.append(id).append('_');
        }
        name.append(stamp);
        name.append(media.video ? ".mp4" : ".jpg");
        return name.toString();
    }

    private static String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        String cleaned = value.replaceAll("[^A-Za-z0-9._]", "");
        return cleaned.isEmpty() ? null : cleaned;
    }

    /** @return a human readable description of where the file landed. */
    private static String save(Context context, String url, String filename, boolean video)
            throws Exception {
        String mimeType = video ? "video/mp4" : "image/jpeg";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, url, filename, mimeType);
        } else {
            saveViaLegacyFile(context, url, filename);
        }
        return "Download/" + FOLDER;
    }

    private static void saveViaMediaStore(Context context, String url, String filename, String mimeType)
            throws Exception {
        ContentResolver resolver = context.getContentResolver();

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + File.separator + FOLDER);
        // Hide the row until the bytes are complete so gallery apps never index a partial file.
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri item = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (item == null) {
            throw new IllegalStateException("MediaStore rejected the insert");
        }

        boolean complete = false;
        try {
            OutputStream out = resolver.openOutputStream(item);
            if (out == null) {
                throw new IllegalStateException("MediaStore returned no output stream");
            }
            try {
                transfer(url, out);
            } finally {
                out.close();
            }
            complete = true;
        } finally {
            if (complete) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                resolver.update(item, done, null, null);
            } else {
                // Leaving a pending row behind would be an invisible, undeletable stub.
                try {
                    resolver.delete(item, null, null);
                } catch (Throwable ignored) {
                    // Nothing useful to do; the original failure is already propagating.
                }
            }
        }
    }

    private static void saveViaLegacyFile(Context context, String url, String filename)
            throws Exception {
        if (context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("storage permission is not granted");
        }

        File directory = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                FOLDER);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("could not create " + directory);
        }

        File target = new File(directory, filename);
        OutputStream out = new FileOutputStream(target);
        try {
            transfer(url, out);
        } finally {
            out.close();
        }

        // Without this the file exists but stays invisible to the gallery until a reboot.
        MediaScannerConnection.scanFile(context, new String[]{target.getAbsolutePath()}, null, null);
    }

    private static void transfer(String url, OutputStream out) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "*/*");
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status > 299) {
                throw new IllegalStateException("CDN returned HTTP " + status);
            }
            InputStream in = connection.getInputStream();
            try {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                out.flush();
            } finally {
                in.close();
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return (message == null || message.isEmpty()) ? t.getClass().getSimpleName() : message;
    }
}
