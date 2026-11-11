package com.wxdfl.salad;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Small formatting and view-building helpers shared by all screens. */
final class U {
    private U() {
    }

    // ------------------------------------------------------------ formatting

    private static final DecimalFormatSymbols SYMBOLS = new DecimalFormatSymbols(Locale.US);

    /** Up to 3 decimals, no trailing zeros: 1,500 / 0.25 / 12.5 */
    static String num(double v) {
        return new DecimalFormat("#,##0.###", SYMBOLS).format(v);
    }

    /** Plain number for putting into an input box (no thousands separators). */
    static String plain(double v) {
        return new DecimalFormat("0.####", SYMBOLS).format(v);
    }

    static String money(Context c, double v) {
        double cents = Math.round(v * 100) / 100.0;
        String pattern = cents == Math.rint(cents) ? "#,##0" : "#,##0.00";
        String s = new DecimalFormat(pattern, SYMBOLS).format(Math.abs(cents));
        return (cents < 0 ? "-" : "") + currency(c) + s;
    }

    /** Reads a number typed by the user; returns NaN when empty or not a number. */
    static double parse(CharSequence text) {
        try {
            double v = Double.parseDouble(text.toString().trim().replace(',', '.'));
            return Double.isInfinite(v) ? Double.NaN : v;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    static String currency(Context c) {
        String saved = c.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("currency", null);
        return saved != null ? saved : "$";
    }

    static void setCurrency(Context c, String symbol) {
        c.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("currency", symbol).apply();
    }

    // ----------------------------------------------------------------- dates

    private static SimpleDateFormat iso() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    }

    static String today() {
        return iso().format(new Date());
    }

    /** 2026-11-10 -> 10 Nov 2026 */
    static String niceDate(String isoDate) {
        try {
            return new SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(iso().parse(isoDate));
        } catch (ParseException | NullPointerException e) {
            return "";
        }
    }

    /** 2026-11 -> Nov */
    static String shortMonth(String month) {
        try {
            return new SimpleDateFormat("MMM", Locale.getDefault()).format(iso().parse(month + "-01"));
        } catch (ParseException e) {
            return month;
        }
    }

    /** 2026-11 -> November 2026 */
    static String monthLabel(String month) {
        try {
            return new SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(iso().parse(month + "-01"));
        } catch (ParseException e) {
            return month;
        }
    }

    static String shiftMonth(String month, int by) {
        Calendar cal = Calendar.getInstance();
        cal.clear();
        cal.set(Integer.parseInt(month.substring(0, 4)), Integer.parseInt(month.substring(5, 7)) - 1, 1);
        cal.add(Calendar.MONTH, by);
        return iso().format(cal.getTime()).substring(0, 7);
    }

    static void pickDate(Context c, String isoDate, Consumer<String> onPicked) {
        Calendar cal = Calendar.getInstance();
        try {
            cal.setTime(iso().parse(isoDate));
        } catch (ParseException | NullPointerException ignored) {
        }
        new DatePickerDialog(c, (view, y, m, d) ->
                onPicked.accept(String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)),
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    // ----------------------------------------------------------------- views

    static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context c, CharSequence s, int sp, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static TextView muted(Context c, CharSequence s) {
        TextView t = text(c, s, 13, false);
        t.setTextColor(c.getColor(R.color.muted));
        return t;
    }

    static TextView sectionTitle(Context c, CharSequence s) {
        TextView t = text(c, s, 14, true);
        t.setTextColor(c.getColor(R.color.accent));
        t.setPadding(0, dp(c, 20), 0, dp(c, 6));
        return t;
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    }

    static EditText input(Context c, boolean numeric) {
        EditText e = new EditText(c);
        e.setSingleLine();
        e.setInputType(numeric
                ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        return e;
    }

    /** Adds a small label followed by the given field. */
    static void field(LinearLayout parent, String label, View field) {
        TextView l = muted(parent.getContext(), label);
        l.setPadding(dp(parent.getContext(), 4), dp(parent.getContext(), 10), 0, 0);
        parent.addView(l);
        parent.addView(field);
    }

    static View card(Context c, String label, String value, String note, int valueColor) {
        LinearLayout box = column(c);
        int p = dp(c, 12);
        box.setPadding(p, p, p, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(c.getColor(R.color.card));
        bg.setCornerRadius(dp(c, 12));
        box.setBackground(bg);
        box.addView(muted(c, label));
        TextView v = text(c, value, 22, true);
        if (valueColor != 0) v.setTextColor(valueColor);
        box.addView(v);
        box.addView(muted(c, note));
        return box;
    }

    /** Table of ingredients: how much is needed, how much is in stock, how much to buy. */
    static View needsTable(Context c, List<Db.Need> needs) {
        LinearLayout box = column(c);
        TableLayout table = new TableLayout(c);
        table.setColumnStretchable(0, true);
        table.setColumnShrinkable(0, true);
        table.addView(needRow(c, "Ingredient", "Need", "Have", "To buy", true, 0));
        double cost = 0;
        for (Db.Need n : needs) {
            double buy = n.toBuy();
            if (buy > 0) cost += n.buyCost();
            table.addView(needRow(c, n.name, num(n.need) + " " + n.unit, num(n.stock),
                    buy <= 0 ? "✓" : n.buyText(), false,
                    c.getColor(buy > 0 ? R.color.bad : R.color.good)));
        }
        box.addView(table);
        TextView total = muted(c, cost > 0
                ? "Estimated cost to buy: " + money(c, cost)
                : "You have everything in stock.");
        total.setPadding(0, dp(c, 8), 0, 0);
        box.addView(total);
        return box;
    }

    private static TableRow needRow(Context c, String a, String b, String d, String e, boolean head, int lastColor) {
        TableRow r = new TableRow(c);
        String[] cells = {a, b, d, e};
        for (int i = 0; i < cells.length; i++) {
            TextView t = head ? muted(c, cells[i]) : text(c, cells[i], 15, i == 3);
            t.setPadding(i == 0 ? 0 : dp(c, 14), dp(c, 5), 0, dp(c, 5));
            if (i > 0) t.setGravity(Gravity.END);
            if (i == 3 && lastColor != 0) t.setTextColor(lastColor);
            r.addView(t);
        }
        return r;
    }

    // --------------------------------------------------------------- dialogs

    interface Save {
        /** @return true when the input was valid and saved, so the dialog can close */
        boolean run();
    }

    /** A dialog with a form in it. It stays open when {@code save} returns false. */
    static void form(Context c, String title, View body, Save save, Runnable delete) {
        ScrollView scroll = new ScrollView(c);
        int p = dp(c, 20);
        scroll.setPadding(p, dp(c, 4), p, 0);
        scroll.addView(body);
        AlertDialog.Builder b = new AlertDialog.Builder(c).setTitle(title).setView(scroll)
                .setPositiveButton("Save", null).setNegativeButton("Cancel", null);
        if (delete != null) b.setNeutralButton("Delete", null);
        AlertDialog d = b.create();
        if (d.getWindow() != null) {
            d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        d.show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (save.run()) d.dismiss();
        });
        if (delete != null) {
            d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v ->
                    confirm(c, "Delete this?", "Delete", () -> {
                        delete.run();
                        d.dismiss();
                    }));
        }
    }

    static void confirm(Context c, String message, String yes, Runnable action) {
        new AlertDialog.Builder(c).setMessage(message)
                .setPositiveButton(yes, (d, w) -> action.run())
                .setNegativeButton("Cancel", null).show();
    }

    /** Calls {@code changed} every time the text of the field changes. */
    static void onChange(EditText e, Runnable changed) {
        e.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                changed.run();
            }
        });
    }
}
