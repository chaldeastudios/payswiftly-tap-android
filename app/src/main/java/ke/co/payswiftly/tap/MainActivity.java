package ke.co.payswiftly.tap;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.nfc.NfcAdapter;
import android.nfc.cardemulation.CardEmulation;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Test sender. Type or paste a PaySwiftly payment link, press Start, then hold
 * the back of this phone against another phone: the other phone opens the link.
 * The UI is built in code so the project has no layout resources to get wrong.
 */
public class MainActivity extends Activity {

    private static final String DEFAULT_LINK = "https://payswiftly.co.ke/r/";
    private static final String TEST_LINK = "https://payswiftly.co.ke/";
    private static final Pattern URL_IN_TEXT = Pattern.compile("https?://\\S+");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, 400);
        }
    };

    private NfcAdapter nfc;
    private TextView capability;
    private Button nfcSettings;
    private EditText linkField;
    private Button toggle;
    private TextView live;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        nfc = NfcAdapter.getDefaultAdapter(this);
        buildUi();
        takeLinkFromIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        takeLinkFromIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        refreshCapability();
        // While this screen is in front, make our service the one that answers
        // other phones, even if another app also registered the NDEF AID.
        if (nfc != null && nfc.isEnabled() && hasHce()) {
            try {
                CardEmulation.getInstance(nfc).setPreferredService(
                        this, new ComponentName(this, TapHceService.class));
            } catch (RuntimeException ignored) {
                // Test build: the status line below still tells the truth.
            }
        }
        handler.post(poll);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(poll);
        // Never keep sending a (15-minute) payment link while this screen is hidden.
        TapState.stop();
        if (nfc != null && nfc.isEnabled() && hasHce()) {
            try {
                CardEmulation.getInstance(nfc).unsetPreferredService(this);
            } catch (RuntimeException ignored) {
            }
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onPause();
    }

    private boolean hasHce() {
        return getPackageManager().hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION);
    }

    /** Null when this phone can send; otherwise the reason it can't. */
    private String blocker() {
        if (nfc == null) return "This phone has no NFC chip, so it cannot send. Use another phone.";
        if (!nfc.isEnabled()) return "NFC is switched off. Turn it on in Settings, then come back.";
        if (!hasHce()) return "This phone's NFC cannot emulate a tag (no host card emulation), so it cannot send.";
        return null;
    }

    private void refreshCapability() {
        String why = blocker();
        if (why == null) {
            capability.setText("NFC is ready on this phone.");
            capability.setTextColor(Color.rgb(22, 120, 60));
            nfcSettings.setVisibility(View.GONE);
        } else {
            capability.setText(why);
            capability.setTextColor(Color.rgb(180, 40, 30));
            nfcSettings.setVisibility(nfc != null && !nfc.isEnabled() ? View.VISIBLE : View.GONE);
            if (TapState.isSending()) TapState.stop();
        }
        toggle.setEnabled(why == null);
    }

    private void onToggle() {
        if (TapState.isSending()) {
            TapState.stop();
            render();
            return;
        }
        String link = linkField.getText().toString().trim();
        try {
            Type4Tag.buildUriRecord(link); // validates; throws with a readable reason
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        TapState.start(link);
        render();
    }

    private void render() {
        boolean sending = TapState.isSending();
        toggle.setText(sending ? "STOP SENDING" : "START SENDING");
        linkField.setEnabled(!sending);
        if (!sending) {
            live.setText("Not sending. Nothing will happen if another phone touches this one.");
            live.setTextColor(Color.DKGRAY);
            return;
        }
        StringBuilder s = new StringBuilder();
        s.append("SENDING. Hold the back of this phone against the back of the other phone.\n\n");
        s.append("Taps completed: ").append(TapState.getTaps()).append('\n');
        long tapAt = TapState.getLastTapAt();
        if (tapAt > 0) {
            s.append("Last completed: ").append(DateFormat.getTimeInstance().format(new Date(tapAt))).append('\n');
        }
        long contactAt = TapState.getLastContactAt();
        if (contactAt > 0) {
            s.append("Last contact: ").append(DateFormat.getTimeInstance().format(new Date(contactAt)))
                    .append(" (").append(TapState.getLastEvent()).append(")");
        } else {
            s.append("No phone has touched this one yet.");
        }
        live.setText(s.toString());
        live.setTextColor(Color.rgb(22, 90, 160));
    }

    private void takeLinkFromIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        CharSequence shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (shared == null) return;
        Matcher m = URL_IN_TEXT.matcher(shared);
        if (m.find()) {
            linkField.setText(m.group());
            Toast.makeText(this, "Link received. Press Start sending.", Toast.LENGTH_SHORT).show();
        }
    }

    private void pasteLink() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = cm == null ? null : cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0 || clip.getItemAt(0).getText() == null) {
            Toast.makeText(this, "Nothing to paste", Toast.LENGTH_SHORT).show();
            return;
        }
        String text = clip.getItemAt(0).getText().toString();
        Matcher m = URL_IN_TEXT.matcher(text);
        linkField.setText(m.find() ? m.group() : text.trim());
    }

    // ---------------------------------------------------------------- UI

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, int sp, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(6), 0, dp(6));
        return t;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(28));

        root.addView(text("PaySwiftly Tap", 24, true, Color.BLACK));
        root.addView(text("Test sender: touch this phone to another phone and the other phone opens a PaySwiftly payment link.",
                14, false, Color.DKGRAY));

        capability = text("", 15, true, Color.DKGRAY);
        root.addView(capability);
        nfcSettings = button("Open NFC settings", v -> startActivity(new Intent(Settings.ACTION_NFC_SETTINGS)));
        root.addView(nfcSettings);

        root.addView(text("Payment link to send", 14, true, Color.BLACK));
        linkField = new EditText(this);
        linkField.setSingleLine(true);
        linkField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        linkField.setText(DEFAULT_LINK);
        linkField.setSelection(DEFAULT_LINK.length());
        root.addView(linkField);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button paste = button("Paste", v -> pasteLink());
        Button test = button("Use test link", v -> linkField.setText(TEST_LINK));
        row.addView(paste, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(test, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row);

        toggle = button("START SENDING", v -> onToggle());
        toggle.setAllCaps(true);
        toggle.setTextSize(16);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dp(16);
        root.addView(toggle, tp);

        live = text("", 15, false, Color.DKGRAY);
        live.setPadding(0, dp(18), 0, dp(6));
        root.addView(live);

        root.addView(text("Tips: keep this screen on and unlocked. Phones read each other where the NFC antenna is, usually "
                + "the middle of the back or near the camera. Remove thick or metal cases. Hold still for a second.",
                13, false, Color.GRAY));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }
}
