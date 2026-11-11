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
        db.recordPurchase(stock("Corn"), 800, 3, "2026-11-10");
        assertEquals(800, stock("Corn").stock, 1e-9);
        assertEquals(3.0 / 800, stock("Corn").packPrice / stock("Corn").packQty, 1e-12);
        List<Db.Expense> expenses = db.expenses("2026-11");
        assertEquals(1, expenses.size());
        assertEquals(3, expenses.get(0).amount, 1e-9);
        assertEquals("Corn 800 g", expenses.get(0).note);
        assertEquals(3, db.summary("2026-11").expenses, 1e-9);
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
