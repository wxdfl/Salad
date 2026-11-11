package com.wxdfl.salad;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Add or edit a recipe: what it sells for and the ingredients in one serving. */
public class RecipeActivity extends Activity {
    private Db db;
    private Db.Recipe recipe;
    private List<Db.Line> lines = new ArrayList<>();
    private EditText name, price;
    private LinearLayout linesBox;
    private TextView summary;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        db = Db.get(this);
        long id = getIntent().getLongExtra("id", 0);
        recipe = id == 0 ? null : db.recipe(id);
        boolean isNew = recipe == null;
        if (isNew) recipe = new Db.Recipe();
        else lines = db.recipeLines(recipe.id);
        setTitle(isNew ? "New recipe" : "Edit recipe");
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);

        LinearLayout col = U.column(this);
        int p = U.dp(this, 16);
        col.setPadding(p, U.dp(this, 4), p, p);

        name = U.input(this, false);
        name.setText(recipe.name);
        U.field(col, "Name (e.g. Chicken Salad)", name);
        price = U.input(this, true);
        if (!isNew) price.setText(U.plain(recipe.price));
        U.field(col, "Selling price (" + U.currency(this) + ")", price);
        U.onChange(price, this::updateSummary);

        col.addView(U.sectionTitle(this, "Ingredients for 1 serving"));
        linesBox = U.column(this);
        col.addView(linesBox);
        Button add = new Button(this, null, android.R.attr.borderlessButtonStyle);
        add.setText("＋ Add ingredient");
        add.setTextColor(getColor(R.color.accent));
        add.setOnClickListener(v -> pickIngredient());
        col.addView(add);

        summary = U.text(this, "", 15, false);
        summary.setPadding(0, U.dp(this, 12), 0, U.dp(this, 12));
        col.addView(summary);

        Button save = new Button(this);
        save.setText("Save recipe");
        save.setOnClickListener(v -> save());
        col.addView(save);
        if (!isNew) {
            Button delete = new Button(this, null, android.R.attr.borderlessButtonStyle);
            delete.setText("Delete recipe");
            delete.setTextColor(getColor(R.color.bad));
            delete.setOnClickListener(v -> U.confirm(this,
                    "Delete " + recipe.name + "? It will also be removed from open orders.", "Delete", () -> {
                        db.deleteRecipe(recipe.id);
                        finish();
                    }));
            col.addView(delete);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(col);
        setContentView(scroll);
        showLines();
    }

    private void showLines() {
        linesBox.removeAllViews();
        if (lines.isEmpty()) {
            linesBox.addView(U.muted(this, "Nothing yet. Add what goes into one serving."));
        }
        for (Db.Line line : lines) {
            LinearLayout row = U.row(this);
            row.addView(U.text(this, line.name, 16, false), U.weight());
            EditText qty = U.input(this, true);
            qty.setGravity(Gravity.END);
            qty.setHint("0");
            if (line.qty > 0) qty.setText(U.plain(line.qty));
            U.onChange(qty, () -> {
                double v = U.parse(qty.getText());
                line.qty = v > 0 ? v : 0;
                updateSummary();
            });
            row.addView(qty, new LinearLayout.LayoutParams(U.dp(this, 84), ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView unit = U.muted(this, line.unit);
            unit.setMinWidth(U.dp(this, 36));
            unit.setPadding(U.dp(this, 4), 0, 0, 0);
            row.addView(unit);
            Button remove = new Button(this, null, android.R.attr.borderlessButtonStyle);
            remove.setText("✕");
            remove.setContentDescription("Remove " + line.name);
            remove.setMinWidth(U.dp(this, 48));
            remove.setMinimumWidth(U.dp(this, 48));
            remove.setOnClickListener(v -> {
                lines.remove(line);
                showLines();
            });
            row.addView(remove);
            linesBox.addView(row);
        }
        updateSummary();
    }

    private void updateSummary() {
        double cost = 0;
        for (Db.Line l : lines) cost += l.qty * l.unitCost;
        double sell = U.parse(price.getText());
        String s = "Ingredient cost per serving: " + U.money(this, cost);
        if (sell > 0) {
            s += "\nProfit per serving: " + U.money(this, sell - cost)
                    + " (" + Math.round((sell - cost) * 100 / sell) + "% margin)";
        }
        summary.setText(s);
    }

    private void pickIngredient() {
        List<Db.Ingredient> choices = new ArrayList<>();
        for (Db.Ingredient i : db.ingredients()) {
            boolean used = false;
            for (Db.Line l : lines) used |= l.ingredientId == i.id;
            if (!used) choices.add(i);
        }
        String[] labels = new String[choices.size() + 1];
        for (int i = 0; i < choices.size(); i++) labels[i] = choices.get(i).name;
        labels[choices.size()] = "＋ New ingredient…";
        new AlertDialog.Builder(this).setTitle("Add ingredient").setItems(labels, (d, which) -> {
            if (which < choices.size()) {
                addLine(choices.get(which));
            } else {
                // Create it on the spot, then add whichever ingredient is new.
                List<Long> before = new ArrayList<>();
                for (Db.Ingredient i : db.ingredients()) before.add(i.id);
                Dialogs.ingredient(this, null, () -> {
                    for (Db.Ingredient i : db.ingredients()) if (!before.contains(i.id)) addLine(i);
                });
            }
        }).show();
    }

    private void addLine(Db.Ingredient i) {
        Db.Line l = new Db.Line();
        l.ingredientId = i.id;
        l.name = i.name;
        l.unit = i.unit;
        l.unitCost = i.packPrice / i.packQty;
        lines.add(l);
        showLines();
    }

    private void save() {
        String n = name.getText().toString().trim();
        double sell = price.getText().toString().trim().isEmpty() ? 0 : U.parse(price.getText());
        if (n.isEmpty()) {
            name.setError("Enter a name");
            name.requestFocus();
            return;
        }
        if (!(sell >= 0)) {
            price.setError("Enter a price");
            price.requestFocus();
            return;
        }
        for (Db.Line l : lines) {
            if (!(l.qty > 0)) {
                Toast.makeText(this, "Enter how much " + l.name + " goes into one serving",
                        Toast.LENGTH_LONG).show();
                return;
            }
        }
        recipe.name = n;
        recipe.price = sell;
        db.saveRecipe(recipe, lines);
        finish();
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
