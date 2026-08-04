package app.mliem.extension.instasave;

/**
 * Decides whether to swallow a double tap so it does not like a post.
 *
 * <p>Instagram routes both a double tap and the heart button through the same like method, so the
 * patch cannot simply neutralize that method or the heart button would stop working too. Instead
 * the patched method asks this at entry, and this returns true only when the current call was
 * reached through a double tap gesture, told apart by the gesture callbacks in the call stack.
 * The callback names come from framework interfaces, so they survive obfuscation.
 */
public final class DoubleTapLike {

    private DoubleTapLike() {
    }

    /**
     * @return true when this like was triggered by a double tap and the feature is on, meaning
     *         the caller should return without liking.
     */
    public static boolean shouldBlock() {
        if (!Settings.disableDoubleTapLike()) {
            return false;
        }
        try {
            for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                String method = frame.getMethodName();
                if ("onDoubleTap".equals(method) || "onDoubleTapEvent".equals(method)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            InstaSave.log("double tap check failed", t);
        }
        return false;
    }
}
