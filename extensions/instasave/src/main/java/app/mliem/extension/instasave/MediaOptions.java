package app.mliem.extension.instasave;

import java.util.ArrayList;
import java.util.List;

/**
 * Feed post and reel overflow menu support.
 *
 * <p>Instagram already models a DOWNLOAD row as a constant of the unobfuscated enum
 * {@code com.instagram.feed.media.mediaoption.MediaOption$Option}. It just filters that
 * constant out of the allowlist the menu is built from. Adding it back is therefore an
 * append to a list rather than building a menu row from scratch, and Instagram renders it
 * with its own styling.
 *
 * <p>Preferred over this is {@code unlockNativeDownloadPatch}, which flips the two eligibility
 * gates so Instagram's own download row and its own save flow both light up with no extension
 * code involved at all. This class is the fallback for builds where the native flow does not
 * fire, and for that reason it also owns the tap handler.
 */
public final class MediaOptions {

    private static final String OPTION_ENUM =
            "com.instagram.feed.media.mediaoption.MediaOption$Option";
    private static final String DOWNLOAD_CONSTANT = "DOWNLOAD";

    /** Resolved once; null means this build does not expose the constant. */
    private static volatile Object downloadOption;
    private static volatile boolean downloadOptionResolved;

    private MediaOptions() {
    }

    /**
     * Injected at the return of the method that produces the allowed option list.
     *
     * @return the same list with DOWNLOAD appended, or the original if it is already present
     *         or the constant could not be resolved
     */
    public static List<Object> addDownloadOption(List<Object> allowed) {
        try {
            if (allowed == null) {
                return null;
            }
            Object option = downloadOption();
            if (option == null || allowed.contains(option)) {
                return allowed;
            }
            // Instagram's list may be immutable, so copy rather than mutate in place.
            List<Object> extended = new ArrayList<>(allowed.size() + 1);
            extended.addAll(allowed);
            extended.add(option);
            return extended;
        } catch (Throwable t) {
            InstaSave.log("media option injection failed", t);
            return allowed;
        }
    }

    /**
     * As {@link #addDownloadOption(List)}, for the reel builder.
     *
     * <p>That builder declares {@code ArrayList} as its return type, and dex verification rejects
     * writing a {@code List} back into a register the method promises to return an
     * {@code ArrayList} from, so the two cannot share one entry point.
     */
    public static ArrayList<Object> addDownloadOptionToArrayList(ArrayList<Object> allowed) {
        try {
            if (allowed == null) {
                return null;
            }
            Object option = downloadOption();
            if (option == null || allowed.contains(option)) {
                return allowed;
            }
            ArrayList<Object> extended = new ArrayList<>(allowed.size() + 1);
            extended.addAll(allowed);
            extended.add(option);
            return extended;
        } catch (Throwable t) {
            InstaSave.log("reel option injection failed", t);
            return allowed;
        }
    }

    /**
     * Injected at the top of the option tap handler when it is static, so there is no receiver
     * to walk from. The resolver falls back to the most recently bound image URL.
     */
    public static boolean onOptionClick(Object tapped) {
        return onOptionClick(null, tapped);
    }

    /**
     * Injected at the top of the option tap handler.
     *
     * <p>Argument order is deliberate: the injected call uses {@code invoke-static/range}, which
     * requires a contiguous ascending register range, and on an instance handler that is
     * {@code this} followed by the option, so handler must come first.
     *
     * @param handler {@code this} at the call site, used as the root of the graph walk
     * @param tapped  the tapped {@code MediaOption$Option} constant
     * @return true when the tap was DOWNLOAD and we handled it, meaning the caller must return
     */
    public static boolean onOptionClick(Object handler, Object tapped) {
        try {
            Object option = downloadOption();
            boolean isDownload = option != null
                    ? option == tapped
                    : tapped != null && DOWNLOAD_CONSTANT.equals(tapped.toString());
            if (!isDownload) {
                return false;
            }

            MediaUrlResolver.Resolved media = MediaUrlResolver.resolve(handler);
            if (media == null) {
                InstaSave.toast(null, "InstaSave: could not find this post's media");
                return true;
            }

            Downloader.enqueue(null, media);
            return true;
        } catch (Throwable t) {
            InstaSave.log("media option click failed", t);
            return true;
        }
    }

    private static Object downloadOption() {
        if (downloadOptionResolved) {
            return downloadOption;
        }
        synchronized (MediaOptions.class) {
            if (downloadOptionResolved) {
                return downloadOption;
            }
            try {
                Class<?> type = Class.forName(OPTION_ENUM);
                Object[] constants = (Object[]) type.getMethod("values").invoke(null);
                if (constants != null) {
                    for (Object constant : constants) {
                        if (constant != null && DOWNLOAD_CONSTANT.equals(constant.toString())) {
                            downloadOption = constant;
                            break;
                        }
                    }
                }
                if (downloadOption == null) {
                    InstaSave.log("no DOWNLOAD constant on " + OPTION_ENUM + " in this build");
                }
            } catch (Throwable t) {
                InstaSave.log("could not resolve " + OPTION_ENUM, t);
            }
            downloadOptionResolved = true;
            return downloadOption;
        }
    }
}
