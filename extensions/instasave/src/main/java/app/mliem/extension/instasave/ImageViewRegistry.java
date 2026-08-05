package app.mliem.extension.instasave;

import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which CDN URL each Instagram image view most recently bound.
 *
 * <p>Three jobs. It tells {@link PostSaveButton} which view to put a save button on and what that
 * button should save. It powers long press to save, which is how a profile picture, or anything
 * else with no options menu, gets a save action. And it keeps a small ring of recently bound URLs
 * that {@link MediaUrlResolver} falls back to when a click handler turns out to be a synthetic
 * class that captured nothing reachable.
 *
 * <p>Called from Instagram's own image bind funnel, which runs on the main thread for every image
 * in every list, so the common path here has to stay genuinely cheap. Two things that did not
 * matter before now do. The hook this class hangs off was pointed at the wrong method until
 * 0.2.11 and so never actually ran, which meant a per bind {@code getMethod} reflection lookup and
 * a fresh touch listener allocated on every single bind were free in practice. Now that the hook
 * fires for real, on every image of every scroll frame, both are paid for: the URL accessor is
 * resolved once per implementation class and cached, and the touch listener is attached once per
 * view rather than rebuilt each time that view is recycled onto a new post.
 */
public final class ImageViewRegistry {

    private static final int RECENT_CAPACITY = 12;

    private static final Map<View, String> BOUND_URLS = new WeakHashMap<>();
    private static final ArrayDeque<String> RECENT = new ArrayDeque<>();
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    /** Views that already carry the long press gesture, so recycling does not stack duplicates. */
    private static final Map<View, Boolean> LONG_PRESS_ATTACHED = new WeakHashMap<>();

    /**
     * {@code getUrl()} per {@code ImageUrl} implementation class.
     *
     * <p>{@code Class.getMethod} copies the whole public method list on every call, which is far
     * too expensive to repeat for every image of every frame. The set of implementation classes
     * is tiny and lives as long as the process, so caching them costs nothing.
     */
    private static final Map<Class<?>, Method> URL_GETTERS = new ConcurrentHashMap<>();

    /** Stands in for "this class has no getUrl", since ConcurrentHashMap rejects null values. */
    private static final Method NO_GETTER;

    static {
        Method sentinel = null;
        try {
            sentinel = Object.class.getMethod("hashCode");
        } catch (Throwable ignored) {
            // Cannot happen. If it somehow does, the cache degrades to re-resolving each time.
        }
        NO_GETTER = sentinel;
    }

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
                if (!url.equals(RECENT.peekFirst())) {
                    RECENT.addFirst(url);
                    while (RECENT.size() > RECENT_CAPACITY) {
                        RECENT.removeLast();
                    }
                }
            }

            // A View may only be touched from the thread that owns it. Recording the URL above is
            // safe anywhere; everything below builds or mutates views and is not.
            if (Looper.myLooper() != Looper.getMainLooper()) {
                return;
            }

            if (LONG_PRESS_ATTACHED.put(target, Boolean.TRUE) == null) {
                attachLongPress(target);
            }
            PostSaveButton.onImageBound(target);
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
        MediaUrlResolver.Resolved media = resolveFor(view);
        if (media == null) {
            return;
        }
        Downloader.enqueue(view.getContext(), media);
    }

    /**
     * What {@code view} is currently showing, resolved with as much metadata as a graph walk from
     * that view can find, or null when nothing is bound to it.
     *
     * <p>Keyed by the view itself rather than by "whatever bound most recently anywhere", which is
     * what lets a save button state exactly which post it belongs to instead of guessing. The
     * guessing version could point at a carousel page held ready one swipe ahead of the visible
     * one, so the button could sit on one post and save another.
     */
    public static MediaUrlResolver.Resolved resolveFor(View view) {
        String url;
        synchronized (BOUND_URLS) {
            url = BOUND_URLS.get(view);
        }
        return url == null ? null : describe(view, url);
    }

    /**
     * Builds the download request for a known view. The URL is already known, so the graph walk
     * runs only to pick up a username and media id for the file name, and its failure is not fatal.
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
                url, MediaUrlResolver.isVideoUrl(url), username, mediaId,
                Collections.<MediaUrlResolver.Candidate>emptyList());
    }

    /** The URL bound most recently anywhere in the app, or null if nothing has bound yet. */
    public static String mostRecentUrl() {
        synchronized (BOUND_URLS) {
            return RECENT.peekFirst();
        }
    }

    /** The cached {@code getUrl()} for an implementation class, or null when it has none. */
    private static Method urlGetter(Class<?> type) {
        Method cached = URL_GETTERS.get(type);
        if (cached != null) {
            return cached == NO_GETTER ? null : cached;
        }
        Method resolved = null;
        try {
            // ImageUrl is one of the interfaces Instagram leaves unobfuscated. The implementing
            // class is usually package private, so the accessible flag is what makes the public
            // interface method callable on it.
            resolved = type.getMethod("getUrl");
            resolved.setAccessible(true);
        } catch (Throwable ignored) {
            // Not an ImageUrl. Recorded below so the lookup is not repeated for this class.
        }
        if (NO_GETTER != null) {
            URL_GETTERS.put(type, resolved == null ? NO_GETTER : resolved);
        }
        return resolved;
    }

    private static String extractUrl(Object imageUrl) {
        if (imageUrl == null) {
            return null;
        }
        if (imageUrl instanceof String) {
            String value = (String) imageUrl;
            return MediaUrlResolver.isMediaUrl(value) ? value : null;
        }
        Method getter = urlGetter(imageUrl.getClass());
        if (getter == null) {
            return null;
        }
        try {
            Object value = getter.invoke(imageUrl);
            if (value instanceof String && MediaUrlResolver.isMediaUrl((String) value)) {
                return (String) value;
            }
        } catch (Throwable ignored) {
            // A URL that cannot be read is simply not recorded.
        }
        return null;
    }
}
