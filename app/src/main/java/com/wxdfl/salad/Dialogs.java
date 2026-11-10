package com.wxdfl.salad;

import android.content.Context;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Toast;

import java.util.Arrays;

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
        LinearLayout form = U.column(c);
        EditText name = U.input(c, false);
        EditText unit = U.input(c, false);
        unit.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        EditText packQty = U.input(c, true);
        EditText packPrice = U.input(c, true);
        EditText stock = U.input(c, true);
        name.setText(ing.name);
        unit.setText(ing.unit);
        if (existing != null) {
            packQty.setText(U.plain(ing.packQty));
            packPrice.setText(U.plain(ing.packPrice));
            stock.setText(U.plain(ing.stock));
        }
        U.field(form, "Name (e.g. Lettuce)", name);
        U.field(form, "Unit you measure it in (g, ml, pcs…)", unit);
        U.field(form, "You usually buy this much (in that unit)", packQty);
        U.field(form, "…for this price (" + U.currency(c) + ")", packPrice);
        U.field(form, "In stock right now", stock);

        U.form(c, existing == null ? "New ingredient" : "Edit ingredient", form, () -> {
            String n = name.getText().toString().trim();
            String u = unit.getText().toString().trim();
            double q = U.parse(packQty.getText());
            double p = U.parse(packPrice.getText());
            double s = stock.getText().toString().trim().isEmpty() ? 0 : U.parse(stock.getText());
            if (n.isEmpty()) return fail(name, "Enter a name");
            if (u.isEmpty()) return fail(unit, "Enter a unit");
            if (!(q > 0)) return fail(packQty, "Enter an amount above 0");
            if (!(p >= 0)) return fail(packPrice, "Enter the price");
            if (!(s >= 0)) return fail(stock, "Enter a number");
            ing.name = n;
            ing.unit = u;
            ing.packQty = q;
            ing.packPrice = p;
            ing.stock = s;
            db.saveIngredient(ing);
            done.run();
            return true;
        }, null);
    }

    /** Bought more of an ingredient: adds stock and logs the expense. */
    static void purchase(Context c, Db.Ingredient ing, Runnable done) {
        LinearLayout form = U.column(c);
        EditText qty = U.input(c, true);
        EditText paid = U.input(c, true);
        U.field(form, "How much did you buy? (" + ing.unit + ")", qty);
        U.field(form, "Total paid (" + U.currency(c) + ")", paid);
        form.addView(U.muted(c, "\nThis adds to your stock, updates the ingredient's price and"
                + " records the amount under Expenses."));
        U.form(c, "Bought " + ing.name, form, () -> {
            double q = U.parse(qty.getText());
            double p = U.parse(paid.getText());
            if (!(q > 0)) return fail(qty, "Enter an amount above 0");
            if (!(p >= 0)) return fail(paid, "Enter what you paid");
            Db.get(c).recordPurchase(ing, q, p, U.today());
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
