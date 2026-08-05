package app.mliem.extension.instasave;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

/**
 * Process wide state and helpers shared by every InstaSave extension class.
 *
 * <p>The application context is injected by {@code instaSaveExtensionPatch}, which adds a
 * call to {@link #setApplication(Context)} at the top of Instagram's own
 * {@code InstagramAppShell.onCreate}. That is the earliest point in the process where a
 * Context exists, and it avoids reaching for {@code ActivityThread.currentApplication()},
 * which is subject to hidden API restrictions on newer Android releases.
 */
public final class InstaSave {

    public static final String TAG = "InstaSave";

    /**
     * Label appended to Instagram's own option menus.
     *
     * <p>Deliberately not the word "Download": Instagram ships its own DOWNLOAD row on some
     * surfaces, and the story click handler dispatches on the label text, so a colliding
     * label would make the two indistinguishable.
     */
    public static final String SAVE_LABEL = "Save to device";

    /**
     * Created on first use rather than at class load.
     *
     * <p>This class is loaded the moment any injected call runs, which can be earlier than a
     * Handler should be built, and building one in a static initializer turns any failure into
     * an ExceptionInInitializerError from an unrelated call site. It also keeps the class
     * loadable off device, so the logic below can be unit tested.
     */
    private static Handler mainHandler;

    private static volatile Context applicationContext;

    private static synchronized Handler main() {
        if (mainHandler == null) {
            mainHandler = new Handler(Looper.getMainLooper());
        }
        return mainHandler;
    }

    private InstaSave() {
    }

    /** Injected by the patch at {@code InstagramAppShell.onCreate}. */
    public static void setApplication(Context context) {
        if (context == null) {
            return;
        }
        applicationContext = context.getApplicationContext() != null
                ? context.getApplicationContext()
                : context;
        log("context captured: " + applicationContext.getPackageName());

        if (applicationContext instanceof Application) {
            try {
                FloatingSaveButton.attach((Application) applicationContext);
            } catch (Throwable t) {
                log("floating save button registration failed", t);
            }
        }
    }

    /**
     * Best available context. Prefers a caller supplied one (an Activity gives correctly
     * themed toasts) and falls back to the captured application context.
     */
    public static Context context(Context preferred) {
        return preferred != null ? preferred : applicationContext;
    }

    public static Context context() {
        return applicationContext;
    }

    public static void log(String message) {
        Log.i(TAG, message);
    }

    public static void log(String message, Throwable error) {
        Log.w(TAG, message, error);
    }

    public static void toast(final Context preferred, final String message) {
        final Context context = context(preferred);
        if (context == null) {
            log("toast dropped, no context: " + message);
            return;
        }
        runOnMain(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    log("toast failed", t);
                }
            }
        });
    }

    public static void runOnMain(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
        } else {
            main().post(runnable);
        }
    }
}
