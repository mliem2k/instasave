package app.mliem.extension.instasave;

import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.lang.ref.WeakReference;
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
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    /** The view behind {@link #RECENT}'s first entry, so a caller with no view of its own (the
     *  floating save button, which is not anchored to any particular post) can still resolve a
     *  proper username and media id instead of saving with a bare, unnamed file. */
    private static volatile WeakReference<View> mostRecentView;

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
                mostRecentView = new WeakReference<>(target);
            }

            attachLongPress(target);
        } catch (Throwable t) {
            // This is a hot path in Instagram's own rendering. Never let it throw.
            InstaSave.log("onImageUrlBound failed", t);
        }
    }

    /**
     * Adds the save gesture without touching whatever long press behaviour a view already has.
     *
     * <p>This used to skip any view where {@code isLongClickable()} was already true, on the
     * reasoning that such a view (post previews, reorder handles in the composer) had its own
     * long press wired through {@code setOnLongClickListener} and had a menu for saving anyway.
     * The premise stopped holding: Instagram's own overflow menu is no longer reliably reachable
     * on every build, so a feed post or a carousel slide, which almost always reports
     * {@code isLongClickable() == true} for reasons that have nothing to do with saving, was left
     * with no save action at all.
     *
     * <p>Rather than replace {@code setOnLongClickListener} and risk breaking whatever claimed it
     * (the composer's own reorder drag, most importantly, which lives behind the same seam since
     * it binds URLs too), this attaches a plain {@code OnTouchListener} that runs its own long
     * press timer and always returns false. Returning false never consumes the event, so it falls
     * through to the view's normal touch handling exactly as if this listener were not here;
     * Instagram's own long click, double tap detection, and any gesture recognizer it owns keep
     * working unmodified. The two mechanisms are independent slots on {@link View}, so nothing here
     * needs to know whether the other one is in use.
     */
    private static void attachLongPress(final View view) {
        final int touchSlopSquared;
        int slop;
        try {
            slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
        } catch (Throwable t) {
            slop = 24; // a reasonable default if the context is unavailable for some reason
        }
        touchSlopSquared = slop * slop;

        view.setOnTouchListener(new View.OnTouchListener() {
            private Runnable pending;
            private float downX;
            private float downY;

            @Override
            public boolean onTouch(View touched, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        cancelPending();
                        downX = event.getRawX();
                        downY = event.getRawY();
                        pending = new Runnable() {
                            @Override
                            public void run() {
                                pending = null;
                                triggerSave(touched);
                            }
                        };
                        HANDLER.postDelayed(pending, ViewConfiguration.getLongPressTimeout());
                        break;
                    case MotionEvent.ACTION_MOVE:
                        // A little jitter is normal for a held finger; only real movement, past
                        // the platform's own touch slop, means this was never a long press.
                        float dx = event.getRawX() - downX;
                        float dy = event.getRawY() - downY;
                        if (dx * dx + dy * dy > touchSlopSquared) {
                            cancelPending();
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        cancelPending();
                        break;
                    default:
                        break;
                }
                // Never consumed: Instagram's own touch handling on this view runs unaffected.
                return false;
            }

            private void cancelPending() {
                if (pending != null) {
                    HANDLER.removeCallbacks(pending);
                    pending = null;
                }
            }
        });
    }

    private static void triggerSave(View view) {
        String url;
        synchronized (BOUND_URLS) {
            url = BOUND_URLS.get(view);
        }
        if (url == null) {
            return;
        }
        Downloader.enqueue(view.getContext(), describe(view, url));
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

    /**
     * The view behind {@link #mostRecentUrl()}, or null if nothing has bound yet or that view has
     * since been recycled out of the window. Lets a caller with no view of its own, such as the
     * floating save button, position itself on top of the post that URL actually belongs to
     * instead of sitting at a fixed spot on screen.
     */
    public static View mostRecentView() {
        WeakReference<View> ref = mostRecentView;
        View view = ref != null ? ref.get() : null;
        return view != null && view.isAttachedToWindow() ? view : null;
    }

    /**
     * The most recently bound image, resolved with as much metadata as a graph walk from its
     * view can find, or null if nothing has bound yet. In practice this is whichever post is
     * currently on screen, since Instagram only renders what is near the viewport.
     */
    public static MediaUrlResolver.Resolved mostRecentResolved() {
        String url;
        View view;
        synchronized (BOUND_URLS) {
            url = RECENT.peekFirst();
            view = mostRecentView != null ? mostRecentView.get() : null;
        }
        if (url == null) {
            return null;
        }
        if (view == null) {
            // The view was garbage collected between binding and this call. Nothing to walk,
            // so save with a bare, unnamed file rather than re-deriving the same URL through
            // MediaUrlResolver's own fallback to this exact method.
            return new MediaUrlResolver.Resolved(
                    url, MediaUrlResolver.isVideoUrl(url), null, null,
                    Collections.<MediaUrlResolver.Candidate>emptyList());
        }
        return describe(view, url);
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
