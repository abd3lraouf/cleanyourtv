package com.gathin.tvhome;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * The prayer panel's sky: the sun's arc from Sunrise to Maghrib over a dashed horizon, with Fajr and Isha just below
 * it, a dot per prayer (passed, next, to come) and the sun, or a crescent at night, at the current minute.
 */
final class PrayerArc extends View {
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint done = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint horizon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;
    int[] mins = new int[0];
    int next = -1;
    int now;

    PrayerArc(Context context) {
        super(context);
        dp = context.getResources().getDisplayMetrics().density;
        setLayerType(LAYER_TYPE_SOFTWARE, null);  // BlurMaskFilter needs it
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2.5f * dp);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setColor(0x59FFFFFF);
        done.set(line);
        done.setColor(0xFFF5C24B);
        done.setStrokeWidth(3f * dp);
        horizon.setStyle(Paint.Style.STROKE);
        horizon.setStrokeWidth(dp);
        horizon.setColor(0x40FFFFFF);
        horizon.setPathEffect(new DashPathEffect(new float[]{4f * dp, 5f * dp}, 0f));
        glow.setMaskFilter(new BlurMaskFilter(9f * dp, BlurMaskFilter.Blur.NORMAL));
    }

    private float base() {
        return getHeight() - 14f * dp;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mins.length != 6 || getWidth() == 0) return;
        canvas.drawLine(x(mins[0] - 25), base(), x(mins[5] + 25), base(), horizon);
        Path all = new Path();
        Path passed = new Path();
        float from = mins[0] - 25, to = mins[5] + 25;
        float at = Math.max(from, Math.min(to, now));
        all.moveTo(x(from), y(from));
        passed.moveTo(x(from), y(from));
        for (float m = from; m <= to; m += 4f) {
            all.lineTo(x(m), y(m));
            if (m <= at) passed.lineTo(x(m), y(m));
        }
        passed.lineTo(x(at), y(at));
        canvas.drawPath(all, line);
        canvas.drawPath(passed, done);
        for (int i = 0; i < 6; i++) {
            float px = x(mins[i]), py = y(mins[i]);
            if (i == next) {
                glow.setColor(0xCC2BB673);
                canvas.drawCircle(px, py, 9f * dp, glow);
                dot.setStyle(Paint.Style.FILL);
                dot.setColor(0xFF2BB673);
                canvas.drawCircle(px, py, 5.5f * dp, dot);
                dot.setColor(0xFFFFFFFF);
                canvas.drawCircle(px, py, 2.2f * dp, dot);
            } else if (mins[i] <= now) {
                dot.setStyle(Paint.Style.FILL);
                dot.setColor(0xFFF5C24B);
                canvas.drawCircle(px, py, 3.6f * dp, dot);
            } else {
                dot.setStyle(Paint.Style.STROKE);
                dot.setStrokeWidth(1.6f * dp);
                dot.setColor(0xB3FFFFFF);
                canvas.drawCircle(px, py, 3.6f * dp, dot);
            }
        }
        float sx = x(at), sy = y(at);
        if (now >= mins[1] && now < mins[4]) {
            glow.setColor(0xB3F5C24B);
            canvas.drawCircle(sx, sy, 13f * dp, glow);
            dot.setStyle(Paint.Style.FILL);
            dot.setColor(0xFFFFE08A);
            canvas.drawCircle(sx, sy, 7f * dp, dot);
            return;
        }
        glow.setColor(0x80CFE0FF);
        canvas.drawCircle(sx, sy, 11f * dp, glow);
        Path moon = new Path();
        Path bite = new Path();
        moon.addCircle(sx, sy, 7f * dp, Path.Direction.CW);
        bite.addCircle(sx + 3.2f * dp, sy - 2.2f * dp, 6f * dp, Path.Direction.CW);
        moon.op(bite, Path.Op.DIFFERENCE);
        dot.setStyle(Paint.Style.FILL);
        dot.setColor(0xFFF2F5FF);
        canvas.drawPath(moon, dot);
    }

    /** Minute of the day to x, mirrored right to left. */
    private float x(float minute) {
        float pad = 14f * dp;
        float width = getWidth() - 2f * pad;
        float start = mins[0] - 25, end = mins[5] + 25;
        float t = Math.max(0f, Math.min(1f, (minute - start) / (end - start)));
        return getLayoutDirection() == LAYOUT_DIRECTION_RTL ? getWidth() - pad - t * width : pad + t * width;
    }

    /** Minute of the day to y: a sine from Sunrise to Maghrib, dipping just under the horizon outside it. */
    private float y(float minute) {
        float rise = mins[1], set = mins[4];
        double h;
        if (minute < rise || minute > set) {
            float off = minute < rise ? minute - rise : minute - set;
            h = Math.min(1.0, Math.abs(off) / 60.0) * -0.18;
        } else {
            h = Math.sin((minute - rise) * Math.PI / (set - rise));
        }
        return (float) (base() - h * (base() - 10f * dp));
    }
}
