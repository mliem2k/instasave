package app.mliem.extension.instasave;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

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
 * <p>The UI is built in code rather than from an XML layout, because a dex-merged extension
 * contributes classes only and cannot ship layout resources. It stays intentionally plain.
 */
public final class SettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("InstaSave");

        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        root.addView(header("InstaSave"));
        root.addView(caption("Version " + BuildConfig.INSTASAVE_VERSION));

        root.addView(sectionLabel("Updates"));
        root.addView(toggle(
                "Check for updates automatically",
                "Looks at GitHub about once a day.",
                Settings.getBoolean(this, Settings.KEY_AUTO_UPDATE, true),
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton button, boolean checked) {
                        Settings.putBoolean(SettingsActivity.this, Settings.KEY_AUTO_UPDATE, checked);
                    }
                }));
        root.addView(button("Check for updates now", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Updater.checkNow(SettingsActivity.this);
            }
        }));

        root.addView(sectionLabel("Downloads"));
        root.addView(toggle(
                "Save highest quality video",
                "Off saves a smaller video to use less data.",
                Settings.getBoolean(this, Settings.KEY_HIGHEST_QUALITY, true),
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton button, boolean checked) {
                        Settings.putBoolean(SettingsActivity.this, Settings.KEY_HIGHEST_QUALITY, checked);
                    }
                }));
        root.addView(caption("Saved files go to Download/InstaSave."));

        root.addView(sectionLabel("Feed"));
        root.addView(toggle(
                "Disable double tap to like",
                "The heart button still works.",
                Settings.getBoolean(this, Settings.KEY_DISABLE_DOUBLE_TAP_LIKE, false),
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton button, boolean checked) {
                        Settings.putBoolean(SettingsActivity.this,
                                Settings.KEY_DISABLE_DOUBLE_TAP_LIKE, checked);
                    }
                }));

        root.addView(sectionLabel("Privacy"));
        root.addView(toggle(
                "Block ads",
                "Removes sponsored posts from feeds. An ad that is never inserted also never " +
                        "generates the impression tracking that goes with showing one.",
                Settings.getBoolean(this, Settings.KEY_BLOCK_ADS, false),
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton button, boolean checked) {
                        Settings.putBoolean(SettingsActivity.this, Settings.KEY_BLOCK_ADS, checked);
                    }
                }));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
    }

    // region view builders

    private TextView header(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        view.setPadding(0, 0, 0, dp(2));
        return view;
    }

    private TextView sectionLabel(String text) {
        TextView view = new TextView(this);
        view.setText(text.toUpperCase());
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setTextColor(Color.GRAY);
        view.setPadding(0, dp(22), 0, dp(6));
        return view;
    }

    private TextView caption(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setTextColor(Color.GRAY);
        view.setPadding(0, dp(4), 0, dp(4));
        return view;
    }

    private View toggle(String title, String subtitle, boolean checked,
                        CompoundButton.OnCheckedChangeListener listener) {
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
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        texts.addView(titleView);

        if (subtitle != null) {
            TextView subtitleView = new TextView(this);
            subtitleView.setText(subtitle);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            subtitleView.setTextColor(Color.GRAY);
            texts.addView(subtitleView);
        }

        Switch toggle = new Switch(this);
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener(listener);

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
