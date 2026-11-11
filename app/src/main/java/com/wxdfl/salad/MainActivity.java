package com.wxdfl.salad;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** The main screen: five tabs along the bottom. */
public class MainActivity extends Activity {
    private static final String[] TABS = {"Home", "Orders", "Recipes", "Stock", "Expenses"};
    private static final String[] ICONS = {"🏠", "🧾", "🥗", "🧺", "💵"};
    private static final String[] TITLES = {"Salad", "Orders", "Recipes", "Ingredients & stock", "Expenses"};
    private static final int REQ_BACKUP = 1, REQ_RESTORE = 2;

    private final TextView[] tabViews = new TextView[TABS.length];
    private FrameLayout content;
    private int tab;
    private String month = U.today().substring(0, 7);

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null) {
            tab = saved.getInt("tab");
            month = saved.getString("month", month);
        }
        LinearLayout root = U.column(this);
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout bar = U.row(this);
        bar.setBackgroundColor(getColor(R.color.bar));
        TypedValue ripple = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        for (int i = 0; i < TABS.length; i++) {
            TextView t = U.text(this, ICONS[i] + "\n" + TABS[i], 12, false);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, U.dp(this, 8), 0, U.dp(this, 8));
            t.setBackgroundResource(ripple.resourceId);
            int index = i;
            t.setOnClickListener(v -> show(index));
            tabViews[i] = t;
            bar.addView(t, U.weight());
        }
        root.addView(bar);
        setContentView(root);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", tab);
        out.putString("month", month);
    }

    @Override
    protected void onResume() {
        super.onResume();
        show(tab);
    }

    private Db db() {
        return Db.get(this);
    }

    private void refresh() {
        show(tab);
    }

    void show(int index) {
        tab = index;
        setTitle(TITLES[index]);
        for (int i = 0; i < tabViews.length; i++) {
            boolean on = i == index;
            tabViews[i].setTextColor(getColor(on ? R.color.accent : R.color.muted));
            tabViews[i].setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
        content.removeAllViews();
        switch (index) {
            case 0: content.addView(home()); break;
            case 1: content.addView(orders()); break;
            case 2: content.addView(recipes()); break;
            case 3: content.addView(stock()); break;
            default: content.addView(expenses()); break;
        }
    }

    // ------------------------------------------------------------------ home

    private View home() {
        LinearLayout col = U.column(this);
        int p = U.dp(this, 16);
        col.setPadding(p, U.dp(this, 8), p, p);
        col.addView(monthBar());

        Db.Summary s = db().summary(month);
        double profit = s.sales - s.expenses;
        String margin = s.sales > 0
                ? Math.round((s.sales - s.ingredientCost) * 100 / s.sales) + "% margin on food" : "of items sold";
        col.addView(cardRow(
                U.card(this, "Sales", U.money(this, s.sales),
                        s.ordersDone + (s.ordersDone == 1 ? " order" : " orders") + " completed", 0),
                U.card(this, "Expenses", U.money(this, s.expenses), "everything you spent", 0)));
        col.addView(cardRow(
                U.card(this, "Profit", U.money(this, profit), "sales − expenses",
                        getColor(profit < 0 ? R.color.bad : R.color.good)),
                U.card(this, "Ingredient cost", U.money(this, s.ingredientCost), margin, 0)));

        int pending = db().pendingOrderCount();
        col.addView(U.sectionTitle(this, "To prepare for " + pending
                + (pending == 1 ? " open order" : " open orders")));
        List<Db.Need> needs = db().pendingNeeds();
        if (needs.isEmpty()) {
            col.addView(U.muted(this, pending == 0
                    ? "No open orders. Add one in the Orders tab and the ingredients you need will show up here."
                    : "The recipes in your open orders have no ingredients yet. Add them in the Recipes tab."));
        } else {
            col.addView(U.needsTable(this, needs));
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(col);
        return scroll;
    }

    private View cardRow(View left, View right) {
        LinearLayout row = new LinearLayout(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.setMargins(0, U.dp(this, 8), U.dp(this, 4), 0);
        row.addView(left, lp);
        lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.setMargins(U.dp(this, 4), U.dp(this, 8), 0, 0);
        row.addView(right, lp);
        return row;
    }

    private View monthBar() {
        LinearLayout bar = U.row(this);
        Button prev = flatButton("◀");
        Button next = flatButton("▶");
        TextView label = U.text(this, U.monthLabel(month), 17, true);
        label.setGravity(Gravity.CENTER);
        prev.setOnClickListener(v -> {
            month = U.shiftMonth(month, -1);
            refresh();
        });
        next.setOnClickListener(v -> {
            month = U.shiftMonth(month, 1);
            refresh();
        });
        bar.addView(prev);
        bar.addView(label, U.weight());
        bar.addView(next);
        return bar;
    }

    private Button flatButton(String text) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(text);
        b.setMinWidth(U.dp(this, 56));
        b.setMinimumWidth(U.dp(this, 56));
        return b;
    }

    // ----------------------------------------------------------------- lists

    private static final class Item {
        final String title, sub, value;
        final Runnable onTap;   // null for a section header

        Item(String title, String sub, String value, Runnable onTap) {
            this.title = title;
            this.sub = sub;
            this.value = value;
            this.onTap = onTap;
        }
    }

    /** A list of rows with an "add" button underneath. */
    private View listScreen(View top, List<Item> items, String empty, String addLabel, Runnable onAdd) {
        LinearLayout col = U.column(this);
        if (top != null) col.addView(top);
        if (items.isEmpty()) {
            TextView t = U.muted(this, empty);
            t.setTextSize(15);
            t.setGravity(Gravity.CENTER);
            int p = U.dp(this, 32);
            t.setPadding(p, p, p, p);
            col.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        } else {
            ListView list = new ListView(this);
            list.setAdapter(new BaseAdapter() {
                @Override
                public int getCount() {
                    return items.size();
                }

                @Override
                public Object getItem(int position) {
                    return items.get(position);
                }

                @Override
                public long getItemId(int position) {
                    return position;
                }

                @Override
                public boolean isEnabled(int position) {
                    return items.get(position).onTap != null;
                }

                @Override
                public boolean areAllItemsEnabled() {
                    return false;
                }

                @Override
                public View getView(int position, View convert, ViewGroup parent) {
                    return rowView(items.get(position));
                }
            });
            list.setOnItemClickListener((parent, view, position, id) -> items.get(position).onTap.run());
            col.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        }
        Button add = new Button(this);
        add.setText(addLabel);
        add.setOnClickListener(v -> onAdd.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int m = U.dp(this, 8);
        lp.setMargins(m, 0, m, m / 2);
        col.addView(add, lp);
        return col;
    }

    private View rowView(Item item) {
        int p = U.dp(this, 16);
        if (item.onTap == null) {
            TextView header = U.sectionTitle(this, item.title);
            header.setPadding(p, U.dp(this, 14), p, U.dp(this, 4));
            return header;
        }
        LinearLayout row = U.row(this);
        row.setPadding(p, U.dp(this, 10), p, U.dp(this, 10));
        LinearLayout left = U.column(this);
        left.addView(U.text(this, item.title, 16, false));
        if (!item.sub.isEmpty()) left.addView(U.muted(this, item.sub));
        row.addView(left, U.weight());
        TextView value = U.text(this, item.value, 16, true);
        value.setPadding(U.dp(this, 12), 0, 0, 0);
        row.addView(value);
        return row;
    }

    // ---------------------------------------------------------------- orders

    private View orders() {
        List<Item> items = new ArrayList<>();
        boolean doneHeader = false;
        for (Db.Order o : db().orders()) {
            if (items.isEmpty() && !o.done) items.add(new Item("To make", "", "", null));
            if (o.done && !doneHeader) {
                items.add(new Item("Completed (sold)", "", "", null));
                doneHeader = true;
            }
            String title = o.customer == null || o.customer.isEmpty() ? "Order #" + o.id : o.customer;
            String when = o.done ? "Sold " + U.niceDate(o.doneDate) : "Due " + U.niceDate(o.due);
            String sub = when + (o.summary.isEmpty() ? "" : " · " + o.summary);
            items.add(new Item(title, sub, U.money(this, o.total), () -> openOrder(o.id)));
        }
        return listScreen(null, items,
                "No orders yet.\n\nAdd an order to see which ingredients to buy and prepare.",
                "＋ New order", () -> openOrder(0));
    }

    private void openOrder(long id) {
        if (id == 0 && db().recipes().isEmpty()) {
            toast("Add a recipe first, in the Recipes tab");
            return;
        }
        startActivity(new Intent(this, OrderActivity.class).putExtra("id", id));
    }

    // --------------------------------------------------------------- recipes

    private View recipes() {
        List<Item> items = new ArrayList<>();
        for (Db.Recipe r : db().recipes()) {
            double profit = r.price - r.cost;
            String sub = "Costs " + U.money(this, r.cost) + " · profit " + U.money(this, profit)
                    + (r.price > 0 ? " (" + Math.round(profit * 100 / r.price) + "%)" : "");
            items.add(new Item(r.name, sub, U.money(this, r.price), () -> openRecipe(r.id)));
        }
        return listScreen(null, items,
                "No recipes yet.\n\nA recipe is something you sell, with the ingredients that go into one serving.",
                "＋ New recipe", () -> openRecipe(0));
    }

    private void openRecipe(long id) {
        startActivity(new Intent(this, RecipeActivity.class).putExtra("id", id));
    }

    // ----------------------------------------------------------------- stock

    private View stock() {
        List<Item> items = new ArrayList<>();
        for (Db.Ingredient i : db().ingredients()) {
            StringBuilder sub = new StringBuilder(
                    U.money(this, i.packPrice) + " per " + U.num(i.packQty) + " " + i.unit);
            for (Db.Unit u : i.units) {
                sub.append(" · 1 ").append(u.name).append(" = ").append(U.num(u.factor)).append(" ").append(i.unit);
            }
            items.add(new Item(i.name, sub.toString(), U.num(i.stock) + " " + i.unit, () -> ingredientMenu(i)));
        }
        return listScreen(null, items,
                "No ingredients yet.\n\nAdd the raw materials you buy, with their price and how much you have.",
                "＋ New ingredient", () -> Dialogs.ingredient(this, null, this::refresh));
    }

    private void ingredientMenu(Db.Ingredient i) {
        String[] actions = {"I bought more (adds stock + expense)", "Edit", "Delete"};
        new AlertDialog.Builder(this).setTitle(i.name).setItems(actions, (d, which) -> {
            if (which == 0) Dialogs.purchase(this, i, this::refresh);
            else if (which == 1) Dialogs.ingredient(this, i, this::refresh);
            else U.confirm(this, "Delete " + i.name + "? It will also be removed from your recipes.",
                        "Delete", () -> {
                            db().deleteIngredient(i.id);
                            refresh();
                        });
        }).show();
    }

    // -------------------------------------------------------------- expenses

    private View expenses() {
        List<Item> items = new ArrayList<>();
        double total = 0;
        for (Db.Expense e : db().expenses(month)) {
            total += e.amount;
            String sub = U.niceDate(e.date) + (e.note.isEmpty() ? "" : " · " + e.note);
            items.add(new Item(e.category, sub, U.money(this, e.amount),
                    () -> Dialogs.expense(this, e, this::refresh)));
        }
        LinearLayout top = U.column(this);
        int p = U.dp(this, 16);
        top.setPadding(p, U.dp(this, 8), p, 0);
        top.addView(monthBar());
        TextView sum = U.muted(this, "Total spent: " + U.money(this, total));
        sum.setGravity(Gravity.CENTER);
        top.addView(sum);
        return listScreen(top, items, "No expenses in " + U.monthLabel(month) + ".",
                "＋ New expense", () -> Dialogs.expense(this, null, this::refresh));
    }

    // ------------------------------------------------------------------ menu

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "Currency symbol");
        menu.add(0, 2, 0, "Back up data");
        menu.add(0, 3, 0, "Restore from backup");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            LinearLayout form = U.column(this);
            EditText symbol = U.input(this, false);
            symbol.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
            symbol.setText(U.currency(this));
            U.field(form, "Shown before every amount (e.g. $, RM, Rp, ₱)", symbol);
            U.form(this, "Currency symbol", form, () -> {
                U.setCurrency(this, symbol.getText().toString().trim());
                refresh();
                return true;
            }, null);
        } else if (item.getItemId() == 2) {
            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream")
                    .putExtra(Intent.EXTRA_TITLE, "salad-backup-" + U.today() + ".db"), REQ_BACKUP);
        } else if (item.getItemId() == 3) {
            U.confirm(this, "Restoring replaces everything in the app with the contents of the backup file.",
                    "Choose file", () -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_RESTORE));
        } else {
            return super.onOptionsItemSelected(item);
        }
        return true;
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        try {
            if (request == REQ_BACKUP) {
                backupTo(data.getData());
                toast("Backup saved");
            } else if (request == REQ_RESTORE) {
                restoreFrom(data.getData());
                toast("Backup restored");
            }
        } catch (IOException | RuntimeException e) {
            toast(request == REQ_BACKUP ? "Could not save the backup" : "That file is not a Salad backup");
        }
        refresh();
    }

    private void backupTo(Uri target) throws IOException {
        db().getWritableDatabase();   // make sure the file exists
        Db.closeAll();                // flush everything into the single .db file
        try (InputStream in = new FileInputStream(getDatabasePath(Db.FILE));
             OutputStream out = getContentResolver().openOutputStream(target)) {
            copy(in, out);
        }
    }

    private void restoreFrom(Uri source) throws IOException {
        File incoming = new File(getCacheDir(), "restore.db");
        try (InputStream in = getContentResolver().openInputStream(source);
             OutputStream out = new FileOutputStream(incoming)) {
            copy(in, out);
        }
        // Refuse anything that is not one of our databases before touching the real one.
        try (SQLiteDatabase check = SQLiteDatabase.openDatabase(
                incoming.getPath(), null, SQLiteDatabase.OPEN_READONLY);
             Cursor c = check.rawQuery(
                     "SELECT 1 FROM ingredient, recipe, recipe_item, orders, order_item, expense LIMIT 1", null)) {
            c.moveToFirst();
        }
        db().getWritableDatabase();   // make sure the database folder exists
        Db.closeAll();
        File live = getDatabasePath(Db.FILE);
        new File(live.getPath() + "-wal").delete();
        new File(live.getPath() + "-shm").delete();
        new File(live.getPath() + "-journal").delete();
        try (InputStream in = new FileInputStream(incoming); OutputStream out = new FileOutputStream(live)) {
            copy(in, out);
        }
        incoming.delete();
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        if (in == null || out == null) throw new IOException("no stream");
        byte[] buffer = new byte[8192];
        for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
