package app.mliem.extension.instasave;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
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
 * <p>Styled to sit comfortably next to Instagram's own dark mode: a true black background, a back
 * arrow plus bold title top bar in place of a floating heading, thin dividers instead of boxed
 * cards, and Instagram blue on the switches and the Save button, rather than the stock platform
 * widget colors.
 *
 * <p>The UI is built in code rather than from an XML layout, because a dex-merged extension
 * contributes classes only and cannot ship layout resources. Edge to edge is handled explicitly
 * (the manifest patch's {@code taskAffinity} and {@code launchMode} keep this in its own task,
 * separate from Instagram's; this class keeps its own content out of the status and navigation
 * bars) since the target SDK draws edge to edge by default from Android 15 on.
 */
public final class SettingsActivity extends Activity {

    private static final int BACKGROUND_COLOR = Color.parseColor("#000000");
    private static final int TEXT_COLOR = Color.parseColor("#FAFAFA");
    private static final int MUTED_TEXT_COLOR = Color.parseColor("#8E8E8E");
    private static final int DIVIDER_COLOR = Color.parseColor("#262626");
    private static final int SECONDARY_BUTTON_COLOR = Color.parseColor("#262626");
    private static final int ACCENT_COLOR = Color.parseColor("#0095F6");
    private static final int SWITCH_OFF_TRACK_COLOR = Color.parseColor("#3A3A3C");

    private static final int TOP_BAR_VERTICAL_PADDING_DP = 12;

    private Switch autoUpdateSwitch;
    private Switch highestQualitySwitch;
    private Switch disableDoubleTapLikeSwitch;
    private Switch blockAdsSwitch;

    private LinearLayout topBar;
    private LinearLayout saveContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Settings");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }

        int pad = dp(16);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACKGROUND_COLOR);

        topBar = buildTopBar();
        root.addView(topBar);
        root.addView(hairline());

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, dp(8), pad, 0);

        content.addView(sectionLabel("Updates"));
        Switch[] autoUpdateHolder = new Switch[1];
        content.addView(toggleRow(
                "Check for updates automatically",
                "Looks at GitHub about once a day.",
                Settings.autoUpdate(),
                autoUpdateHolder));
        autoUpdateSwitch = autoUpdateHolder[0];
        content.addView(divider());
        content.addView(secondaryButton("Check for updates now", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Updater.checkNow(SettingsActivity.this);
            }
        }));

        content.addView(sectionLabel("Downloads"));
        Switch[] highestQualityHolder = new Switch[1];
        content.addView(toggleRow(
                "Save highest quality video",
                "Off saves a smaller video to use less data.",
                Settings.highestQuality(),
                highestQualityHolder));
        highestQualitySwitch = highestQualityHolder[0];
        content.addView(caption("Saved files go to Download/InstaSave."));

        content.addView(sectionLabel("Feed"));
        Switch[] doubleTapHolder = new Switch[1];
        content.addView(toggleRow(
                "Disable double tap to like",
                "The heart button still works.",
                Settings.disableDoubleTapLike(),
                doubleTapHolder));
        disableDoubleTapLikeSwitch = doubleTapHolder[0];

        content.addView(sectionLabel("Privacy"));
        Switch[] blockAdsHolder = new Switch[1];
        content.addView(toggleRow(
                "Block ads",
                "Removes sponsored posts from feeds. An ad that is never inserted also never " +
                        "generates the impression tracking that goes with showing one.",
                Settings.blockAds(),
                blockAdsHolder));
        blockAdsSwitch = blockAdsHolder[0];

        content.addView(versionFooter());

        // A spacer so the last row does not sit flush against the fixed Save button below.
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(24)));
        content.addView(spacer);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BACKGROUND_COLOR);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        saveContainer = new LinearLayout(this);
        saveContainer.setOrientation(LinearLayout.VERTICAL);
        saveContainer.setBackgroundColor(BACKGROUND_COLOR);
        Button save = accentButton("Save");
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveSettings();
            }
        });
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        saveParams.setMargins(pad, dp(8), pad, dp(12));
        saveContainer.addView(save, saveParams);
        root.addView(saveContainer);

        applyEdgeToEdgeInsets(root);
        setContentView(root);
    }

    private void saveSettings() {
        Settings.putBoolean(this, Settings.KEY_AUTO_UPDATE, autoUpdateSwitch.isChecked());
        Settings.putBoolean(this, Settings.KEY_HIGHEST_QUALITY, highestQualitySwitch.isChecked());
        Settings.putBoolean(this, Settings.KEY_DISABLE_DOUBLE_TAP_LIKE,
                disableDoubleTapLikeSwitch.isChecked());
        Settings.putBoolean(this, Settings.KEY_BLOCK_ADS, blockAdsSwitch.isChecked());
        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();
    }

    /**
     * Pads the top bar and the Save button area by the system bars' insets, on top of their own
     * designed padding, so edge to edge drawing (the default from Android 15 on this target SDK)
     * never draws either one under the status or navigation bar. Below API 30 the platform still
     * insets automatically on its own, so nothing extra is needed there.
     */
    private void applyEdgeToEdgeInsets(final LinearLayout root) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        final int topBarBasePadding = dp(TOP_BAR_VERTICAL_PADDING_DP);
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                topBar.setPadding(topBar.getPaddingLeft(), topBarBasePadding + bars.top,
                        topBar.getPaddingRight(), topBarBasePadding);
                saveContainer.setPadding(0, 0, 0, bars.bottom);
                return insets;
            }
        });
    }

    // region view builders

    private LinearLayout buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(BACKGROUND_COLOR);
        int vertical = dp(TOP_BAR_VERTICAL_PADDING_DP);
        bar.setPadding(dp(4), vertical, dp(16), vertical);

        TextView back = new TextView(this);
        back.setText("←");
        back.setTextColor(TEXT_COLOR);
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        back.setPadding(dp(12), dp(8), dp(12), dp(8));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        bar.addView(back);

        TextView title = new TextView(this);
        title.setText("Settings");
        title.setTextColor(TEXT_COLOR);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.leftMargin = dp(4);
        bar.addView(title, titleParams);

        return bar;
    }

    private TextView sectionLabel(String text) {
        TextView view = new TextView(this);
        view.setText(text.toUpperCase());
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        view.setTextColor(MUTED_TEXT_COLOR);
        view.setPadding(0, dp(22), 0, dp(6));
        return view;
    }

    private TextView caption(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setTextColor(MUTED_TEXT_COLOR);
        view.setPadding(0, dp(6), 0, dp(4));
        return view;
    }

    private TextView versionFooter() {
        TextView view = new TextView(this);
        view.setText("InstaSave " + BuildConfig.INSTASAVE_VERSION);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        view.setTextColor(MUTED_TEXT_COLOR);
        view.setGravity(Gravity.CENTER_HORIZONTAL);
        view.setPadding(0, dp(32), 0, 0);
        return view;
    }

    private View hairline() {
        View line = new View(this);
        line.setBackgroundColor(DIVIDER_COLOR);
        line.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return line;
    }

    private View divider() {
        View line = new View(this);
        line.setBackgroundColor(DIVIDER_COLOR);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        params.topMargin = dp(6);
        params.bottomMargin = dp(6);
        line.setLayoutParams(params);
        return line;
    }

    /**
     * @param out one element out parameter the created Switch is written to, so the caller can
     *            read its state later at Save time without a listener firing on every flip.
     */
    private View toggleRow(String title, String subtitle, boolean checked, Switch[] out) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));

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
        styleSwitch(toggle);
        out[0] = toggle;

        row.addView(texts);
        row.addView(toggle);
        return row;
    }

    /** Instagram blue when on, matching the accent used everywhere else in the app's own UI. */
    private void styleSwitch(Switch toggle) {
        int[][] states = new int[][] {
                new int[] {android.R.attr.state_checked},
                new int[] {}
        };
        toggle.setThumbTintList(new ColorStateList(states, new int[] {
                Color.WHITE,
                Color.parseColor("#F5F5F5")
        }));
        toggle.setTrackTintList(new ColorStateList(states, new int[] {
                ACCENT_COLOR,
                SWITCH_OFF_TRACK_COLOR
        }));
    }

    private View secondaryButton(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(TEXT_COLOR);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        button.setBackground(pill(SECONDARY_BUTTON_COLOR));
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(4);
        params.bottomMargin = dp(8);
        button.setLayoutParams(params);
        return button;
    }

    private Button accentButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        button.setBackground(pill(ACCENT_COLOR));
        return button;
    }

    private GradientDrawable pill(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(8));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // endregion
}
