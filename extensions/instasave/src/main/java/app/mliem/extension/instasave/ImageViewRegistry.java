package app.mliem.extension.instasave;

import android.view.View;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Tracks which CDN URL each Instagram image view most recently bound.
 *
 * <p>Two jobs. First, it powers long press to save, which is how profile pictures and any other
 * image without an options menu get a save action. Second, it keeps a small ring of recently
 * bound URLs that {@link MediaUrlResolver} falls back to when a click handler turns out to be a
 * synthetic class that captured nothing reachable.
 *
 * <p>Called from Instagram's image URL setter, which runs on the main thread for every image in
 * every list, so everything here stays O(1) and allocation free on the common path.
 */
public final class ImageViewRegistry {

    private static final int RECENT_CAPACITY = 12;

    private static final Map<View, String> BOUND_URLS = new WeakHashMap<>();
    private static final ArrayDeque<String> RECENT = new ArrayDeque<>();

    private ImageViewRegistry() {
    }

    /**
     * Injected call site: Instagram's image view is about to load {@code imageUrl}.
     *
     * @param view     the image view, which is {@code this} at the patched call site
     * @param imageUrl an Instagram {@code ImageUrl}, or a plain URL string
     */
    public static void onImageUrlBound(Object view, Object imageUrl) {
        try {
            if (!(view instanceof View)) {
                return;
            }
            String url = extractUrl(imageUrl);
            if (url == null) {
                return;
            }

            View target = (View) view;
            synchronized (BOUND_URLS) {
                BOUND_URLS.put(target, url);
                if (RECENT.peekFirst() == null || !url.equals(RECENT.peekFirst())) {
                    RECENT.addFirst(url);
                    while (RECENT.size() > RECENT_CAPACITY) {
                        RECENT.removeLast();
                    }
                }
            }

            attachLongPress(target);
        } catch (Throwable t) {
            // This is a hot path in Instagram's own rendering. Never let it throw.
            InstaSave.log("onImageUrlBound failed", t);
        }
    }

    /**
     * Adds the save gesture, but only to views nothing else claims.
     *
     * <p>{@code isLongClickable()} is set by {@code setOnLongClickListener}, so a true value
     * means Instagram already wired its own long press (post previews, reorder handles) and
     * replacing it would break that behaviour. Those surfaces have an options menu instead.
     */
    private static void attachLongPress(final View view) {
        if (view.isLongClickable()) {
            return;
        }
        view.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View clicked) {
                String url;
                synchronized (BOUND_URLS) {
                    url = BOUND_URLS.get(clicked);
                }
                if (url == null) {
                    return false;
                }
                Downloader.enqueue(clicked.getContext(), describe(clicked, url));
                return true;
            }
        });
    }

    /**
     * Builds the download request for a long pressed view. The URL is already known, so the
     * graph walk runs only to pick up a username and media id for the file name, and its
     * failure is not fatal.
     */
    private static MediaUrlResolver.Resolved describe(View view, String url) {
        String username = null;
        String mediaId = null;
        try {
            MediaUrlResolver.Resolved walked = MediaUrlResolver.resolve(view);
            if (walked != null) {
                username = walked.username;
                mediaId = walked.mediaId;
            }
        } catch (Throwable t) {
            InstaSave.log("metadata walk failed, saving without a name", t);
        }
        return new MediaUrlResolver.Resolved(
                url, MediaUrlResolver.isVideoUrl(url), username, mediaId, Collections.<MediaUrlResolver.Candidate>emptyList());
    }

    /** The URL bound most recently anywhere in the app, or null if nothing has bound yet. */
    public static String mostRecentUrl() {
        synchronized (BOUND_URLS) {
            return RECENT.peekFirst();
        }
    }

    private static String extractUrl(Object imageUrl) {
        if (imageUrl == null) {
            return null;
        }
        if (imageUrl instanceof String) {
            String value = (String) imageUrl;
            return MediaUrlResolver.isMediaUrl(value) ? value : null;
        }
        try {
            // ImageUrl is one of the interfaces Instagram leaves unobfuscated.
            Object value = imageUrl.getClass().getMethod("getUrl").invoke(imageUrl);
            if (value instanceof String && MediaUrlResolver.isMediaUrl((String) value)) {
                return (String) value;
            }
        } catch (Throwable ignored) {
            // Not an ImageUrl. Nothing to record.
        }
        return null;
    }
}
