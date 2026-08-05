package app.mliem.extension.instasave;

import android.app.Activity;
import android.app.Application;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * An InstaSave owned save button, kept glued to whichever post is actually on screen.
 *
 * <p>Instagram's own overflow menu is no longer reliably where the download option lives. Rather
 * than keep chasing wherever it moved to, this adds an independent trigger that does not depend
 * on Instagram's menu at all. It does not, however, inject a real child view into Instagram's own
 * post layout: that would need to guess the right {@code ViewGroup.LayoutParams} type for a parent
 * whose actual class is unknown and varies by surface, and guessing wrong on a stacking layout
 * (a vertical {@code LinearLayout}, say) would not overlay the image at all, it would insert as a
 * whole extra row and visibly break Instagram's own feed. Instead this button lives in InstaSave's
 * own layer, added once to the activity's decor view, and is repositioned every frame to sit on
 * top of whichever image {@link ImageViewRegistry} most recently saw bound, using nothing but that
 * image's own current on screen location. The result looks the same, a button on the post, without
 * ever touching a view Instagram owns.
 *
 * <p>Hidden whenever there is nothing bound to act on, rather than left visible and answering a tap
 * with "nothing to save yet": if {@link ImageViewRegistry#mostRecentView()} is null, so is the
 * button.
 *
 * <p>Registered once through {@link Application.ActivityLifecycleCallbacks} rather than patched
 * into any specific screen, so it does not depend on which Activity or Fragment happens to be
 * hosting the current view.
 */
public final class FloatingSaveButton {

    private static final int SIZE_DP = 40;
    private static final int MARGIN_DP = 8;
    private static final int ACCENT_COLOR = Color.parseColor("#CC0095F6"); // semi-transparent

    /** One button per Activity, so a config change or re-resume never adds a second one. */
    private static final Map<Activity, View> ATTACHED = new WeakHashMap<>();

    private FloatingSaveButton() {
    }

    /** Registered once, from {@link InstaSave#setApplication}. */
    public static void attach(Application application) {
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(Activity activity) {
                addButtonIfNeeded(activity);
            }

            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
            }

            @Override
            public void onActivityStarted(Activity activity) {
            }

            @Override
            public void onActivityPaused(Activity activity) {
            }

            @Override
            public void onActivityStopped(Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
            }

            @Override
            public void onActivityDestroyed(Activity activity) {
                ATTACHED.remove(activity);
            }
        });
    }

    /**
     * Marked the moment a button is promised for this Activity, before the deferred work below
     * actually runs, so a second {@code onActivityResumed} arriving before that work runs (a fast
     * resume/pause cycle during a transition) cannot queue a duplicate.
     */
    private static void addButtonIfNeeded(final Activity activity) {
        if (activity instanceof SettingsActivity || ATTACHED.containsKey(activity)) {
            return;
        }
        final ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) {
            return;
        }
        ATTACHED.put(activity, null);

        // Adding a view here synchronously would force an extra layout pass on the whole content
        // tree in the middle of the activity's own resume, competing with whatever transition or
        // heavy first render it is already doing. Posting defers the work to the next time this
        // thread's message queue is idle, after that work has had a chance to finish, so the
        // button costs nothing at the one moment a jank is most visible and most likely to cascade.
        content.post(new Runnable() {
            @Override
            public void run() {
                attachButton(activity, content);
            }
        });
    }

    private static void attachButton(final Activity activity, final ViewGroup content) {
        try {
            if (activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            final View button = buildButton(activity);
            final int size = dp(activity, SIZE_DP);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size);
            button.setVisibility(View.GONE);
            content.addView(button, params);
            ATTACHED.put(activity, button);
            trackTargetPost(button, content, dp(activity, MARGIN_DP));
        } catch (Throwable t) {
            InstaSave.log("floating save button attach failed", t);
        }
    }

    /**
     * Repositions the button onto {@link ImageViewRegistry#mostRecentView()}'s current on screen
     * bounds before every single frame this window draws, which is what keeps it glued to the
     * right post through scrolling, paging between carousel slides, or a fresh post being bound
     * as the feed loads more, without needing a reference to whatever is actually scrolling.
     * Hidden outright when nothing is bound, rather than left sitting somewhere stale.
     */
    private static void trackTargetPost(final View button, final ViewGroup content, final int margin) {
        button.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            private final int[] targetLocation = new int[2];
            private final int[] contentLocation = new int[2];

            @Override
            public boolean onPreDraw() {
                if (!button.isAttachedToWindow()) {
                    // The window is gone; nothing left to track. Cannot remove this listener from
                    // inside its own callback, but returning true every time costs nothing.
                    return true;
                }
                View target = ImageViewRegistry.mostRecentView();
                if (target == null || target.getWidth() == 0 || target.getHeight() == 0) {
                    button.setVisibility(View.GONE);
                    return true;
                }
                button.setVisibility(View.VISIBLE);

                target.getLocationOnScreen(targetLocation);
                content.getLocationOnScreen(contentLocation);
                int size = button.getWidth();
                float x = (targetLocation[0] - contentLocation[0]) + target.getWidth() - size - margin;
                float y = (targetLocation[1] - contentLocation[1]) + target.getHeight() - size - margin;
                button.setX(x);
                button.setY(y);
                return true;
            }
        });
    }

    private static View buildButton(final Activity activity) {
        TextView button = new TextView(activity);
        button.setText("⤓"); // a plain downward-arrow-into-tray glyph, no icon resource needed
        button.setTextColor(Color.WHITE);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        button.setGravity(Gravity.CENTER);

        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(ACCENT_COLOR);
        button.setBackground(background);
        button.setElevation(dp(activity, 3));

        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu(activity, v);
            }
        });
        return button;
    }

    private static void showMenu(final Activity activity, View anchor) {
        PopupMenu menu = new PopupMenu(activity, anchor);
        menu.getMenu().add(InstaSave.SAVE_LABEL);
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                saveCurrent(activity);
                return true;
            }
        });
        menu.show();
    }

    private static void saveCurrent(Activity activity) {
        MediaUrlResolver.Resolved media = ImageViewRegistry.mostRecentResolved();
        if (media == null) {
            InstaSave.toast(activity, "InstaSave: nothing to save yet");
            return;
        }
        Downloader.enqueue(activity, media);
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
