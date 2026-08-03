package app.mliem.extension.instasave;

import java.lang.ref.WeakReference;

/**
 * Adds a save entry to the story viewer's options sheet and handles taps on it.
 *
 * <p>Instagram builds that sheet as a plain {@code CharSequence[]} and dispatches taps by
 * comparing the tapped label, so appending an entry is an array copy and intercepting it is a
 * string comparison.
 *
 * <p>The owner object is captured when the sheet is built rather than when it is tapped. The
 * builder is an instance method on the controller that owns the current reel, so {@code this}
 * there is a reliable root for the graph walk. The tap handler is frequently a synthetic lambda
 * class whose captured state varies between releases, which is exactly the fragility this
 * avoids.
 */
public final class StoryOptions {

    private static volatile WeakReference<Object> sheetOwner = new WeakReference<>(null);

    private StoryOptions() {
    }

    /**
     * Injected at the top of the options builder, where {@code this} is still guaranteed live.
     *
     * <p>Split out from {@link #addOption(CharSequence[])} deliberately. Dalvik lets a method
     * reuse a parameter register as scratch once it no longer needs the argument, so reading
     * {@code p0} at the return would be reading whatever the method last put there. At entry it
     * is always the receiver.
     *
     * @param owner the controller that owns the reel currently on screen
     */
    public static void rememberOwner(Object owner) {
        if (owner != null) {
            sheetOwner = new WeakReference<>(owner);
        }
    }

    /**
     * Injected at every object return of the options builder.
     *
     * @param original Instagram's own option labels
     * @return the labels with the save entry appended
     */
    public static CharSequence[] addOption(CharSequence[] original) {
        try {
            if (original == null) {
                return null;
            }
            for (CharSequence existing : original) {
                if (existing != null && InstaSave.SAVE_LABEL.contentEquals(existing)) {
                    return original;
                }
            }

            CharSequence[] extended = new CharSequence[original.length + 1];
            System.arraycopy(original, 0, extended, 0, original.length);
            extended[original.length] = InstaSave.SAVE_LABEL;
            return extended;
        } catch (Throwable t) {
            InstaSave.log("story option injection failed", t);
            return original;
        }
    }

    /**
     * Injected at the top of the options tap handler.
     *
     * @return true when this was our entry, meaning the caller must return without running
     *         Instagram's own dispatch, which has no branch for a label it never added
     */
    public static boolean onOptionClick(CharSequence tapped) {
        try {
            if (tapped == null || !InstaSave.SAVE_LABEL.contentEquals(tapped)) {
                return false;
            }

            Object owner = sheetOwner.get();
            if (owner == null) {
                InstaSave.toast(null, "InstaSave: the story is no longer on screen");
                return true;
            }

            MediaUrlResolver.Resolved media = MediaUrlResolver.resolve(owner);
            if (media == null) {
                InstaSave.toast(null, "InstaSave: could not find this story's media");
                return true;
            }

            Downloader.enqueue(null, media);
            return true;
        } catch (Throwable t) {
            InstaSave.log("story option click failed", t);
            // Returning true still consumes the event: Instagram cannot handle our label
            // anyway, and letting it through would look like a dead menu row.
            return true;
        }
    }
}
