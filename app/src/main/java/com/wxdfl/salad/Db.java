package com.wxdfl.salad;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** All data lives in one small SQLite file. Dates are stored as yyyy-MM-dd text. */
final class Db extends SQLiteOpenHelper {
    static final String FILE = "salad.db";
    static final String INGREDIENTS_CATEGORY = "Ingredients";
    /** SQL for the cost of one unit of ingredient {@code i}. */
    private static final String UNIT_COST = "(i.pack_price / i.pack_qty)";

    private static Db instance;

    static synchronized Db get(Context c) {
        if (instance == null) instance = new Db(c.getApplicationContext());
        return instance;
    }

    /** Closes the database so the file can be copied or replaced. */
    static synchronized void closeAll() {
        if (instance != null) instance.close();
        instance = null;
    }

    private Db(Context c) {
        super(c, FILE, null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase d) {
        d.execSQL("CREATE TABLE ingredient(id INTEGER PRIMARY KEY, name TEXT NOT NULL, unit TEXT NOT NULL,"
                + " pack_qty REAL NOT NULL, pack_price REAL NOT NULL, stock REAL NOT NULL DEFAULT 0)");
        d.execSQL("CREATE TABLE recipe(id INTEGER PRIMARY KEY, name TEXT NOT NULL, price REAL NOT NULL)");
        d.execSQL("CREATE TABLE recipe_item(recipe_id INTEGER NOT NULL, ingredient_id INTEGER NOT NULL,"
                + " qty REAL NOT NULL)");
        d.execSQL("CREATE TABLE orders(id INTEGER PRIMARY KEY, customer TEXT, note TEXT, due TEXT,"
                + " status INTEGER NOT NULL DEFAULT 0, done_date TEXT)");
        d.execSQL("CREATE TABLE order_item(order_id INTEGER NOT NULL, recipe_id INTEGER NOT NULL,"
                + " name TEXT NOT NULL, qty INTEGER NOT NULL, price REAL NOT NULL, cost REAL NOT NULL DEFAULT 0)");
        d.execSQL("CREATE TABLE expense(id INTEGER PRIMARY KEY, date TEXT NOT NULL, category TEXT NOT NULL,"
                + " amount REAL NOT NULL, note TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase d, int oldVersion, int newVersion) {
    }

    // ---------------------------------------------------------------- models

    static final class Ingredient {
        long id;
        String name = "", unit = "g";
        double packQty = 1, packPrice, stock;
    }

    static final class Recipe {
        long id;
        String name = "";
        double price, cost;
    }

    /** One ingredient line of a recipe: how much goes into a single serving. */
    static final class Line {
        long ingredientId;
        String name, unit;
        double qty, unitCost;
    }

    static final class Order {
        long id;
        String customer = "", note = "", due, doneDate, summary;
        boolean done;
        double total;
    }

    static final class OrderItem {
        long recipeId;
        String name;
        int qty;
        double price, cost;
    }

    static final class Expense {
        long id;
        String date, category, note = "";
        double amount;
    }

    /** Total amount of one ingredient needed, compared with what is in stock. */
    static final class Need {
        String name, unit;
        double need, stock, unitCost;

        double toBuy() {
            return Math.max(0, need - stock);
        }
    }

    static final class Summary {
        double sales, ingredientCost, expenses;
        int ordersDone;
    }

    // ----------------------------------------------------------- ingredients

    List<Ingredient> ingredients() {
        List<Ingredient> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, name, unit, pack_qty, pack_price, stock FROM ingredient"
                        + " ORDER BY name COLLATE NOCASE", null)) {
            while (c.moveToNext()) {
                Ingredient i = new Ingredient();
                i.id = c.getLong(0);
                i.name = c.getString(1);
                i.unit = c.getString(2);
                i.packQty = c.getDouble(3);
                i.packPrice = c.getDouble(4);
                i.stock = c.getDouble(5);
                out.add(i);
            }
        }
        return out;
    }

    void saveIngredient(Ingredient i) {
        ContentValues v = new ContentValues();
        v.put("name", i.name);
        v.put("unit", i.unit);
        v.put("pack_qty", i.packQty);
        v.put("pack_price", i.packPrice);
        v.put("stock", i.stock);
        if (i.id == 0) i.id = getWritableDatabase().insert("ingredient", null, v);
        else getWritableDatabase().update("ingredient", v, "id=?", arg(i.id));
    }

    void deleteIngredient(long id) {
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            d.delete("recipe_item", "ingredient_id=?", arg(id));
            d.delete("ingredient", "id=?", arg(id));
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
    }

    /** Adds to stock, remembers the latest price, and logs the money spent as an expense. */
    void recordPurchase(Ingredient i, double qty, double paid, String date) {
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            d.execSQL("UPDATE ingredient SET stock = stock + ?, pack_qty = ?, pack_price = ? WHERE id = ?",
                    new Object[]{qty, qty, paid, i.id});
            Expense e = new Expense();
            e.date = date;
            e.category = INGREDIENTS_CATEGORY;
            e.amount = paid;
            e.note = i.name + " " + U.num(qty) + " " + i.unit;
            saveExpense(e);
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
    }

    // --------------------------------------------------------------- recipes

    List<Recipe> recipes() {
        List<Recipe> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT r.id, r.name, r.price, (SELECT SUM(ri.qty * " + UNIT_COST + ") FROM recipe_item ri"
                        + " JOIN ingredient i ON i.id = ri.ingredient_id WHERE ri.recipe_id = r.id)"
                        + " FROM recipe r ORDER BY r.name COLLATE NOCASE", null)) {
            while (c.moveToNext()) {
                Recipe r = new Recipe();
                r.id = c.getLong(0);
                r.name = c.getString(1);
                r.price = c.getDouble(2);
                r.cost = c.getDouble(3);
                out.add(r);
            }
        }
        return out;
    }

    Recipe recipe(long id) {
        for (Recipe r : recipes()) if (r.id == id) return r;
        return null;
    }

    List<Line> recipeLines(long recipeId) {
        List<Line> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT i.id, i.name, i.unit, ri.qty, " + UNIT_COST + " FROM recipe_item ri"
                        + " JOIN ingredient i ON i.id = ri.ingredient_id WHERE ri.recipe_id = ?"
                        + " ORDER BY ri.rowid", arg(recipeId))) {
            while (c.moveToNext()) {
                Line l = new Line();
                l.ingredientId = c.getLong(0);
                l.name = c.getString(1);
                l.unit = c.getString(2);
                l.qty = c.getDouble(3);
                l.unitCost = c.getDouble(4);
                out.add(l);
            }
        }
        return out;
    }

    void saveRecipe(Recipe r, List<Line> lines) {
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            ContentValues v = new ContentValues();
            v.put("name", r.name);
            v.put("price", r.price);
            if (r.id == 0) r.id = d.insert("recipe", null, v);
            else d.update("recipe", v, "id=?", arg(r.id));
            d.delete("recipe_item", "recipe_id=?", arg(r.id));
            for (Line l : lines) {
                ContentValues lv = new ContentValues();
                lv.put("recipe_id", r.id);
                lv.put("ingredient_id", l.ingredientId);
                lv.put("qty", l.qty);
                d.insert("recipe_item", null, lv);
            }
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
    }

    /** Also removes the recipe from orders that are not completed yet; past sales are kept. */
    void deleteRecipe(long id) {
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            d.delete("recipe_item", "recipe_id=?", arg(id));
            d.delete("order_item",
                    "recipe_id=? AND order_id IN (SELECT id FROM orders WHERE status = 0)", arg(id));
            d.delete("recipe", "id=?", arg(id));
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
    }

    // ---------------------------------------------------------------- orders

    /** Orders still to make first (earliest due date on top), then completed ones (newest first). */
    List<Order> orders() {
        List<Order> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT o.id, o.customer, o.note, o.due, o.status, o.done_date,"
                        + " (SELECT SUM(qty * price) FROM order_item WHERE order_id = o.id),"
                        + " (SELECT GROUP_CONCAT(qty || '× ' || name, ', ') FROM order_item WHERE order_id = o.id)"
                        + " FROM orders o ORDER BY o.status,"
                        + " CASE o.status WHEN 0 THEN o.due END, o.done_date DESC, o.id DESC", null)) {
            while (c.moveToNext()) {
                Order o = new Order();
                o.id = c.getLong(0);
                o.customer = c.getString(1);
                o.note = c.getString(2);
                o.due = c.getString(3);
                o.done = c.getInt(4) == 1;
                o.doneDate = c.getString(5);
                o.total = c.getDouble(6);
                o.summary = c.isNull(7) ? "" : c.getString(7);
                out.add(o);
            }
        }
        return out;
    }

    Order order(long id) {
        for (Order o : orders()) if (o.id == id) return o;
        return null;
    }

    /** For an order that is not completed, {@code cost} is today's ingredient cost of one serving. */
    List<OrderItem> orderItems(long orderId) {
        List<OrderItem> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT oi.recipe_id, oi.name, oi.qty, oi.price, CASE o.status WHEN 1 THEN oi.cost ELSE"
                        + " COALESCE((SELECT SUM(ri.qty * " + UNIT_COST + ") FROM recipe_item ri"
                        + " JOIN ingredient i ON i.id = ri.ingredient_id WHERE ri.recipe_id = oi.recipe_id), 0) END"
                        + " FROM order_item oi JOIN orders o ON o.id = oi.order_id WHERE oi.order_id = ?"
                        + " ORDER BY oi.rowid", arg(orderId))) {
            while (c.moveToNext()) {
                OrderItem it = new OrderItem();
                it.recipeId = c.getLong(0);
                it.name = c.getString(1);
                it.qty = c.getInt(2);
                it.price = c.getDouble(3);
                it.cost = c.getDouble(4);
                out.add(it);
            }
        }
        return out;
    }

    void saveOrder(Order o, List<OrderItem> items) {
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            ContentValues v = new ContentValues();
            v.put("customer", o.customer);
            v.put("note", o.note);
            v.put("due", o.due);
            if (o.id == 0) o.id = d.insert("orders", null, v);
            else d.update("orders", v, "id=?", arg(o.id));
            d.delete("order_item", "order_id=?", arg(o.id));
            for (OrderItem it : items) {
                ContentValues iv = new ContentValues();
                iv.put("order_id", o.id);
                iv.put("recipe_id", it.recipeId);
                iv.put("name", it.name);
                iv.put("qty", it.qty);
                iv.put("price", it.price);
                d.insert("order_item", null, iv);
            }
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
    }

    void deleteOrder(long id) {
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            d.delete("order_item", "order_id=?", arg(id));
            d.delete("orders", "id=?", arg(id));
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
    }

    /** Records the sale: takes the ingredients out of stock and freezes the cost of each item. */
    void completeOrder(long id, String date) {
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            d.execSQL("UPDATE ingredient SET stock = MAX(0, stock - (SELECT SUM(ri.qty * oi.qty)"
                    + " FROM order_item oi JOIN recipe_item ri ON ri.recipe_id = oi.recipe_id"
                    + " WHERE oi.order_id = ?1 AND ri.ingredient_id = ingredient.id))"
                    + " WHERE id IN (SELECT ri.ingredient_id FROM order_item oi"
                    + " JOIN recipe_item ri ON ri.recipe_id = oi.recipe_id WHERE oi.order_id = ?1)",
                    new Object[]{id});
            d.execSQL("UPDATE order_item SET cost = COALESCE((SELECT SUM(ri.qty * " + UNIT_COST + ")"
                    + " FROM recipe_item ri JOIN ingredient i ON i.id = ri.ingredient_id"
                    + " WHERE ri.recipe_id = order_item.recipe_id), 0) WHERE order_id = ?", new Object[]{id});
            d.execSQL("UPDATE orders SET status = 1, done_date = ? WHERE id = ?", new Object[]{date, id});
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
    }

    int pendingOrderCount() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM orders WHERE status = 0", null)) {
            c.moveToFirst();
            return c.getInt(0);
        }
    }

    // ----------------------------------------------------------------- needs

    /** Ingredients needed for every order that is not completed yet. */
    List<Need> pendingNeeds() {
        return needs("SELECT oi.recipe_id, oi.qty FROM order_item oi"
                + " JOIN orders o ON o.id = oi.order_id WHERE o.status = 0");
    }

    /** Ingredients needed to make the given servings (recipe id -> number of servings). */
    List<Need> needsFor(Map<Long, Integer> servings) {
        if (servings.isEmpty()) return new ArrayList<>();
        StringBuilder sql = new StringBuilder();
        for (Map.Entry<Long, Integer> e : servings.entrySet()) {
            if (sql.length() > 0) sql.append(" UNION ALL ");
            sql.append("SELECT ").append(e.getKey()).append(" AS recipe_id, ").append(e.getValue()).append(" AS qty");
        }
        return needs(sql.toString());
    }

    /** @param servingsSql a query returning (recipe_id, qty) rows */
    private List<Need> needs(String servingsSql) {
        List<Need> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT i.name, i.unit, SUM(ri.qty * s.qty), i.stock, " + UNIT_COST
                        + " FROM (" + servingsSql + ") s JOIN recipe_item ri ON ri.recipe_id = s.recipe_id"
                        + " JOIN ingredient i ON i.id = ri.ingredient_id"
                        + " GROUP BY i.id ORDER BY i.name COLLATE NOCASE", null)) {
            while (c.moveToNext()) {
                Need n = new Need();
                n.name = c.getString(0);
                n.unit = c.getString(1);
                n.need = c.getDouble(2);
                n.stock = c.getDouble(3);
                n.unitCost = c.getDouble(4);
                out.add(n);
            }
        }
        return out;
    }

    // -------------------------------------------------------------- expenses

    /** @param month yyyy-MM */
    List<Expense> expenses(String month) {
        List<Expense> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, date, category, amount, note FROM expense WHERE substr(date, 1, 7) = ?"
                        + " ORDER BY date DESC, id DESC", new String[]{month})) {
            while (c.moveToNext()) {
                Expense e = new Expense();
                e.id = c.getLong(0);
                e.date = c.getString(1);
                e.category = c.getString(2);
                e.amount = c.getDouble(3);
                e.note = c.isNull(4) ? "" : c.getString(4);
                out.add(e);
            }
        }
        return out;
    }

    void saveExpense(Expense e) {
        ContentValues v = new ContentValues();
        v.put("date", e.date);
        v.put("category", e.category);
        v.put("amount", e.amount);
        v.put("note", e.note);
        if (e.id == 0) e.id = getWritableDatabase().insert("expense", null, v);
        else getWritableDatabase().update("expense", v, "id=?", arg(e.id));
    }

    void deleteExpense(long id) {
        getWritableDatabase().delete("expense", "id=?", arg(id));
    }

    // --------------------------------------------------------------- summary

    /** @param month yyyy-MM */
    Summary summary(String month) {
        Summary s = new Summary();
        SQLiteDatabase d = getReadableDatabase();
        try (Cursor c = d.rawQuery("SELECT SUM(oi.qty * oi.price), SUM(oi.qty * oi.cost), COUNT(DISTINCT o.id)"
                + " FROM orders o JOIN order_item oi ON oi.order_id = o.id"
                + " WHERE o.status = 1 AND substr(o.done_date, 1, 7) = ?", new String[]{month})) {
            c.moveToFirst();
            s.sales = c.getDouble(0);
            s.ingredientCost = c.getDouble(1);
            s.ordersDone = c.getInt(2);
        }
        try (Cursor c = d.rawQuery("SELECT SUM(amount) FROM expense WHERE substr(date, 1, 7) = ?",
                new String[]{month})) {
            c.moveToFirst();
            s.expenses = c.getDouble(0);
        }
        return s;
    }

    private static String[] arg(long id) {
        return new String[]{String.valueOf(id)};
    }
}
