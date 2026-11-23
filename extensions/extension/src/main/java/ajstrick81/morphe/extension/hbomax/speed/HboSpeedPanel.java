package ajstrick81.morphe.extension.hbomax.speed;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;

/**
 * The on-screen speed chip, designed for a TV remote.
 *
 * Issue #258 behaviour:
 *  - Hidden by default. It appears when the viewer uses the remote (or moves a
 *    mouse) during playback and auto-hides after a few idle seconds, like HBO's
 *    own transport overlay.
 *  - It is NOT part of HBO's focus chain (not focusable in normal state), so it
 *    never captures stray D-pad moves. The viewer "grabs" it by HOLDING UP (or
 *    pressing MENU); the controller then routes keys to it:
 *      LEFT / RIGHT = slower / faster, OK = cycle, BACK / DOWN / UP = release.
 *  - Clicking it with a mouse still cycles the speed.
 */
@SuppressLint("ViewConstructor")
final class HboSpeedPanel extends Button {
    /** Keep a normal-speed entry so the user can always get back to 1×. */
    static final float[] SPEEDS = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f};

    private boolean active;

    HboSpeedPanel(Context context) {
        super(context);

        setFocusable(false);
        setFocusableInTouchMode(false);
        setClickable(true);
        setAllCaps(false);
        setTextColor(Color.WHITE);
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        int padH = dp(16);
        int padV = dp(8);
        setPadding(padH, padV, padH, padV);
        setVisibility(View.GONE);
        render();

        setOnClickListener(new ClickCycler(this));
    }

    FrameLayout.LayoutParams buildLayoutParams() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.TOP | Gravity.END;
        int margin = dp(24);
        params.topMargin = margin;
        params.rightMargin = margin;
        return params;
    }

    boolean isActive() {
        return active;
    }

    /** Remote has "grabbed" the chip: highlight it and show the arrows. */
    void setActive(boolean value) {
        active = value;
        render();
    }

    void step(int direction) {
        int index = currentIndex();
        int next = Math.max(0, Math.min(SPEEDS.length - 1, index + direction));
        apply(SPEEDS[next]);
    }

    void cycle() {
        apply(SPEEDS[(currentIndex() + 1) % SPEEDS.length]);
    }

    private int currentIndex() {
        float current = Prefs.speed();
        int best = 2; // 1.0×
        float bestDelta = Float.MAX_VALUE;
        for (int i = 0; i < SPEEDS.length; i++) {
            float delta = Math.abs(SPEEDS[i] - current);
            if (delta < bestDelta) {
                bestDelta = delta;
                best = i;
            }
        }
        return best;
    }

    private void apply(float speed) {
        try {
            Prefs.speed(speed);
            int dispatched = PlayerBridge.setSpeed(speed);
            render();
            Logger.i("Speed chip -> " + speed + "x (dispatched to " + dispatched + " player(s))");
        } catch (Throwable t) {
            Logger.e("Speed change failed", t);
        }
    }

    private void render() {
        String speed = label(Prefs.speed());
        if (active) {
            setText("◀  " + speed + "  ▶");
            setBackgroundColor(Color.argb(0xE6, 0xF0, 0xF0, 0xF0));
            setTextColor(Color.BLACK);
        } else {
            setText(speed + "   hold ▲");
            setBackgroundColor(Color.argb(0xB0, 0x20, 0x20, 0x20));
            setTextColor(Color.WHITE);
        }
    }

    private static String label(float speed) {
        String number = (speed == (long) speed)
                ? String.valueOf((long) speed)
                : String.valueOf(speed);
        return number + "×";
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    /** Named listener (ART verifier — see SpeedWindowCallback). */
    private static final class ClickCycler implements View.OnClickListener {
        private final HboSpeedPanel panel;

        ClickCycler(HboSpeedPanel panel) {
            this.panel = panel;
        }

        @Override
        public void onClick(View v) {
            panel.cycle();
            HboSpeedController.onPointerActivity();
        }
    }
}
