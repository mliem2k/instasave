package app.mliem.extension.instasave;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.AdapterView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * A save button that sits on the post itself, as a real child of the post's own container.
 *
 * <p>This replaces a button pinned to a corner of the screen. That one was rejected twice as bad
 * design, and rightly: a control that acts on a specific post should be on that post, not floating
 * somewhere generic and guessing which post it means. It never worked either, but for an unrelated
 * reason, see {@code ImageUrlBindFingerprint}.
 *
 * <p>Being a real child rather than an overlay tracked frame by frame is what makes it behave
 * correctly for free. It scrolls in perfect sync with the post because it moves with its parent,
 * it is clipped by the same bounds as the rest of the post rather than floating over the top bar
 * on the way out of view, and it costs nothing per frame.
 *
 * <p>The risk that argument has to answer is layout damage, which is why the host is chosen rather
 * than assumed. Adding a child to a stacking container (a vertical {@code LinearLayout}) would not
 * overlay the image at all, it would insert a whole extra row and visibly break the feed. Adding
 * one to a recycler or a pager would hand our view to something that owns its children's positions
 * and lifecycles. So {@link #isSafeHost} accepts only containers that position children freely and
 * independently, and the walk up from the image stops rather than settling for anything else. An
 * overlay capable ancestor is close to guaranteed in practice, because Instagram already draws its
 * own things over post images (carousel dots, product pills, the audio attribution), so the
 * container holding the image must already support exactly this.
 *
 * <p>No bytecode patch of its own. It is driven entirely by {@link ImageViewRegistry}, which
 * already sees every image the app binds.
 */
public final class PostSaveButton {

    private static final int SIZE_DP = 34;
    private static final int MARGIN_DP = 10;

    /**
     * Below this, in either dimension, the image is furniture rather than a post: an avatar, a
     * story tray ring, a profile grid tile, an icon. Attaching to those would put a save button on
     * everything, and worse, could grow a small wrap content container to fit a button larger than
     * the image it holds.
     */
    private static final int MIN_POST_DP = 150;

    /** How far up to look for a container that can host an overlay before giving up. */
    private static final int MAX_HOST_DEPTH = 8;

    private static final int BACKGROUND_COLOR = Color.parseColor("#B3000000"); // 70% black
    private static final int GLYPH_COLOR = Color.WHITE;

    /** Images already carrying a layout listener, so recycling never stacks duplicates. */
    private static final Map<View, Boolean> WATCHED = new WeakHashMap<>();

    /**
     * The button, and the image it currently belongs to.
     *
     * <p>A dedicated type rather than a map from host to button on purpose. The button is a child
     * of its host, so it holds a strong reference to it; keeping that pair in a {@code WeakHashMap}
     * keyed by the host would be the textbook value referencing key mistake, and the entry could
     * never be collected. Finding our own button among the host's children instead needs no
     * bookkeeping at all and cannot leak: when the host goes, everything goes with it.
     */
    private static final class SaveButton extends TextView {
        WeakReference<View> target;

        SaveButton(Context context) {
            super(context);
        }
    }

    private PostSaveButton() {
    }

    /** Called from {@link ImageViewRegistry} on the main thread, for every image bound. */
    static void onImageBound(View image) {
        try {
            watch(image);
            evaluate(image);
        } catch (Throwable t) {
            InstaSave.log("post save button bind failed", t);
        }
    }

    /**
     * An image is almost never laid out at the moment its URL is bound, so its size is unknown and
     * the post test below cannot be answered yet. Rather than guess, this re-runs the whole
     * decision on every layout of that image, which is also what keeps the button correct when the
     * view is later recycled onto a different post.
     */
    private static void watch(final View image) {
        if (WATCHED.put(image, Boolean.TRUE) != null) {
            return;
        }
        image.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View view, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                try {
                    evaluate(view);
                } catch (Throwable t) {
                    InstaSave.log("post save button layout failed", t);
                }
            }
        });
    }

    private static void evaluate(final View image) {
        if (!isPostSized(image)) {
            return;
        }
        final ViewGroup host = findHost(image);
        if (host == null) {
            return;
        }

        SaveButton button = existing(host);
        if (button == null) {
            // An image is rarely measured at bind time, so in practice the button is first created
            // from the image's layout listener, which runs inside a layout pass. Adding a child
            // there would call requestLayout on a host that is already laying out, which Android
            // answers by deferring to another traversal at best and warns about at worst. Posting
            // moves the one structural change out of the pass; repositioning below is only a
            // translation, which invalidates without ever requesting layout, so it stays inline.
            host.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        attach(host, image);
                    } catch (Throwable t) {
                        InstaSave.log("post save button attach failed", t);
                    }
                }
            });
            return;
        }
        button.target = new WeakReference<>(image);
        position(host, button, image);
    }

    private static void attach(ViewGroup host, View image) {
        SaveButton button = existing(host);
        if (button == null) {
            if (image.getParent() == null) {
                // Recycled away between the layout pass and this post. The view that replaces it
                // will bind and lay out on its own, so there is nothing to attach to here.
                return;
            }
            button = build(host.getContext());
            int size = dp(host.getContext(), SIZE_DP);
            // A plain ViewGroup.LayoutParams is the one kind every container can convert into its
            // own through generateLayoutParams, so this needs no per container special casing.
            host.addView(button, new ViewGroup.LayoutParams(size, size));
        }
        button.target = new WeakReference<>(image);
        position(host, button, image);
    }

    /** Our own button among the host's children, or null. Added last, so search from the end. */
    private static SaveButton existing(ViewGroup host) {
        for (int i = host.getChildCount() - 1; i >= 0; i--) {
            View child = host.getChildAt(i);
            if (child instanceof SaveButton) {
                return (SaveButton) child;
            }
        }
        return null;
    }

    /**
     * Places the button over the bottom left of the image, in the host's own coordinate space.
     *
     * <p>Left, not right: Instagram puts its own audio mute toggle in the bottom right of a post,
     * so a button there would sit on top of it.
     *
     * <p>Positioned by translation rather than by layout params so it never participates in the
     * host's measure pass and so cannot change where anything else lands. Both views sit inside
     * the same host, so this offset does not change while scrolling, which is precisely why being
     * a child removes the need to track anything per frame.
     */
    private static void position(ViewGroup host, SaveButton button, View image) {
        int[] imageLocation = new int[2];
        int[] hostLocation = new int[2];
        image.getLocationInWindow(imageLocation);
        host.getLocationInWindow(hostLocation);

        Context context = host.getContext();
        int size = dp(context, SIZE_DP);
        int margin = dp(context, MARGIN_DP);

        button.setX((imageLocation[0] - hostLocation[0]) + margin);
        button.setY((imageLocation[1] - hostLocation[1]) + image.getHeight() - size - margin);
        button.setVisibility(View.VISIBLE);
    }

    private static boolean isPostSized(View image) {
        int minimum = dp(image.getContext(), MIN_POST_DP);
        return image.getWidth() >= minimum && image.getHeight() >= minimum;
    }

    private static ViewGroup findHost(View image) {
        ViewParent parent = image.getParent();
        int depth = 0;
        while (parent instanceof ViewGroup && depth++ < MAX_HOST_DEPTH) {
            ViewGroup group = (ViewGroup) parent;
            if (group.getId() == android.R.id.content) {
                // The activity's own root. Anything this far up is no longer "the post", and a
                // button there would be the floating button this exists to replace.
                return null;
            }
            if (isSafeHost(group)) {
                return group;
            }
            parent = group.getParent();
        }
        return null;
    }

    /**
     * True only for containers that position children freely and independently of one another.
     *
     * <p>Order matters here. {@code ScrollView} and {@code HorizontalScrollView} both extend
     * {@code FrameLayout}, so the scrolling rejection has to be tested before the FrameLayout
     * acceptance or a scroller would be accepted as a plain frame.
     */
    private static boolean isSafeHost(ViewGroup group) {
        if (group instanceof AdapterView) {
            return false;
        }
        String name = group.getClass().getName();
        // Anything that scrolls, pages or recycles owns where its children are and when they exist.
        if (name.contains("ScrollView")
                || name.contains("RecyclerView")
                || name.contains("ViewPager")
                || name.contains("ListView")) {
            return false;
        }
        // A stacking container would insert the button as a whole extra row instead of over the
        // image. This covers RadioGroup and the table layouts, which are LinearLayout subclasses.
        if (group instanceof LinearLayout) {
            return false;
        }
        if (group instanceof FrameLayout || group instanceof RelativeLayout) {
            return true;
        }
        // Matched by name because these are AndroidX types the extension does not compile against,
        // and Instagram's own subclasses of them are obfuscated anyway.
        return name.endsWith("ConstraintLayout")
                || name.endsWith("CoordinatorLayout")
                || name.endsWith("MotionLayout");
    }

    private static SaveButton build(Context context) {
        final SaveButton button = new SaveButton(context);
        button.setText("⤓"); // downward arrow to bar, no icon resource needed
        button.setTextColor(GLYPH_COLOR);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        button.setGravity(Gravity.CENTER);

        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(BACKGROUND_COLOR);
        button.setBackground(background);
        // Raises it above the image without reordering children, which bringToFront would do at
        // the cost of a layout pass on the host.
        button.setElevation(dp(context, 4));

        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View clicked) {
                save(button);
            }
        });
        return button;
    }

    private static void save(SaveButton button) {
        View target = button.target != null ? button.target.get() : null;
        MediaUrlResolver.Resolved media =
                target == null ? null : ImageViewRegistry.resolveFor(target);
        if (media == null) {
            InstaSave.toast(button.getContext(), "InstaSave: nothing to save here");
            return;
        }
        Downloader.enqueue(button.getContext(), media);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
