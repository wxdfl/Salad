package com.wxdfl.salad;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** A small bar chart of profit per month: bars go up for a profit and down for a loss. */
final class ProfitChart extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String[] labels;
    private final String[] amounts;
    private final double[] values;

    /** The last bar is the month being looked at and is drawn stronger than the others. */
    ProfitChart(Context c, String[] labels, double[] values) {
        super(c);
        this.labels = labels;
        this.values = values;
        amounts = new String[values.length];
        StringBuilder spoken = new StringBuilder("Profit by month.");
        for (int i = 0; i < values.length; i++) {
            amounts[i] = U.money(c, Math.round(values[i]));
            spoken.append(' ').append(labels[i]).append(": ").append(amounts[i]).append('.');
        }
        setContentDescription(spoken);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(getDefaultSize(0, widthSpec), U.dp(getContext(), 150));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Context c = getContext();
        float text = U.dp(c, 11);
        float gap = U.dp(c, 4);
        paint.setTextSize(text);
        paint.setTextAlign(Paint.Align.CENTER);

        double top = 0, bottom = 0;
        for (double v : values) {
            top = Math.max(top, v);
            bottom = Math.min(bottom, v);
        }
        if (top == 0 && bottom == 0) top = 1;
        // Room for the amount above (or below) each bar and for the month names underneath.
        float chartTop = text + gap * 2;
        float chartBottom = getHeight() - text - gap * 2 - (bottom < 0 ? text + gap : 0);
        float zero = chartTop + (float) (top / (top - bottom)) * (chartBottom - chartTop);
        float slot = getWidth() / (float) values.length;
        float half = Math.min(slot * 0.3f, U.dp(c, 22));

        for (int i = 0; i < values.length; i++) {
            float x = slot * i + slot / 2;
            float y = zero - (float) (values[i] / (top - bottom)) * (chartBottom - chartTop);
            boolean current = i == values.length - 1;
            paint.setColor(c.getColor(values[i] < 0 ? R.color.bad : R.color.good));
            paint.setAlpha(current ? 255 : 130);
            canvas.drawRect(x - half, Math.min(y, zero), x + half, Math.max(y, zero), paint);
            paint.setColor(c.getColor(R.color.muted));
            paint.setFakeBoldText(current);
            if (values[i] != 0) {
                canvas.drawText(amounts[i], x, values[i] < 0 ? y + text + gap / 2 : y - gap, paint);
            }
            canvas.drawText(labels[i], x, getHeight() - gap, paint);
        }
        paint.setFakeBoldText(false);
        paint.setColor(c.getColor(R.color.muted));
        paint.setAlpha(120);
        canvas.drawRect(0, zero, getWidth(), zero + Math.max(1, U.dp(c, 1) / 2f), paint);
    }
}
