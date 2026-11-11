package com.wxdfl.salad;

import android.content.Context;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
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

        // Other units: each row reads "1 [can] = [250] g".
        form.addView(U.sectionTitle(c, "Other units (optional)"));
        form.addView(U.muted(c, "For things you also count another way, e.g. 1 can = 250 g."
                + " You can then use that unit in recipes and when you buy."));
        LinearLayout unitsBox = U.column(c);
        form.addView(unitsBox);
        List<EditText[]> unitRows = new ArrayList<>();
        List<TextView> baseLabels = new ArrayList<>();
        U.onChange(unit, () -> {
            for (TextView t : baseLabels) t.setText(unit.getText().toString().trim());
        });
        for (Db.Unit u : ing.units) addUnitRow(c, unitsBox, unitRows, baseLabels, unit, u);
        Button addUnit = new Button(c, null, android.R.attr.borderlessButtonStyle);
        addUnit.setText("＋ Add a unit");
        addUnit.setTextColor(c.getColor(R.color.accent));
        addUnit.setOnClickListener(v -> addUnitRow(c, unitsBox, unitRows, baseLabels, unit, null));
        form.addView(addUnit);

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
            List<Db.Unit> units = new ArrayList<>();
            for (EditText[] row : unitRows) {
                String un = row[0].getText().toString().trim();
                String amount = row[1].getText().toString().trim();
                if (un.isEmpty() && amount.isEmpty()) continue;   // an untouched row
                double f = U.parse(amount);
                if (un.isEmpty()) return fail(row[0], "Name this unit, e.g. can");
                if (un.equalsIgnoreCase(u)) return fail(row[0], "Same as the main unit");
                for (Db.Unit other : units) {
                    if (other.name.equalsIgnoreCase(un)) return fail(row[0], "Already listed");
                }
                if (!(f > 0)) return fail(row[1], "How many " + u + " in 1 " + un + "?");
                units.add(new Db.Unit(un, f));
            }
            ing.units = units;
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

    /** Adds a "1 [name] = [amount] base-unit" row to the ingredient form. */
    private static void addUnitRow(Context c, LinearLayout box, List<EditText[]> rows, List<TextView> baseLabels,
                                   EditText baseUnit, Db.Unit existing) {
        LinearLayout row = U.row(c);
        EditText name = U.input(c, false);
        name.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        name.setHint("can");
        EditText amount = U.input(c, true);
        amount.setHint("250");
        TextView base = U.muted(c, baseUnit.getText().toString().trim());
        if (existing != null) {
            name.setText(existing.name);
            amount.setText(U.plain(existing.factor));
        }
        EditText[] fields = {name, amount};
        Button remove = new Button(c, null, android.R.attr.borderlessButtonStyle);
        remove.setText("✕");
        remove.setContentDescription("Remove this unit");
        remove.setMinWidth(U.dp(c, 44));
        remove.setMinimumWidth(U.dp(c, 44));
        remove.setOnClickListener(v -> {
            rows.remove(fields);
            baseLabels.remove(base);
            box.removeView(row);
        });
        row.addView(U.text(c, "1", 16, false));
        row.addView(name, U.weight());
        row.addView(U.text(c, "=", 16, false));
        row.addView(amount, new LinearLayout.LayoutParams(U.dp(c, 76), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(base);
        row.addView(remove);
        rows.add(fields);
        baseLabels.add(base);
        box.addView(row);
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

    /** Bought more of an ingredient: adds stock and logs the expense. */
    static void purchase(Context c, Db.Ingredient ing, Runnable done) {
        LinearLayout form = U.column(c);
        EditText qty = U.input(c, true);
        EditText paid = U.input(c, true);
        Spinner unit = unitSpinner(c, ing);
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
