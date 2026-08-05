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
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * An InstaSave owned save button, floating over every screen in the app.
 *
 * <p>Instagram's own overflow menu is no longer reliably where the download option lives. Rather
 * than keep chasing wherever it moved to, this adds an independent trigger that does not depend
 * on Instagram's menu at all: a small round button fixed to a corner of the screen, present on
 * every Activity, that opens a menu of InstaSave's own. Registered once through
 * {@link Application.ActivityLifecycleCallbacks} rather than patched into any specific screen, so
 * it does not depend on which Activity or Fragment happens to be hosting the current view.
 *
 * <p>Tapping the button, then Save in the menu it opens, saves whichever image or video was most
 * recently rendered anywhere in the app: {@link ImageViewRegistry}'s own record of the last bound
 * view, resolved the same way a long press or a menu tap resolves one, so the file is named the
 * same way too. In practice that is the post currently on screen, since Instagram only renders
 * what is near the viewport.
 */
public final class FloatingSaveButton {

    private static final int SIZE_DP = 52;
    private static final int MARGIN_END_DP = 16;
    private static final int MARGIN_BOTTOM_DP = 96;
    private static final int ACCENT_COLOR = Color.parseColor("#0095F6");

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
        ViewGroup content = activity.findViewById(android.R.id.content);
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

    private static void attachButton(Activity activity, ViewGroup content) {
        try {
            if (activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            View button = buildButton(activity);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    dp(activity, SIZE_DP), dp(activity, SIZE_DP));
            params.gravity = Gravity.BOTTOM | Gravity.END;
            params.rightMargin = dp(activity, MARGIN_END_DP);
            params.bottomMargin = dp(activity, MARGIN_BOTTOM_DP);
            content.addView(button, params);
            ATTACHED.put(activity, button);
        } catch (Throwable t) {
            InstaSave.log("floating save button attach failed", t);
        }
    }

    private static View buildButton(final Activity activity) {
        TextView button = new TextView(activity);
        button.setText("⤓"); // a plain downward-arrow-into-tray glyph, no icon resource needed
        button.setTextColor(Color.WHITE);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        button.setGravity(Gravity.CENTER);

        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(ACCENT_COLOR);
        button.setBackground(background);
        button.setElevation(dp(activity, 4));

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
