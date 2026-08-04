package app.mliem.extension.instasave;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import app.mliem.extension.BuildConfig;

/**
 * The InstaSave settings screen.
 *
 * <p>A patched Instagram gives no clean place to hang a settings row inside its own UI without
 * fingerprinting obfuscated screens, so this is a standalone Activity declared in the manifest
 * with its own launcher icon. It is the entire settings surface: the updater controls, the
 * download quality choice, the double tap gesture, and ad blocking, all persisted through
 * {@link Settings}.
 *
 * <p>Every toggle here changes only the on screen switch until the Save button is tapped; nothing
 * is written to {@link Settings} before that. This is deliberate: writing on every flip gave no
 * feedback that anything actually happened, which is exactly what read as "settings aren't
 * applied". A single Save action, with a toast confirming it, gives one unambiguous moment where
 * the user knows the change took. "Check for updates now" is an action, not a preference, and
 * still runs immediately; it has nothing to save.
 *
 * <p>The UI is built in code rather than from an XML layout, because a dex-merged extension
 * contributes classes only and cannot ship layout resources. It stays intentionally plain, and
 * follows the dark system theme declared for this activity in the manifest patch.
 */
public final class SettingsActivity extends Activity {

    private static final int TEXT_COLOR = Color.parseColor("#F5F5F5");
    private static final int MUTED_TEXT_COLOR = Color.parseColor("#B0B0B0");
    private static final int BACKGROUND_COLOR = Color.parseColor("#121212");

    private Switch autoUpdateSwitch;
    private Switch highestQualitySwitch;
    private Switch disableDoubleTapLikeSwitch;
    private Switch blockAdsSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("InstaSave");

        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, dp(4));
        root.setBackgroundColor(BACKGROUND_COLOR);

        root.addView(header("InstaSave"));
        root.addView(caption("Version " + BuildConfig.INSTASAVE_VERSION));

        root.addView(sectionLabel("Updates"));
        Switch[] autoUpdateHolder = new Switch[1];
        root.addView(toggle(
                "Check for updates automatically",
                "Looks at GitHub about once a day.",
                Settings.autoUpdate(),
                autoUpdateHolder));
        autoUpdateSwitch = autoUpdateHolder[0];
        root.addView(button("Check for updates now", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Updater.checkNow(SettingsActivity.this);
            }
        }));

        root.addView(sectionLabel("Downloads"));
        Switch[] highestQualityHolder = new Switch[1];
        root.addView(toggle(
                "Save highest quality video",
                "Off saves a smaller video to use less data.",
                Settings.highestQuality(),
                highestQualityHolder));
        highestQualitySwitch = highestQualityHolder[0];
        root.addView(caption("Saved files go to Download/InstaSave."));

        root.addView(sectionLabel("Feed"));
        Switch[] doubleTapHolder = new Switch[1];
        root.addView(toggle(
                "Disable double tap to like",
                "The heart button still works.",
                Settings.disableDoubleTapLike(),
                doubleTapHolder));
        disableDoubleTapLikeSwitch = doubleTapHolder[0];

        root.addView(sectionLabel("Privacy"));
        Switch[] blockAdsHolder = new Switch[1];
        root.addView(toggle(
                "Block ads",
                "Removes sponsored posts from feeds. An ad that is never inserted also never " +
                        "generates the impression tracking that goes with showing one.",
                Settings.blockAds(),
                blockAdsHolder));
        blockAdsSwitch = blockAdsHolder[0];

        // A spacer so the last row does not sit flush against the fixed Save button below.
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(24)));
        root.addView(spacer);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BACKGROUND_COLOR);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button save = new Button(this);
        save.setText("Save");
        save.setAllCaps(false);
        save.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveSettings();
            }
        });

        // A plain vertical layout with the scroll view given all remaining space (weight 1) and
        // Save sized to its own content keeps Save fixed at the bottom, visible without scrolling,
        // regardless of how long the settings list grows.
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundColor(BACKGROUND_COLOR);
        container.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        saveParams.setMargins(pad, dp(8), pad, pad);
        container.addView(save, saveParams);

        setContentView(container);
    }

    private void saveSettings() {
        Settings.putBoolean(this, Settings.KEY_AUTO_UPDATE, autoUpdateSwitch.isChecked());
        Settings.putBoolean(this, Settings.KEY_HIGHEST_QUALITY, highestQualitySwitch.isChecked());
        Settings.putBoolean(this, Settings.KEY_DISABLE_DOUBLE_TAP_LIKE,
                disableDoubleTapLikeSwitch.isChecked());
        Settings.putBoolean(this, Settings.KEY_BLOCK_ADS, blockAdsSwitch.isChecked());
        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();
    }

    // region view builders

    private TextView header(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(TEXT_COLOR);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        view.setPadding(0, 0, 0, dp(2));
        return view;
    }

    private TextView sectionLabel(String text) {
        TextView view = new TextView(this);
        view.setText(text.toUpperCase());
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setTextColor(MUTED_TEXT_COLOR);
        view.setPadding(0, dp(22), 0, dp(6));
        return view;
    }

    private TextView caption(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setTextColor(MUTED_TEXT_COLOR);
        view.setPadding(0, dp(4), 0, dp(4));
        return view;
    }

    /**
     * @param out one element out parameter the created Switch is written to, so the caller can
     *            read its state later at Save time without a listener firing on every flip.
     */
    private View toggle(String title, String subtitle, boolean checked, Switch[] out) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams grow = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        texts.setLayoutParams(grow);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(TEXT_COLOR);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        texts.addView(titleView);

        if (subtitle != null) {
            TextView subtitleView = new TextView(this);
            subtitleView.setText(subtitle);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            subtitleView.setTextColor(MUTED_TEXT_COLOR);
            texts.addView(subtitleView);
        }

        Switch toggle = new Switch(this);
        toggle.setChecked(checked);
        out[0] = toggle;

        row.addView(texts);
        row.addView(toggle);
        return row;
    }

    private View button(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(6);
        button.setLayoutParams(params);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // endregion
}
