package com.wxdfl.salad;

import android.content.Context;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The small pop-up forms: ingredient, purchase, expense. */
final class Dialogs {
    private Dialogs() {
    }

    static final String[] CATEGORIES = {
            Db.INGREDIENTS_CATEGORY, "Packaging", "Gas & utilities", "Delivery", "Equipment", "Marketing", "Other"};

    private static void toast(Context c, String message) {
        Toast.makeText(c, message, Toast.LENGTH_SHORT).show();
    }

    /** @param existing null to add a new ingredient */
    static void ingredient(Context c, Db.Ingredient existing, Runnable done) {
        Db db = Db.get(c);
        Db.Ingredient ing = existing != null ? existing : new Db.Ingredient();
        Db.Unit oldBuy = ing.buy();
        LinearLayout form = U.column(c);
        EditText name = U.input(c, false);
        EditText buyUnit = plainText(c, "can, btl, pkt, kg, pcs");
        EditText price = U.input(c, true);
        EditText size = U.input(c, true);
        size.setHint("250");
        EditText small = plainText(c, "g");
        EditText stock = U.input(c, true);
        EditText extra = U.input(c, true);
        EditText less = U.input(c, true);
        name.setText(ing.name);
        if (existing != null) {
            buyUnit.setText(ing.buyName());
            price.setText(U.plain(ing.unitCost() * ing.buySize()));
            stock.setText(U.plain(ing.stock / ing.buySize()));
            if (oldBuy != null) {
                size.setText(U.plain(oldBuy.factor));
                small.setText(ing.unit);
            }
            if (ing.extraQty > 0) extra.setText(U.plain(ing.extraQty));
            if (ing.lessQty > 0) less.setText(U.plain(ing.lessQty));
        }
        U.field(form, "Name (e.g. Lettuce)", name);
        U.field(form, "You buy it by the…", buyUnit);
        U.field(form, "Price of one (" + U.currency(c) + ")", price);
        U.field(form, "In stock right now (in that unit)", stock);

        LinearLayout sizeRow = U.row(c);
        sizeRow.addView(size, U.weight());
        sizeRow.addView(small, new LinearLayout.LayoutParams(U.dp(c, 96), ViewGroup.LayoutParams.WRAP_CONTENT));
        U.field(form, "One of them contains (optional, e.g. 250 g)", sizeRow);
        form.addView(U.muted(c, "Fill this in to measure recipes in a smaller unit like g or ml."
                + " Leave it empty if recipes use whole ones."));

        form.addView(U.sectionTitle(c, "Custom orders"));
        U.field(form, "\"Extra\" adds this much (in the recipe unit)", extra);
        U.field(form, "\"Less\" takes away this much", less);
        form.addView(U.muted(c, "Leave empty to use half of what the recipe has."));

        U.form(c, existing == null ? "New ingredient" : "Edit ingredient", form, () -> {
            String n = name.getText().toString().trim();
            String buy = buyUnit.getText().toString().trim();
            String sm = small.getText().toString().trim();
            String sizeText = size.getText().toString().trim();
            double p = U.parse(price.getText());
            double s = blankIsZero(stock);
            double ex = blankIsZero(extra);
            double le = blankIsZero(less);
            boolean hasSmall = !sm.isEmpty() || !sizeText.isEmpty();
            double f = hasSmall ? U.parse(sizeText) : 1;
            if (n.isEmpty()) return fail(name, "Enter a name");
            if (buy.isEmpty()) return fail(buyUnit, "E.g. can, btl, pkt, kg, pcs");
            if (!(p >= 0)) return fail(price, "Enter the price");
            if (!(s >= 0)) return fail(stock, "Enter a number");
            if (hasSmall && !(f > 0)) return fail(size, "How much is in one " + buy + "?");
            if (hasSmall && sm.isEmpty()) return fail(small, "Unit, e.g. g");
            if (hasSmall && sm.equalsIgnoreCase(buy)) return fail(small, "Use a smaller unit than " + buy);
            if (!(ex >= 0)) return fail(extra, "Enter a number");
            if (!(le >= 0)) return fail(less, "Enter a number");

            // Any further units (from older versions) survive as long as the small unit stays the same.
            String newSmall = hasSmall ? sm : buy;
            List<Db.Unit> units = new ArrayList<>();
            if (hasSmall) units.add(new Db.Unit(buy, f));
            for (Db.Unit u : ing.units) {
                boolean same = existing != null && ing.unit.equalsIgnoreCase(newSmall);
                if (same && u != oldBuy && !u.name.equalsIgnoreCase(buy) && !u.name.equalsIgnoreCase(sm)) {
                    units.add(u);
                }
            }
            ing.name = n;
            ing.unit = newSmall;
            ing.buyUnit = hasSmall ? buy : null;
            ing.units = units;
            ing.packQty = f;
            ing.packPrice = p;
            ing.stock = s * f;
            ing.extraQty = ex;
            ing.lessQty = le;
            db.saveIngredient(ing);
            done.run();
            return true;
        }, null);
    }

    private static EditText plainText(Context c, String hint) {
        EditText e = U.input(c, false);
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        e.setHint(hint);
        return e;
    }

    private static double blankIsZero(EditText e) {
        return e.getText().toString().trim().isEmpty() ? 0 : U.parse(e.getText());
    }

    /** A drop-down with the ingredient's base unit first, then its other units. */
    static Spinner unitSpinner(Context c, Db.Ingredient ing) {
        List<String> names = new ArrayList<>();
        names.add(ing.unit);
        for (Db.Unit u : ing.units) names.add(u.name);
        Spinner spinner = new Spinner(c);
        spinner.setAdapter(new ArrayAdapter<>(c, android.R.layout.simple_spinner_dropdown_item, names));
        return spinner;
    }

    /**
     * Bought more of an ingredient: adds stock and logs the expense.
     *
     * @param suggested amount (in the small unit) to fill in to start with, or 0 for none
     */
    static void purchase(Context c, Db.Ingredient ing, double suggested, Runnable done) {
        LinearLayout form = U.column(c);
        EditText qty = U.input(c, true);
        EditText paid = U.input(c, true);
        Spinner unit = unitSpinner(c, ing);
        int buyIndex = ing.buy() == null ? 0 : ing.units.indexOf(ing.buy()) + 1;
        unit.setSelection(buyIndex);
        if (ing.units.isEmpty()) {
            U.field(form, "How much did you buy? (" + ing.unit + ")", qty);
        } else {
            LinearLayout row = U.row(c);
            row.addView(qty, U.weight());
            row.addView(unit);
            U.field(form, "How much did you buy?", row);
        }
        U.field(form, "Total paid (" + U.currency(c) + ")", paid);
        form.addView(U.muted(c, "\nThis adds to your stock, updates the ingredient's price and"
                + " records the amount under Expenses."));
        if (suggested > 0) {
            // Suggest whole cans/packets when that is how it is bought, otherwise the exact amount.
            qty.setText(U.plain(ing.buy() == null ? suggested : Math.ceil(suggested / ing.buySize() - 1e-9)));
        }
        U.form(c, "Bought " + ing.name, form, () -> {
            double q = U.parse(qty.getText());
            double p = U.parse(paid.getText());
            if (!(q > 0)) return fail(qty, "Enter an amount above 0");
            if (!(p >= 0)) return fail(paid, "Enter what you paid");
            int picked = unit.getSelectedItemPosition();
            Db.get(c).recordPurchase(ing, q, picked > 0 ? ing.units.get(picked - 1) : null, p, U.today());
            toast(c, "Stock and expenses updated");
            done.run();
            return true;
        }, null);
    }

    /** @param existing null to add a new expense */
    static void expense(Context c, Db.Expense existing, Runnable done) {
        Db db = Db.get(c);
        Db.Expense e = existing != null ? existing : new Db.Expense();
        if (existing == null) {
            e.date = U.today();
            e.category = CATEGORIES[1];
        }
        LinearLayout form = U.column(c);
        Button date = new Button(c);
        date.setText(U.niceDate(e.date));
        date.setOnClickListener(v -> U.pickDate(c, e.date, picked -> {
            e.date = picked;
            date.setText(U.niceDate(picked));
        }));
        Spinner category = new Spinner(c);
        category.setAdapter(new ArrayAdapter<>(c, android.R.layout.simple_spinner_dropdown_item, CATEGORIES));
        category.setSelection(Math.max(0, Arrays.asList(CATEGORIES).indexOf(e.category)));
        EditText amount = U.input(c, true);
        EditText note = U.input(c, false);
        if (existing != null) amount.setText(U.plain(e.amount));
        note.setText(e.note);
        U.field(form, "Date", date);
        U.field(form, "Category", category);
        U.field(form, "Amount (" + U.currency(c) + ")", amount);
        U.field(form, "Note (optional)", note);

        U.form(c, existing == null ? "New expense" : "Edit expense", form, () -> {
            double a = U.parse(amount.getText());
            if (!(a > 0)) return fail(amount, "Enter an amount above 0");
            e.amount = a;
            e.category = (String) category.getSelectedItem();
            e.note = note.getText().toString().trim();
            db.saveExpense(e);
            done.run();
            return true;
        }, existing == null ? null : () -> {
            db.deleteExpense(e.id);
            done.run();
        });
    }

    private static boolean fail(EditText field, String message) {
        field.setError(message);
        field.requestFocus();
        return false;
    }
}
