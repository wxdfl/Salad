package com.wxdfl.salad;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class DbTest {
    private Db db;
    private Db.Recipe salad;

    /** Lettuce, chicken, corn and tomatoes; one chicken salad sells for 8 and costs 2.55 to make. */
    static Db.Recipe seed(Db db) {
        Db.Ingredient lettuce = ingredient(db, "Lettuce", "g", 500, 2.50, 150);
        Db.Ingredient chicken = ingredient(db, "Chicken", "g", 1000, 9, 1000);
        Db.Ingredient corn = ingredient(db, "Corn", "g", 400, 1.60, 0);
        Db.Ingredient tomato = ingredient(db, "Tomatoes", "pcs", 6, 3, 1);
        Db.Recipe r = new Db.Recipe();
        r.name = "Chicken Salad";
        r.price = 8;
        List<Db.Line> lines = new ArrayList<>();
        lines.add(line(lettuce, 100));   // 0.50
        lines.add(line(chicken, 150));   // 1.35
        lines.add(line(corn, 50));       // 0.20
        lines.add(line(tomato, 1));      // 0.50
        db.saveRecipe(r, lines);
        return r;
    }

    static Db.Ingredient ingredient(Db db, String name, String unit, double packQty, double price, double stock) {
        Db.Ingredient i = new Db.Ingredient();
        i.name = name;
        i.unit = unit;
        i.packQty = packQty;
        i.packPrice = price;
        i.stock = stock;
        if (packQty > 1) {                 // bought by the packet, measured in the small unit
            i.units.add(new Db.Unit("pkt", packQty));
            i.buyUnit = "pkt";
        }
        db.saveIngredient(i);
        return i;
    }

    static Db.Line line(Db.Ingredient i, double qty) {
        Db.Line l = new Db.Line();
        l.ingredientId = i.id;
        l.qty = qty;
        return l;
    }

    static Db.Order order(Db db, Db.Recipe r, int qty, String due) {
        Db.Order o = new Db.Order();
        o.customer = "Mei";
        o.due = due;
        Db.OrderItem it = new Db.OrderItem();
        it.recipeId = r.id;
        it.name = r.name;
        it.qty = qty;
        it.price = r.price;
        db.saveOrder(o, Collections.singletonList(it));
        return o;
    }

    @Before
    public void setUp() {
        db = Db.get(RuntimeEnvironment.getApplication());
        salad = seed(db);
    }

    @After
    public void tearDown() {
        Db.closeAll();
    }

    private Db.Need need(List<Db.Need> needs, String name) {
        for (Db.Need n : needs) if (n.name.equals(name)) return n;
        throw new AssertionError("no need for " + name);
    }

    private Db.Ingredient stock(String name) {
        for (Db.Ingredient i : db.ingredients()) if (i.name.equals(name)) return i;
        throw new AssertionError("no ingredient " + name);
    }

    @Test
    public void recipeCostIsSumOfIngredients() {
        assertEquals(2.55, db.recipe(salad.id).cost, 1e-9);
    }

    @Test
    public void oneChickenSaladTellsWhatToBuy() {
        order(db, salad, 1, "2026-11-12");
        List<Db.Need> needs = db.pendingNeeds();
        assertEquals(4, needs.size());
        assertEquals(100, need(needs, "Lettuce").need, 1e-9);
        assertEquals(0, need(needs, "Lettuce").toBuy(), 1e-9);      // 150 g in stock
        assertEquals(150, need(needs, "Chicken").need, 1e-9);
        assertEquals(50, need(needs, "Corn").toBuy(), 1e-9);        // none in stock
        assertEquals(0, need(needs, "Tomatoes").toBuy(), 1e-9);
    }

    @Test
    public void needsAddUpAcrossOpenOrders() {
        order(db, salad, 1, "2026-11-12");
        order(db, salad, 2, "2026-11-13");
        List<Db.Need> needs = db.pendingNeeds();
        assertEquals(300, need(needs, "Lettuce").need, 1e-9);
        assertEquals(150, need(needs, "Lettuce").toBuy(), 1e-9);
        assertEquals(2, need(needs, "Tomatoes").toBuy(), 1e-9);
        assertEquals(2, db.pendingOrderCount());
    }

    @Test
    public void completingAnOrderRecordsSaleAndUsesStock() {
        Db.Order o = order(db, salad, 2, "2026-11-12");
        db.completeOrder(o.id, "2026-11-12");

        assertEquals(0, stock("Lettuce").stock, 1e-9);       // needed 200, had 150: never negative
        assertEquals(700, stock("Chicken").stock, 1e-9);
        assertTrue(db.pendingNeeds().isEmpty());
        assertEquals(0, db.pendingOrderCount());

        Db.Summary s = db.summary("2026-11");
        assertEquals(16, s.sales, 1e-9);
        assertEquals(5.10, s.ingredientCost, 1e-9);
        assertEquals(1, s.ordersDone);
        assertEquals(0, db.summary("2026-10").sales, 1e-9);

        // A later price change must not rewrite the cost of a sale that already happened.
        Db.Ingredient chicken = stock("Chicken");
        chicken.packPrice = 20;
        db.saveIngredient(chicken);
        assertEquals(5.10, db.summary("2026-11").ingredientCost, 1e-9);
        assertEquals(2.55, db.orderItems(o.id).get(0).cost, 1e-9);
    }

    @Test
    public void purchaseAddsStockAndExpense() {
        db.recordPurchase(stock("Corn"), 800, null, 3, "2026-11-10");
        assertEquals(800, stock("Corn").stock, 1e-9);
        assertEquals(3.0 / 800, stock("Corn").packPrice / stock("Corn").packQty, 1e-12);
        List<Db.Expense> expenses = db.expenses("2026-11");
        assertEquals(1, expenses.size());
        assertEquals(3, expenses.get(0).amount, 1e-9);
        assertEquals("Corn 800 g", expenses.get(0).note);
        assertEquals(3, db.summary("2026-11").expenses, 1e-9);
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

    /** Two salads with extra lettuce (+50 g each) and no tomato. */
    private Db.Order customOrder() {
        Db.Order o = new Db.Order();
        o.due = "2026-11-12";
        Db.OrderItem it = new Db.OrderItem();
        it.recipeId = salad.id;
        it.name = salad.name;
        it.qty = 2;
        it.price = 9;
        it.mods.add(mod(stock("Lettuce"), 50, false));
        it.mods.add(mod(stock("Tomatoes"), 0, true));
        db.saveOrder(o, Collections.singletonList(it));
        return o;
    }

    @Test
    public void customOrderChangesWhatIsNeeded() {
        Db.Order o = customOrder();
        List<Db.Need> needs = db.pendingNeeds();
        assertEquals(3, needs.size());                               // no tomatoes at all
        assertEquals(300, need(needs, "Lettuce").need, 1e-9);        // (100 + 50) x 2
        assertEquals(300, need(needs, "Chicken").need, 1e-9);        // unchanged

        Db.OrderItem saved = db.orderItems(o.id).get(0);
        assertEquals("extra Lettuce, no Tomatoes", saved.custom);
        assertEquals(2, saved.mods.size());
        assertEquals(2.30, saved.cost, 1e-9);                        // 2.55 + 0.25 lettuce - 0.50 tomato
        assertEquals("2× Chicken Salad (extra Lettuce, no Tomatoes)", db.order(o.id).summary);
        assertEquals(18, db.order(o.id).total, 1e-9);

        order(db, salad, 1, "2026-11-13");                           // a normal one alongside
        needs = db.pendingNeeds();
        assertEquals(400, need(needs, "Lettuce").need, 1e-9);
        assertEquals(1, need(needs, "Tomatoes").need, 1e-9);
    }

    @Test
    public void completingACustomOrderUsesTheChangedAmounts() {
        Db.Order o = customOrder();
        db.completeOrder(o.id, "2026-11-12");
        assertEquals(0, stock("Lettuce").stock, 1e-9);               // had 150, used 300
        assertEquals(700, stock("Chicken").stock, 1e-9);
        assertEquals(1, stock("Tomatoes").stock, 1e-9);              // untouched
        assertEquals(4.60, db.summary("2026-11").ingredientCost, 1e-9);
        assertEquals(18, db.summary("2026-11").sales, 1e-9);
        assertEquals("extra Lettuce, no Tomatoes", db.orderItems(o.id).get(0).custom);
    }

    @Test
    public void customOrderCanAddAnIngredientOrUseLess() {
        Db.Ingredient avocado = ingredient(db, "Avocado", "g", 200, 4, 0);
        List<Db.Mod> mods = new ArrayList<>();
        mods.add(mod(avocado, 30, false));
        mods.add(mod(stock("Chicken"), -50, false));
        mods.add(mod(stock("Corn"), -80, false));                    // more than the recipe has: none left
        java.util.Map<Long, Double> amounts = db.servingAmounts(salad.id, mods);
        assertEquals(30, amounts.get(avocado.id), 1e-9);
        assertEquals(100, amounts.get(stock("Chicken").id), 1e-9);
        assertTrue(!amounts.containsKey(stock("Corn").id));
        assertEquals("extra Avocado, less Chicken, less Corn", Db.describe(mods));
        assertEquals(2.55 + 0.60 - 0.45 - 0.20, db.servingCost(salad.id, mods), 1e-9);

        // Deleting the ingredient drops the change with it.
        Db.Order o = new Db.Order();
        o.due = "2026-11-12";
        Db.OrderItem it = new Db.OrderItem();
        it.recipeId = salad.id;
        it.name = salad.name;
        it.qty = 1;
        it.mods = mods;
        db.saveOrder(o, Collections.singletonList(it));
        db.deleteIngredient(avocado.id);
        assertEquals(2, db.orderItems(o.id).get(0).mods.size());
    }

    @Test
    public void shoppingTicksAreRememberedAndCleared() {
        long lettuce = stock("Lettuce").id;
        long corn = stock("Corn").id;
        db.setTicked(lettuce, true);
        db.setTicked(corn, true);
        assertTrue(stock("Lettuce").ticked);
        db.recordPurchase(stock("Corn"), 100, null, 1, "2026-11-10");   // bought: tick goes away
        assertTrue(!stock("Corn").ticked);
        db.untickAllExcept(Collections.singletonList(lettuce));
        assertTrue(stock("Lettuce").ticked);
        db.untickAllExcept(new ArrayList<>());
        assertTrue(!stock("Lettuce").ticked);
    }

    /** Corn can also be counted in cans of 250 g; a "Corn cup" uses 1 can (costs 1.00). */
    private Db.Recipe cornCup() {
        Db.Ingredient corn = stock("Corn");
        corn.units.clear();
        corn.units.add(new Db.Unit("can", 250));
        corn.buyUnit = "can";
        db.saveIngredient(corn);
        Db.Recipe cup = new Db.Recipe();
        cup.name = "Corn cup";
        cup.price = 3;
        Db.Line l = line(corn, 1);
        l.useUnit = "can";
        db.saveRecipe(cup, Collections.singletonList(l));
        return cup;
    }

    @Test
    public void recipeCanUseAnotherUnit() {
        Db.Recipe cup = cornCup();
        assertEquals(1.0, db.recipe(cup.id).cost, 1e-9);            // 250 g at 1.60 per 400 g
        Db.Line saved = db.recipeLines(cup.id).get(0);
        assertEquals("can", saved.useUnit);
        assertEquals(1, saved.qty, 1e-9);
        assertEquals(250, saved.factor, 1e-9);
        assertEquals(1.0, saved.cost(), 1e-9);

        order(db, cup, 2, "2026-11-12");
        order(db, salad, 1, "2026-11-12");                           // 50 g more, written in grams
        Db.Need corn = need(db.pendingNeeds(), "Corn");
        assertEquals(550, corn.need, 1e-9);
        assertEquals("can", corn.buyUnit.name);
        assertEquals("3 can", corn.buyText());                       // 2.2 cans, rounded up
        assertEquals(3 * 250 * 1.6 / 400, corn.buyCost(), 1e-9);
        assertEquals("0 pkt", need(db.pendingNeeds(), "Lettuce").buyText());
    }

    @Test
    public void completingAnOrderUsesStockInBaseUnits() {
        Db.Recipe cup = cornCup();
        db.recordPurchase(stock("Corn"), 4, stock("Corn").units.get(0), 6, "2026-11-10");
        assertEquals(1000, stock("Corn").stock, 1e-9);
        assertEquals("Corn 4 can", db.expenses("2026-11").get(0).note);
        assertEquals(6.0 / 1000, stock("Corn").packPrice / stock("Corn").packQty, 1e-12);

        Db.Order o = order(db, cup, 3, "2026-11-12");
        db.completeOrder(o.id, "2026-11-12");
        assertEquals(250, stock("Corn").stock, 1e-9);
        assertEquals(1.5, db.orderItems(o.id).get(0).cost, 1e-9);    // 250 g at 6 per 1000 g
    }

    @Test
    public void changingOrRemovingAUnitKeepsRecipesSensible() {
        Db.Recipe cup = cornCup();
        Db.Ingredient corn = stock("Corn");
        corn.units.get(0).factor = 300;                              // cans got bigger
        db.saveIngredient(corn);
        assertEquals(300, db.recipeLines(cup.id).get(0).factor, 1e-9);
        assertEquals(1.2, db.recipe(cup.id).cost, 1e-9);

        corn.units.clear();                                          // no more cans: recipe becomes 300 g
        db.saveIngredient(corn);
        Db.Line l = db.recipeLines(cup.id).get(0);
        assertEquals(null, l.useUnit);
        assertEquals(300, l.qty, 1e-9);
        assertEquals(1.2, db.recipe(cup.id).cost, 1e-9);
        assertEquals(100, db.recipeLines(salad.id).get(0).qty, 1e-9);   // other recipes untouched
    }

    @Test
    public void dataFromTheFirstVersionIsKeptOnUpgrade() {
        Db.closeAll();
        android.content.Context c = RuntimeEnvironment.getApplication();
        c.deleteDatabase(Db.FILE);
        try (android.database.sqlite.SQLiteDatabase old = c.openOrCreateDatabase(Db.FILE, 0, null)) {
            old.execSQL("CREATE TABLE ingredient(id INTEGER PRIMARY KEY, name TEXT NOT NULL, unit TEXT NOT NULL,"
                    + " pack_qty REAL NOT NULL, pack_price REAL NOT NULL, stock REAL NOT NULL DEFAULT 0)");
            old.execSQL("CREATE TABLE recipe(id INTEGER PRIMARY KEY, name TEXT NOT NULL, price REAL NOT NULL)");
            old.execSQL("CREATE TABLE recipe_item(recipe_id INTEGER NOT NULL, ingredient_id INTEGER NOT NULL,"
                    + " qty REAL NOT NULL)");
            old.execSQL("CREATE TABLE orders(id INTEGER PRIMARY KEY, customer TEXT, note TEXT, due TEXT,"
                    + " status INTEGER NOT NULL DEFAULT 0, done_date TEXT)");
            old.execSQL("CREATE TABLE order_item(order_id INTEGER NOT NULL, recipe_id INTEGER NOT NULL,"
                    + " name TEXT NOT NULL, qty INTEGER NOT NULL, price REAL NOT NULL, cost REAL NOT NULL DEFAULT 0)");
            old.execSQL("CREATE TABLE expense(id INTEGER PRIMARY KEY, date TEXT NOT NULL, category TEXT NOT NULL,"
                    + " amount REAL NOT NULL, note TEXT)");
            old.execSQL("INSERT INTO ingredient VALUES(1, 'Corn', 'g', 400, 1.6, 30)");
            old.execSQL("INSERT INTO recipe VALUES(1, 'Corn cup', 3)");
            old.execSQL("INSERT INTO recipe_item VALUES(1, 1, 100)");
            old.execSQL("INSERT INTO orders VALUES(1, 'Mei', '', '2026-11-12', 0, NULL)");
            old.execSQL("INSERT INTO order_item VALUES(1, 1, 'Corn cup', 2, 3, 0)");
            old.setVersion(1);
        }
        db = Db.get(c);
        assertEquals(0.4, db.recipe(1).cost, 1e-9);
        assertEquals(200, need(db.pendingNeeds(), "Corn").need, 1e-9);
        assertEquals(170, need(db.pendingNeeds(), "Corn").toBuy(), 1e-9);
        assertEquals("pkt", stock("Corn").buyName());                // was "1.60 per 400 g"
        assertEquals(400, stock("Corn").buySize(), 1e-9);
        assertEquals("g", stock("Corn").unit);
        assertTrue(!stock("Corn").ticked);
        assertEquals("2× Corn cup", db.order(1).summary);
        db.completeOrder(1, "2026-11-12");                           // old order lines can still be completed
        assertEquals(0.4, db.orderItems(1).get(0).cost, 1e-9);
        assertEquals(0, stock("Corn").stock, 1e-9);
        cornCup();                                                   // the new feature works on old data
        assertEquals("can", stock("Corn").buyName());
    }

    @Test
    public void addingOrDroppingTheSmallUnitKeepsRecipesRight() {
        // Tuna starts out counted in whole cans only.
        Db.Ingredient tuna = ingredient(db, "Tuna", "can", 1, 2, 5);
        Db.Recipe r = new Db.Recipe();
        r.name = "Tuna salad";
        r.price = 6;
        db.saveRecipe(r, Collections.singletonList(line(tuna, 1)));
        assertEquals(2, db.recipe(r.id).cost, 1e-9);

        // Now: 1 can = 150 g, so amounts are kept in g.
        tuna.unit = "g";
        tuna.buyUnit = "can";
        tuna.units.add(new Db.Unit("can", 150));
        tuna.packQty = 150;
        tuna.stock = 5 * 150;
        db.saveIngredient(tuna);
        Db.Line l = db.recipeLines(r.id).get(0);
        assertEquals("can", l.useUnit);                              // the recipe still says 1 can
        assertEquals(1, l.qty, 1e-9);
        assertEquals(2, db.recipe(r.id).cost, 1e-9);
        order(db, r, 2, "2026-11-12");
        assertEquals(300, need(db.pendingNeeds(), "Tuna").need, 1e-9);
        assertEquals("0 can", need(db.pendingNeeds(), "Tuna").buyText());

        Db.Recipe half = new Db.Recipe();
        half.name = "Tuna bites";
        half.price = 3;
        db.saveRecipe(half, Collections.singletonList(line(stock("Tuna"), 75)));
        assertEquals(1, db.recipe(half.id).cost, 1e-9);

        // And back to whole cans only.
        tuna = stock("Tuna");
        tuna.unit = "can";
        tuna.buyUnit = null;
        tuna.units.clear();
        tuna.packQty = 1;
        tuna.stock = 5;
        db.saveIngredient(tuna);
        assertEquals(null, db.recipeLines(r.id).get(0).useUnit);
        assertEquals(1, db.recipeLines(r.id).get(0).qty, 1e-9);
        assertEquals(0.5, db.recipeLines(half.id).get(0).qty, 1e-9); // 75 g is half a can
        assertEquals(2, db.recipe(r.id).cost, 1e-9);
        assertEquals(1, db.recipe(half.id).cost, 1e-9);
        assertEquals(2, need(db.pendingNeeds(), "Tuna").need, 1e-9);
    }

    @Test
    public void profitPerMonthForTheChart() {
        Db.Order o = order(db, salad, 2, "2026-10-05");
        db.completeOrder(o.id, "2026-10-05");                        // October: +16
        db.recordPurchase(stock("Corn"), 1, null, 30, "2026-11-02"); // November: -30
        double[] p = db.profits("2026-11", 3);
        assertEquals(3, p.length);
        assertEquals(0, p[0], 1e-9);
        assertEquals(16, p[1], 1e-9);
        assertEquals(-30, p[2], 1e-9);
    }

    @Test
    public void deletingRecipeKeepsPastSales() {
        Db.Order sold = order(db, salad, 1, "2026-11-10");
        db.completeOrder(sold.id, "2026-11-10");
        Db.Order open = order(db, salad, 1, "2026-11-12");
        db.deleteRecipe(salad.id);
        assertEquals(8, db.summary("2026-11").sales, 1e-9);
        assertTrue(db.orderItems(open.id).isEmpty());
        assertTrue(db.pendingNeeds().isEmpty());
    }

    @Test
    public void formatting() {
        assertEquals("1,500", U.num(1500));
        assertEquals("0.25", U.num(0.25));
        assertEquals("2026-12", U.shiftMonth("2026-11", 1));
        assertEquals("2025-12", U.shiftMonth("2026-01", -1));
        assertEquals(2.5, U.parse(" 2,5 "), 1e-9);
        assertTrue(Double.isNaN(U.parse("")));
    }
}
