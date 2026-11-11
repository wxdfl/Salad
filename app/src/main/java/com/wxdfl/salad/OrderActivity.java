package com.wxdfl.salad;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MenuItem;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Add or edit an order, see what it needs, and mark it as completed (sold). */
public class OrderActivity extends Activity {
    private Db db;
    private Db.Order order;
    private List<Db.OrderItem> items = new ArrayList<>();
    private EditText customer, note;
    private LinearLayout itemsBox, needsBox;
    private TextView totals;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        db = Db.get(this);
        long id = getIntent().getLongExtra("id", 0);
        order = id == 0 ? null : db.order(id);
        boolean isNew = order == null;
        if (isNew) {
            order = new Db.Order();
            order.due = U.today();
        } else {
            items = db.orderItems(order.id);
        }
        boolean open = !order.done;
        setTitle(isNew ? "New order" : open ? "Edit order" : "Completed order");
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);

        LinearLayout col = U.column(this);
        int p = U.dp(this, 16);
        col.setPadding(p, U.dp(this, 4), p, p);

        customer = U.input(this, false);
        customer.setText(order.customer);
        U.field(col, "Customer (optional)", customer);
        note = U.input(this, false);
        note.setText(order.note);
        U.field(col, "Note (optional)", note);
        customer.setEnabled(open);
        note.setEnabled(open);

        if (open) {
            Button due = new Button(this);
            due.setText(U.niceDate(order.due));
            due.setOnClickListener(v -> U.pickDate(this, order.due, picked -> {
                order.due = picked;
                due.setText(U.niceDate(picked));
            }));
            U.field(col, "Needed on", due);
        } else {
            TextView sold = U.text(this, "Sold on " + U.niceDate(order.doneDate), 15, false);
            sold.setPadding(0, U.dp(this, 12), 0, 0);
            col.addView(sold);
        }

        col.addView(U.sectionTitle(this, "Items"));
        itemsBox = U.column(this);
        col.addView(itemsBox);
        if (open) {
            Button add = new Button(this, null, android.R.attr.borderlessButtonStyle);
            add.setText("＋ Add item");
            add.setTextColor(getColor(R.color.accent));
            add.setOnClickListener(v -> pickRecipe());
            col.addView(add);
        }
        totals = U.text(this, "", 15, false);
        totals.setPadding(0, U.dp(this, 8), 0, 0);
        col.addView(totals);

        needsBox = U.column(this);
        col.addView(needsBox);

        if (open) {
            Button save = new Button(this);
            save.setText("Save order");
            save.setOnClickListener(v -> {
                if (save()) finish();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = U.dp(this, 16);
            col.addView(save, lp);
            Button done = new Button(this);
            done.setText("✓ Mark as completed (sold)");
            done.setOnClickListener(v -> {
                if (items.isEmpty()) {
                    toast("Add at least one item first");
                    return;
                }
                U.confirm(this, "Mark this order as completed?\n\nThe sale is recorded for today and the"
                        + " ingredients are taken out of your stock.", "Completed", () -> {
                    if (save()) {
                        db.completeOrder(order.id, U.today());
                        finish();
                    }
                });
            });
            col.addView(done);
        }
        if (!isNew) {
            Button delete = new Button(this, null, android.R.attr.borderlessButtonStyle);
            delete.setText("Delete order");
            delete.setTextColor(getColor(R.color.bad));
            delete.setOnClickListener(v -> U.confirm(this, open ? "Delete this order?"
                    : "Delete this completed order? Its sale will be removed from your totals.", "Delete", () -> {
                        db.deleteOrder(order.id);
                        finish();
                    }));
            col.addView(delete);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(col);
        setContentView(scroll);
        showItems();
    }

    private void showItems() {
        boolean open = !order.done;
        itemsBox.removeAllViews();
        if (items.isEmpty()) itemsBox.addView(U.muted(this, "Nothing yet. Add what the customer ordered."));
        double total = 0, cost = 0;
        TypedValue ripple = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        for (Db.OrderItem it : items) {
            total += it.qty * it.price;
            cost += it.qty * it.cost;

            LinearLayout row = U.row(this);
            row.setPadding(0, U.dp(this, 4), 0, U.dp(this, 4));
            LinearLayout left = U.column(this);
            left.addView(U.text(this, it.name, 16, false));
            if (!it.custom.isEmpty()) {
                TextView custom = U.text(this, it.custom, 13, false);
                custom.setTextColor(getColor(R.color.accent));
                left.addView(custom);
            }
            left.addView(U.muted(this, U.money(this, it.price) + " each"
                    + (open && it.custom.isEmpty() ? " · tap to customise" : "")));
            if (open) {
                left.setBackgroundResource(ripple.resourceId);
                left.setContentDescription("Customise " + it.name);
                left.setOnClickListener(v -> customise(it));
            }
            row.addView(left, U.weight());
            if (open) row.addView(stepButton("−", "Less " + it.name, it, -1));
            TextView qty = U.text(this, (open ? "" : "× ") + it.qty, 17, true);
            qty.setGravity(Gravity.CENTER);
            qty.setMinWidth(U.dp(this, 36));
            row.addView(qty);
            if (open) row.addView(stepButton("＋", "More " + it.name, it, 1));
            TextView line = U.text(this, U.money(this, it.qty * it.price), 15, false);
            line.setGravity(Gravity.END);
            line.setMinWidth(U.dp(this, 72));
            row.addView(line);
            itemsBox.addView(row);
        }
        totals.setText(items.isEmpty() ? "" : "Total: " + U.money(this, total)
                + "\nIngredient cost: " + U.money(this, cost) + " · profit: " + U.money(this, total - cost));

        needsBox.removeAllViews();
        if (open && !items.isEmpty()) {
            needsBox.addView(U.sectionTitle(this, "Ingredients needed for this order"));
            List<Db.Need> needs = db.needsFor(items);
            if (needs.isEmpty()) {
                needsBox.addView(U.muted(this, "These recipes have no ingredients yet. Add them in the Recipes tab."));
            } else {
                needsBox.addView(U.needsTable(this, needs));
            }
        }
    }

    private Button stepButton(String label, String description, Db.OrderItem it, int by) {
        Button b = new Button(this);
        b.setText(label);
        b.setContentDescription(description);
        b.setMinWidth(U.dp(this, 48));
        b.setMinimumWidth(U.dp(this, 48));
        b.setOnClickListener(v -> {
            it.qty += by;
            if (it.qty <= 0) items.remove(it);
            showItems();
        });
        return b;
    }

    private void pickRecipe() {
        List<Db.Recipe> recipes = db.recipes();
        if (recipes.isEmpty()) {
            toast("Add a recipe first, in the Recipes tab");
            return;
        }
        String[] labels = new String[recipes.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = recipes.get(i).name + "  ·  " + U.money(this, recipes.get(i).price);
        }
        new AlertDialog.Builder(this).setTitle("Add item").setItems(labels, (d, which) -> {
            Db.Recipe r = recipes.get(which);
            for (Db.OrderItem it : items) {
                if (it.recipeId == r.id && it.mods.isEmpty()) {
                    it.qty++;
                    showItems();
                    return;
                }
            }
            Db.OrderItem it = new Db.OrderItem();
            it.recipeId = r.id;
            it.name = r.name;
            it.qty = 1;
            it.price = r.price;
            it.cost = r.cost;
            items.add(it);
            showItems();
        }).show();
    }

    /** Changes one order line for this order only: its price, and extra / less / no of an ingredient. */
    private void customise(Db.OrderItem it) {
        Map<Long, Double> normal = db.recipeAmounts(it.recipeId);
        List<Db.Ingredient> all = db.ingredients();
        Map<Long, Choice> choices = new LinkedHashMap<>();

        LinearLayout form = U.column(this);
        EditText price = U.input(this, true);
        price.setText(U.plain(it.price));
        U.field(form, "Price each (" + U.currency(this) + ")", price);
        form.addView(U.sectionTitle(this, "Ingredients"));
        form.addView(U.muted(this, "For this order only. If only some servings are different, add the item"
                + " again as its own line. What extra and less mean is set per ingredient in the Stock tab."));
        LinearLayout rows = U.column(this);
        form.addView(rows);
        for (Db.Ingredient i : all) {
            Db.Mod current = null;
            for (Db.Mod m : it.mods) if (m.ingredientId == i.id) current = m;
            if (normal.containsKey(i.id) || current != null) {
                choiceRow(rows, choices, i, normal.getOrDefault(i.id, 0.0), current);
            }
        }
        Button add = new Button(this, null, android.R.attr.borderlessButtonStyle);
        add.setText("＋ Add another ingredient");
        add.setTextColor(getColor(R.color.accent));
        add.setOnClickListener(v -> {
            List<Db.Ingredient> others = new ArrayList<>();
            for (Db.Ingredient i : all) if (!choices.containsKey(i.id)) others.add(i);
            if (others.isEmpty()) {
                toast("Every ingredient is already listed");
                return;
            }
            String[] labels = new String[others.size()];
            for (int i = 0; i < labels.length; i++) labels[i] = others.get(i).name;
            new AlertDialog.Builder(this).setTitle("Add ingredient").setItems(labels, (d, which) -> {
                Db.Ingredient i = others.get(which);
                if (i.extraQty <= 0) {
                    toast("First set what \"extra\" means for " + i.name + " (Stock tab → Edit)");
                    return;
                }
                choiceRow(rows, choices, i, 0, null).spinner.setSelection(1);
            }).show();
        });
        form.addView(add);

        U.form(this, it.name, form, () -> {
            double p = U.parse(price.getText());
            if (!(p >= 0)) {
                price.setError("Enter a price");
                price.requestFocus();
                return false;
            }
            List<Db.Mod> mods = new ArrayList<>();
            for (Choice ch : choices.values()) {
                Db.Mod m = ch.mods[ch.spinner.getSelectedItemPosition()];
                if (m != null) mods.add(m);
            }
            it.price = p;
            it.mods = mods;
            it.custom = Db.describe(mods);
            it.cost = db.servingCost(it.recipeId, mods);
            showItems();
            return true;
        }, null);
    }

    /** A drop-down of one ingredient, with the change each of its options stands for (null = as usual). */
    private static final class Choice {
        Spinner spinner;
        Db.Mod[] mods;
    }

    /**
     * One "ingredient  [Normal / Extra / Less / No]" row of the customise form.
     *
     * @param usual   how much the recipe has per serving (0 when it is not in the recipe)
     * @param current the change this order line already has for the ingredient, or null
     */
    private Choice choiceRow(LinearLayout rows, Map<Long, Choice> choices, Db.Ingredient i,
                             double usual, Db.Mod current) {
        // "Extra" and "less" come from the ingredient's presets; without one, half the recipe amount.
        double extra = i.extraQty > 0 ? i.extraQty : usual / 2;
        double less = Math.min(usual, i.lessQty > 0 ? i.lessQty : usual / 2);
        // An amount this line already carries is kept as it is unless another option is picked.
        if (current != null && !current.none && current.delta > 0) extra = current.delta;
        if (current != null && !current.none && current.delta < 0) less = -current.delta;

        List<String> labels = new ArrayList<>();
        List<Db.Mod> mods = new ArrayList<>();
        labels.add(usual > 0 ? "Normal" : "Not added");
        mods.add(null);
        if (extra > 0) {
            labels.add("Extra (+" + U.num(extra) + " " + i.unit + ")");
            mods.add(mod(i, extra, false));
        }
        if (usual > 0 && less > 0) {
            labels.add("Less (−" + U.num(less) + " " + i.unit + ")");
            mods.add(mod(i, -less, false));
        }
        if (usual > 0) {
            labels.add("No " + i.name);
            mods.add(mod(i, 0, true));
        }
        Choice choice = new Choice();
        choice.mods = mods.toArray(new Db.Mod[0]);
        choice.spinner = new Spinner(this);
        choice.spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        for (int n = 1; n < choice.mods.length; n++) {
            Db.Mod m = choice.mods[n];
            if (current != null && m.none == current.none && Math.signum(m.delta) == Math.signum(current.delta)) {
                choice.spinner.setSelection(n);
            }
        }

        LinearLayout row = U.row(this);
        LinearLayout left = U.column(this);
        left.addView(U.text(this, i.name, 16, false));
        left.addView(U.muted(this, usual > 0 ? "usually " + U.num(usual) + " " + i.unit : "not in the recipe"));
        row.addView(left, U.weight());
        row.addView(choice.spinner);
        rows.addView(row);
        choices.put(i.id, choice);
        return choice;
    }

    private static Db.Mod mod(Db.Ingredient i, double delta, boolean none) {
        Db.Mod m = new Db.Mod();
        m.ingredientId = i.id;
        m.name = i.name;
        m.unit = i.unit;
        m.delta = delta;
        m.none = none;
        return m;
    }

    private boolean save() {
        if (items.isEmpty()) {
            toast("Add at least one item first");
            return false;
        }
        order.customer = customer.getText().toString().trim();
        order.note = note.getText().toString().trim();
        db.saveOrder(order, items);
        return true;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
