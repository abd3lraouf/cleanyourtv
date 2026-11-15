package com.gathin.tvhome;

import android.app.Activity;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * prayer_panel: today's prayer times from the Prayer Times TV app (com.majnioui.tv.prayer), read from its content
 * provider, as a glass card top-right: a sun arc from Fajr to Isha and one chip per time, the next one lit with a
 * countdown. Hidden when the app is missing or has no times for today.
 */
final class PrayerPanel {
    static final Uri URI = Uri.parse("content://com.majnioui.tv.prayer.times/today");

    final Activity act;
    final PrayerArc arc;
    final LinearLayout box;
    final float dp;
    final Typeface face;
    final ContentObserver observer;
    final LinearLayout row;
    final TextView title;
    boolean watching;
    String[] keys = new String[0];
    String[] labels = new String[0];
    int[] mins = new int[0];
    String day = "";
    String place = "";

    PrayerPanel(Activity activity, ViewGroup parent, Typeface typeface, Handler handler) {
        act = activity;
        face = typeface;
        dp = activity.getResources().getDisplayMetrics().density;
        box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(16), px(12), px(16), px(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(22));
        bg.setColor(0x990B0F16);
        bg.setStroke(Math.max(1, px(1)), 0x26FFFFFF);
        box.setBackground(bg);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        lp.setMarginEnd(px(56));
        lp.topMargin = px(112);
        parent.addView(box, 3, lp);
        title = text(13f, 0xB3FFFFFF, false);
        title.setPadding(px(4), 0, px(4), px(8));
        box.addView(title);
        arc = new PrayerArc(activity);
        box.addView(arc, new LinearLayout.LayoutParams(px(544), px(70)));
        row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        box.addView(row);
        box.setVisibility(View.GONE);
        observer = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                load();
                render();
            }
        };
    }

    void load() {
        try (Cursor c = act.getContentResolver().query(URI, null, null, null, null)) {
            if (c == null) {
                keys = new String[0];
                return;
            }
            int n = c.getCount();
            String[] k = new String[n];
            String[] l = new String[n];
            int[] m = new int[n];
            int i = 0;
            while (c.moveToNext()) {
                k[i] = c.getString(c.getColumnIndexOrThrow("key"));
                l[i] = c.getString(c.getColumnIndexOrThrow("label"));
                m[i] = c.getInt(c.getColumnIndexOrThrow("minutes"));
                day = c.getString(c.getColumnIndexOrThrow("day"));
                place = c.getString(c.getColumnIndexOrThrow("place"));
                i++;
            }
            keys = k;
            labels = l;
            mins = m;
            WallpaperMood.prayerMinutes = n == 6 ? m.clone() : null;
        } catch (RuntimeException e) {
            keys = new String[0];
        }
    }

    int px(float v) {
        return Math.round(v * dp);
    }

    void render() {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
        if (keys.length > 0 && !today.equals(day)) load();
        if (keys.length == 0 || !today.equals(day)) {
            box.setVisibility(View.GONE);
            return;
        }
        box.setVisibility(View.VISIBLE);
        title.setText(act.getString(R.string.prayer_panel_title, place));
        Calendar cal = Calendar.getInstance();
        int now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        int next = -1;
        for (int i = 0; i < mins.length; i++) {
            if (!"Sunrise".equals(keys[i]) && mins[i] > now) {
                next = i;
                break;
            }
        }
        boolean tomorrow = next < 0;
        if (tomorrow) next = 0;
        if (mins.length == 6) {
            arc.mins = mins;
            arc.now = now;
            arc.next = tomorrow ? -1 : next;
            arc.invalidate();
        }
        boolean h24 = DateFormat.is24HourFormat(act);
        row.removeAllViews();
        for (int i = 0; i < mins.length; i++) {
            boolean lit = i == next;
            LinearLayout chip = new LinearLayout(act);
            chip.setOrientation(LinearLayout.VERTICAL);
            chip.setGravity(Gravity.CENTER_HORIZONTAL);
            chip.setPadding(0, px(7), 0, px(7));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(px(16));
            if (lit) {
                bg.setColor(0xE62BB673);
            } else {
                bg.setColor(0x40000000);
                bg.setStroke(Math.max(1, px(1)), 0x33FFFFFF);
            }
            chip.setBackground(bg);
            TextView name = text(13f, lit ? 0xFFFFFFFF : 0xCCFFFFFF, lit);
            name.setText(labels[i]);
            chip.addView(name);
            TextView time = text(21f, 0xFFFFFFFF, lit);
            Calendar t = Calendar.getInstance();
            t.set(Calendar.HOUR_OF_DAY, mins[i] / 60);
            t.set(Calendar.MINUTE, mins[i] % 60);
            time.setText(new SimpleDateFormat(h24 ? "HH:mm" : "h:mm", Locale.getDefault()).format(t.getTime()));
            chip.addView(time);
            if (lit) {
                TextView in = text(11f, 0xF2FFFFFF, false);
                if (tomorrow) {
                    in.setText(act.getString(R.string.prayer_panel_tomorrow));
                } else {
                    int left = mins[i] - now;
                    String span;
                    if (left < 60) span = act.getString(R.string.prayer_panel_m, left);
                    else if (left % 60 == 0) span = act.getString(R.string.prayer_panel_h, left / 60);
                    else span = act.getString(R.string.prayer_panel_hm, left / 60, left % 60);
                    in.setText(act.getString(R.string.prayer_panel_in, span));
                }
                chip.addView(in);
            }
            boolean past = !tomorrow && mins[i] <= now;
            chip.setAlpha(lit ? 1f : past ? 0.5f : "Sunrise".equals(keys[i]) ? 0.7f : 0.92f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(84), ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i < mins.length - 1) lp.setMarginEnd(px(8));
            lp.gravity = Gravity.CENTER_VERTICAL;
            row.addView(chip, lp);
        }
    }

    private TextView text(float sp, int color, boolean bold) {
        TextView v = new TextView(act);
        v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        v.setIncludeFontPadding(false);
        v.setTypeface(face, bold ? Typeface.BOLD : Typeface.NORMAL);
        v.setShadowLayer(px(6), 0f, px(1), 0x66000000);
        v.setGravity(Gravity.CENTER_HORIZONTAL);
        return v;
    }
}
