package app.mliem.extension.instasave;

import android.graphics.Rect;
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
 * image without an options menu get a save action. Second, it keeps a small history of recent
 * binds, view included, that both {@link MediaUrlResolver} and the floating save button fall
 * back to.
 *
 * <p>Called from Instagram's image URL setter, which runs on the main thread for every image in
 * every list, so everything here stays O(1) and allocation free on the common path.
 */
public final class ImageViewRegistry {

    private static final int RECENT_CAPACITY = 12;

    /** One bind event: the URL a view loaded, and a weak handle back to that view. */
    private static final class Bind {
        final String url;
        final WeakReference<View> view;

        Bind(String url, View view) {
            this.url = url;
            this.view = new WeakReference<>(view);
        }
    }

    private static final Map<View, String> BOUND_URLS = new WeakHashMap<>();

    /** Most recent first. Kept as full bind records, not just URLs, so a caller with no view of
     *  its own can walk backward past a bind that is no longer on screen to the next one that
     *  is, rather than trusting the single last bind unconditionally. A carousel or a Reels
     *  pager keeps the next page attached one ahead so a swipe has something to show, so
     *  "attached to a window" alone does not mean "the thing on screen right now": the very
     *  last bind can legitimately be for a page sitting just off to the side. */
    private static final ArrayDeque<Bind> RECENT = new ArrayDeque<>();

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

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
                Bind front = RECENT.peekFirst();
                boolean sameAsFront = front != null && url.equals(front.url) && front.view.get() == target;
                if (!sameAsFront) {
                    RECENT.addFirst(new Bind(url, target));
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
            Bind front = RECENT.peekFirst();
            return front != null ? front.url : null;
        }
    }

    /**
     * The most recently bound view that is still attached to a window, has been laid out, and is
     * actually visible on screen right now, or null if nothing so far qualifies. Walks backward
     * through recent binds rather than trusting only the very last one, since the last bind can
     * legitimately be a carousel or Reels page Instagram keeps attached one ahead of the one on
     * screen, so it has something ready the moment a swipe finishes; that page is attached, but
     * sitting off to the side, not on screen. Lets a caller with no view of its own, such as the
     * floating save button, position itself on top of the post actually on screen instead of
     * sitting at a fixed spot, or disappearing whenever the latest bind happens to be that
     * lookahead page.
     */
    public static View mostRecentView() {
        synchronized (BOUND_URLS) {
            for (Bind bind : RECENT) {
                View view = bind.view.get();
                if (isOnScreen(view)) {
                    return view;
                }
            }
        }
        return null;
    }

    /**
     * The most recently bound image that is actually visible on screen right now, resolved with
     * as much metadata as a graph walk from its view can find. Falls back to the single most
     * recent bind, view included if it is still reachable, when nothing currently qualifies as
     * on screen, so a tap that narrowly loses a race with a scroll still saves something sensible
     * rather than nothing at all. Null only when nothing has bound yet.
     */
    public static MediaUrlResolver.Resolved mostRecentResolved() {
        String url = null;
        View view = null;
        synchronized (BOUND_URLS) {
            for (Bind bind : RECENT) {
                View candidate = bind.view.get();
                if (isOnScreen(candidate)) {
                    url = bind.url;
                    view = candidate;
                    break;
                }
            }
            if (url == null) {
                Bind front = RECENT.peekFirst();
                if (front != null) {
                    url = front.url;
                    view = front.view.get();
                }
            }
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

    /**
     * True when {@code view} is attached, has real size, and has at least one visible pixel on
     * screen right now. {@code isAttachedToWindow()} alone is not enough: a carousel slide or
     * Reels page one swipe away is commonly kept attached ahead of time and would pass that check
     * while sitting entirely outside the visible viewport.
     */
    private static boolean isOnScreen(View view) {
        if (view == null || !view.isAttachedToWindow()
                || view.getWidth() == 0 || view.getHeight() == 0) {
            return false;
        }
        Rect visible = new Rect();
        return view.getGlobalVisibleRect(visible);
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
