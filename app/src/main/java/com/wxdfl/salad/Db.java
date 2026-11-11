package com.wxdfl.salad;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** All data lives in one small SQLite file. Dates are stored as yyyy-MM-dd text. */
final class Db extends SQLiteOpenHelper {
    static final String FILE = "salad.db";
    static final String INGREDIENTS_CATEGORY = "Ingredients";
    /** SQL for the cost of one unit of ingredient {@code i}. */
    private static final String UNIT_COST = "(i.pack_price / i.pack_qty)";
    /**
     * SQL for the amount of recipe line {@code ri} in the ingredient's base unit. A line may be
     * written in one of the ingredient's other units (e.g. 1 can), which is converted here.
     */
    private static final String BASE_QTY = "(ri.qty * COALESCE((SELECT u.factor FROM ingredient_unit u"
            + " WHERE u.ingredient_id = ri.ingredient_id AND u.name = ri.unit), 1))";

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
        super(c, FILE, null, 3);
    }

    @Override
    public void onCreate(SQLiteDatabase d) {
        d.execSQL("CREATE TABLE ingredient(id INTEGER PRIMARY KEY, name TEXT NOT NULL, unit TEXT NOT NULL,"
                + " pack_qty REAL NOT NULL, pack_price REAL NOT NULL, stock REAL NOT NULL DEFAULT 0,"
                + " ticked INTEGER NOT NULL DEFAULT 0)");
        d.execSQL("CREATE TABLE recipe(id INTEGER PRIMARY KEY, name TEXT NOT NULL, price REAL NOT NULL)");
        d.execSQL("CREATE TABLE recipe_item(recipe_id INTEGER NOT NULL, ingredient_id INTEGER NOT NULL,"
                + " qty REAL NOT NULL, unit TEXT)");
        d.execSQL(CREATE_UNITS);
        d.execSQL("CREATE TABLE orders(id INTEGER PRIMARY KEY, customer TEXT, note TEXT, due TEXT,"
                + " status INTEGER NOT NULL DEFAULT 0, done_date TEXT)");
        d.execSQL("CREATE TABLE order_item(order_id INTEGER NOT NULL, recipe_id INTEGER NOT NULL,"
                + " name TEXT NOT NULL, qty INTEGER NOT NULL, price REAL NOT NULL, cost REAL NOT NULL DEFAULT 0,"
                + " line INTEGER, custom TEXT)");
        d.execSQL(CREATE_MODS);
        d.execSQL("CREATE TABLE expense(id INTEGER PRIMARY KEY, date TEXT NOT NULL, category TEXT NOT NULL,"
                + " amount REAL NOT NULL, note TEXT)");
    }

    /** Extra units of an ingredient: one {@code name} equals {@code factor} of its base unit. */
    private static final String CREATE_UNITS = "CREATE TABLE ingredient_unit(ingredient_id INTEGER NOT NULL,"
            + " name TEXT NOT NULL, factor REAL NOT NULL)";

    /**
     * A change to one ingredient of one order line, per serving: {@code none} leaves it out,
     * otherwise {@code delta} (in the base unit) is added to what the recipe normally uses.
     */
    private static final String CREATE_MODS = "CREATE TABLE order_mod(order_id INTEGER NOT NULL,"
            + " line INTEGER NOT NULL, ingredient_id INTEGER NOT NULL, delta REAL NOT NULL DEFAULT 0,"
            + " none INTEGER NOT NULL DEFAULT 0)";

    @Override
    public void onUpgrade(SQLiteDatabase d, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            d.execSQL(CREATE_UNITS);
            d.execSQL("ALTER TABLE recipe_item ADD COLUMN unit TEXT");
        }
        if (oldVersion < 3) {
            d.execSQL(CREATE_MODS);
            d.execSQL("ALTER TABLE order_item ADD COLUMN line INTEGER");
            d.execSQL("ALTER TABLE order_item ADD COLUMN custom TEXT");
            d.execSQL("UPDATE order_item SET line = rowid");
            d.execSQL("ALTER TABLE ingredient ADD COLUMN ticked INTEGER NOT NULL DEFAULT 0");
        }
    }

    // ---------------------------------------------------------------- models

    static final class Ingredient {
        long id;
        String name = "", unit = "g";
        double packQty = 1, packPrice, stock;
        /** Ticked off on the shopping list. */
        boolean ticked;
        /** Other units this ingredient can be counted in, e.g. 1 can = 250 g. */
        List<Unit> units = new ArrayList<>();
    }

    /** One {@code name} (e.g. "can") equals {@code factor} of the ingredient's base unit. */
    static final class Unit {
        String name;
        double factor;

        Unit(String name, double factor) {
            this.name = name;
            this.factor = factor;
        }
    }

    static final class Recipe {
        long id;
        String name = "";
        double price, cost;
    }

    /** One ingredient line of a recipe: how much goes into a single serving. */
    static final class Line {
        long ingredientId;
        /** {@code unit} is the ingredient's base unit; {@code useUnit} the one {@code qty} is in (null = base). */
        String name, unit, useUnit;
        /** {@code factor} converts {@code qty} to base units; {@code unitCost} is per base unit. */
        double qty, factor = 1, unitCost;

        double cost() {
            return qty * factor * unitCost;
        }
    }

    static final class Order {
        long id;
        String customer = "", note = "", due, doneDate, summary;
        boolean done;
        double total;
    }

    static final class OrderItem {
        long recipeId;
        /** {@code custom} describes the changes for this order, e.g. "no Tomatoes"; empty if none. */
        String name, custom = "";
        /** {@code line} is the position within the order. */
        int qty, line;
        double price, cost;
        List<Mod> mods = new ArrayList<>();
    }

    /** A change to one ingredient for one order line: left out, or more/less per serving. */
    static final class Mod {
        long ingredientId;
        String name, unit;
        double delta;
        boolean none;
    }

    static final class Expense {
        long id;
        String date, category, note = "";
        double amount;
    }

    /** Total amount of one ingredient needed, compared with what is in stock. */
    static final class Need {
        Ingredient ingredient;
        String name, unit;
        double need, stock, unitCost;
        /** A bigger unit to shop in (e.g. can), or null. */
        Unit buyUnit;

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
                "SELECT id, name, unit, pack_qty, pack_price, stock, ticked FROM ingredient"
                        + " ORDER BY name COLLATE NOCASE", null)) {
            while (c.moveToNext()) {
                Ingredient i = new Ingredient();
                i.id = c.getLong(0);
                i.name = c.getString(1);
                i.unit = c.getString(2);
                i.packQty = c.getDouble(3);
                i.packPrice = c.getDouble(4);
                i.stock = c.getDouble(5);
                i.ticked = c.getInt(6) == 1;
                out.add(i);
            }
        }
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT ingredient_id, name, factor FROM ingredient_unit ORDER BY rowid", null)) {
            while (c.moveToNext()) {
                for (Ingredient i : out) {
                    if (i.id == c.getLong(0)) i.units.add(new Unit(c.getString(1), c.getDouble(2)));
                }
            }
        }
        return out;
    }

    /** Saves the ingredient together with its list of other units. */
    void saveIngredient(Ingredient i) {
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            ContentValues v = new ContentValues();
            v.put("name", i.name);
            v.put("unit", i.unit);
            v.put("pack_qty", i.packQty);
            v.put("pack_price", i.packPrice);
            v.put("stock", i.stock);
            if (i.id == 0) i.id = d.insert("ingredient", null, v);
            else d.update("ingredient", v, "id=?", arg(i.id));

            // Recipes written in a unit that no longer exists are converted to the base unit,
            // using what that unit used to be worth, so their amounts stay the same.
            StringBuilder kept = new StringBuilder();
            List<Object> args = new ArrayList<>();
            args.add(i.id);
            for (Unit u : i.units) {
                kept.append(kept.length() == 0 ? "?" : ", ?");
                args.add(u.name);
            }
            String gone = "ingredient_id = ?1 AND unit IS NOT NULL AND unit NOT IN (" + kept + ")";
            d.execSQL("UPDATE recipe_item SET qty = qty * COALESCE((SELECT u.factor FROM ingredient_unit u"
                    + " WHERE u.ingredient_id = ?1 AND u.name = recipe_item.unit), 1) WHERE " + gone,
                    args.toArray());
            d.execSQL("UPDATE recipe_item SET unit = NULL WHERE " + gone, args.toArray());

            d.delete("ingredient_unit", "ingredient_id=?", arg(i.id));
            for (Unit u : i.units) {
                ContentValues uv = new ContentValues();
                uv.put("ingredient_id", i.id);
                uv.put("name", u.name);
                uv.put("factor", u.factor);
                d.insert("ingredient_unit", null, uv);
            }
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
    }

    void deleteIngredient(long id) {
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            d.delete("recipe_item", "ingredient_id=?", arg(id));
            d.delete("ingredient_unit", "ingredient_id=?", arg(id));
            d.delete("order_mod", "ingredient_id=?", arg(id));
            d.delete("ingredient", "id=?", arg(id));
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
    }

    /**
     * Adds to stock, remembers the latest price, and logs the money spent as an expense.
     *
     * @param unit the unit {@code qty} is counted in, or null for the ingredient's base unit
     */
    void recordPurchase(Ingredient i, double qty, Unit unit, double paid, String date) {
        double base = qty * (unit == null ? 1 : unit.factor);
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            d.execSQL("UPDATE ingredient SET stock = stock + ?, pack_qty = ?, pack_price = ?, ticked = 0 WHERE id = ?",
                    new Object[]{base, base, paid, i.id});
            Expense e = new Expense();
            e.date = date;
            e.category = INGREDIENTS_CATEGORY;
            e.amount = paid;
            e.note = i.name + " " + U.num(qty) + " " + (unit == null ? i.unit : unit.name);
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
                "SELECT r.id, r.name, r.price, (SELECT SUM(" + BASE_QTY + " * " + UNIT_COST + ") FROM recipe_item ri"
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
                "SELECT i.id, i.name, i.unit, ri.qty, " + UNIT_COST + ", ri.unit, " + BASE_QTY
                        + " / ri.qty FROM recipe_item ri"
                        + " JOIN ingredient i ON i.id = ri.ingredient_id WHERE ri.recipe_id = ?"
                        + " ORDER BY ri.rowid", arg(recipeId))) {
            while (c.moveToNext()) {
                Line l = new Line();
                l.ingredientId = c.getLong(0);
                l.name = c.getString(1);
                l.unit = c.getString(2);
                l.qty = c.getDouble(3);
                l.unitCost = c.getDouble(4);
                if (!c.isNull(6) && c.getDouble(6) != 1) {
                    l.useUnit = c.getString(5);
                    l.factor = c.getDouble(6);
                }
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
                lv.put("unit", l.useUnit);
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
            String open = "recipe_id = ? AND order_id IN (SELECT id FROM orders WHERE status = 0)";
            d.execSQL("DELETE FROM order_mod WHERE EXISTS (SELECT 1 FROM order_item WHERE"
                    + " order_item.order_id = order_mod.order_id AND order_item.line = order_mod.line"
                    + " AND order_item." + open.replace("AND order_id", "AND order_item.order_id") + ")",
                    new Object[]{id});
            d.delete("order_item", open, arg(id));
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
                        + " (SELECT GROUP_CONCAT(qty || '× ' || name || CASE WHEN COALESCE(custom, '') = ''"
                        + " THEN '' ELSE ' (' || custom || ')' END, ', ') FROM order_item WHERE order_id = o.id)"
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
        boolean done = false;
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT oi.line, oi.recipe_id, oi.name, oi.qty, oi.price, oi.cost, oi.custom, o.status"
                        + " FROM order_item oi JOIN orders o ON o.id = oi.order_id WHERE oi.order_id = ?"
                        + " ORDER BY oi.line, oi.rowid", arg(orderId))) {
            while (c.moveToNext()) {
                OrderItem it = new OrderItem();
                it.line = c.getInt(0);
                it.recipeId = c.getLong(1);
                it.name = c.getString(2);
                it.qty = c.getInt(3);
                it.price = c.getDouble(4);
                it.cost = c.getDouble(5);
                it.custom = c.isNull(6) ? "" : c.getString(6);
                done = c.getInt(7) == 1;
                out.add(it);
            }
        }
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT m.line, m.ingredient_id, i.name, i.unit, m.delta, m.none FROM order_mod m"
                        + " JOIN ingredient i ON i.id = m.ingredient_id WHERE m.order_id = ? ORDER BY m.rowid",
                arg(orderId))) {
            while (c.moveToNext()) {
                Mod m = new Mod();
                m.ingredientId = c.getLong(1);
                m.name = c.getString(2);
                m.unit = c.getString(3);
                m.delta = c.getDouble(4);
                m.none = c.getInt(5) == 1;
                for (OrderItem it : out) if (it.line == c.getInt(0)) it.mods.add(m);
            }
        }
        if (!done) {
            Map<Long, Double> unitCosts = unitCosts();
            for (OrderItem it : out) it.cost = cost(servingAmounts(it.recipeId, it.mods), unitCosts);
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
            d.delete("order_mod", "order_id=?", arg(o.id));
            int line = 0;
            for (OrderItem it : items) {
                it.line = ++line;
                it.custom = describe(it.mods);
                ContentValues iv = new ContentValues();
                iv.put("order_id", o.id);
                iv.put("line", it.line);
                iv.put("recipe_id", it.recipeId);
                iv.put("name", it.name);
                iv.put("qty", it.qty);
                iv.put("price", it.price);
                iv.put("custom", it.custom);
                d.insert("order_item", null, iv);
                for (Mod m : it.mods) {
                    ContentValues mv = new ContentValues();
                    mv.put("order_id", o.id);
                    mv.put("line", it.line);
                    mv.put("ingredient_id", m.ingredientId);
                    mv.put("delta", m.delta);
                    mv.put("none", m.none ? 1 : 0);
                    d.insert("order_mod", null, mv);
                }
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
            d.delete("order_mod", "order_id=?", arg(id));
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
            List<OrderItem> items = orderItems(id);
            for (Need n : needsFor(items)) {
                d.execSQL("UPDATE ingredient SET stock = MAX(0, stock - ?) WHERE id = ?",
                        new Object[]{n.need, n.ingredient.id});
            }
            for (OrderItem it : items) {
                d.execSQL("UPDATE order_item SET cost = ? WHERE order_id = ? AND line = ?",
                        new Object[]{it.cost, id, it.line});
            }
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

    /** What a recipe normally puts into one serving: ingredient id -> amount in its base unit. */
    Map<Long, Double> recipeAmounts(long recipeId) {
        Map<Long, Double> out = new LinkedHashMap<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT ri.ingredient_id, " + BASE_QTY
                + " FROM recipe_item ri WHERE ri.recipe_id = ? ORDER BY ri.rowid", arg(recipeId))) {
            while (c.moveToNext()) out.merge(c.getLong(0), c.getDouble(1), Double::sum);
        }
        return out;
    }

    /** What goes into one serving once an order's changes (extra, less, none) are applied. */
    Map<Long, Double> servingAmounts(long recipeId, List<Mod> mods) {
        Map<Long, Double> out = recipeAmounts(recipeId);
        for (Mod m : mods) {
            double amount = m.none ? 0 : out.getOrDefault(m.ingredientId, 0.0) + m.delta;
            if (amount > 0) out.put(m.ingredientId, amount);
            else out.remove(m.ingredientId);
        }
        return out;
    }

    /** Ingredient cost of one serving, with the order's changes applied. */
    double servingCost(long recipeId, List<Mod> mods) {
        return cost(servingAmounts(recipeId, mods), unitCosts());
    }

    private static double cost(Map<Long, Double> amounts, Map<Long, Double> unitCosts) {
        double total = 0;
        for (Map.Entry<Long, Double> e : amounts.entrySet()) {
            total += e.getValue() * unitCosts.getOrDefault(e.getKey(), 0.0);
        }
        return total;
    }

    private Map<Long, Double> unitCosts() {
        Map<Long, Double> out = new HashMap<>();
        for (Ingredient i : ingredients()) out.put(i.id, i.packPrice / i.packQty);
        return out;
    }

    /** Short text for an order line's changes, e.g. "no Tomatoes, +50 g Lettuce". */
    static String describe(List<Mod> mods) {
        StringBuilder sb = new StringBuilder();
        for (Mod m : mods) {
            if (sb.length() > 0) sb.append(", ");
            if (m.none) sb.append("no ").append(m.name);
            else sb.append(m.delta > 0 ? "+" : "−").append(U.num(Math.abs(m.delta)))
                    .append(" ").append(m.unit).append(" ").append(m.name);
        }
        return sb.toString();
    }

    /** Ingredients needed for every order that is not completed yet. */
    List<Need> pendingNeeds() {
        List<OrderItem> items = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id FROM orders WHERE status = 0", null)) {
            while (c.moveToNext()) items.addAll(orderItems(c.getLong(0)));
        }
        return needsFor(items);
    }

    /** Ingredients needed to make the given order lines, sorted by name. */
    List<Need> needsFor(List<OrderItem> items) {
        Map<Long, Double> total = new HashMap<>();
        for (OrderItem it : items) {
            for (Map.Entry<Long, Double> e : servingAmounts(it.recipeId, it.mods).entrySet()) {
                total.merge(e.getKey(), e.getValue() * it.qty, Double::sum);
            }
        }
        List<Need> out = new ArrayList<>();
        for (Ingredient i : ingredients()) {
            Double need = total.get(i.id);
            if (need == null) continue;
            Need n = new Need();
            n.ingredient = i;
            n.name = i.name;
            n.unit = i.unit;
            n.need = need;
            n.stock = i.stock;
            n.unitCost = i.packPrice / i.packQty;
            for (Unit u : i.units) {
                if (u.factor > 1 && n.buyUnit == null) n.buyUnit = u;
            }
            out.add(n);
        }
        return out;
    }

    /** Forgets the ticks of everything that is not on the shopping list any more. */
    void untickAllExcept(List<Long> ingredientIds) {
        StringBuilder ids = new StringBuilder();
        for (Long id : ingredientIds) ids.append(ids.length() == 0 ? "" : ", ").append(id);
        getWritableDatabase().execSQL(
                "UPDATE ingredient SET ticked = 0 WHERE ticked = 1 AND id NOT IN (" + ids + ")");
    }

    /** Ticks an ingredient off the shopping list (or unticks it). */
    void setTicked(long ingredientId, boolean ticked) {
        getWritableDatabase().execSQL("UPDATE ingredient SET ticked = ? WHERE id = ?",
                new Object[]{ticked ? 1 : 0, ingredientId});
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
