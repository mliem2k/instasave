package app.mliem.extension.instasave;

/**
 * Forces the two server delivered flags that hide Instagram's own download row.
 *
 * <p>Instagram ships a complete DOWNLOAD entry wired to its own save to camera roll flow. Two
 * boolean MobileConfig parameters decide whether it appears: whether the media may be downloaded,
 * and whether the viewer is restricted from downloading. For someone else's post the server
 * answers no to both.
 *
 * <p>This is consulted from the single method every boolean MobileConfig read passes through, so
 * it runs on a hot path and does nothing but compare two longs. Returning null means "no opinion"
 * and the original lookup proceeds untouched, which is what makes hooking that shared method safe:
 * the only ids this can affect are the two named here.
 *
 * <p>Why the parameter ids are hardcoded, and the one thing to check on a new Instagram release:
 * these are stable identities by design, but they are not eternal. Instagram 436 used
 * {@code 0x81035f00020d71} and {@code 0x81035f00060d73}; by 440 the same two flags had moved to
 * the values below. {@code tools/verify_anchors.py} reports them as MISSING when that happens,
 * and the neighbouring ids in category {@code 0x81035F} are where the replacements will be.
 */
public final class MobileConfigOverrides {

    /** "Can this media be downloaded". Forced on. */
    private static final long DOWNLOAD_ELIGIBLE = 0x81035f00020d62L;

    /** "Is the viewer restricted from downloading". Forced off. */
    private static final long DOWNLOAD_RESTRICTED = 0x81035f00030d63L;

    private MobileConfigOverrides() {
    }

    /**
     * @param parameterId the MobileConfig parameter being read
     * @return the value to force, or null to let Instagram answer normally
     */
    public static Boolean evaluate(long parameterId) {
        if (parameterId == DOWNLOAD_ELIGIBLE) {
            return Boolean.TRUE;
        }
        if (parameterId == DOWNLOAD_RESTRICTED) {
            return Boolean.FALSE;
        }
        return null;
    }
}
