package com.wxdfl.salad;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

/** Opens every screen with realistic data and checks what it shows. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w360dp-h720dp-xhdpi")
public class ScreensTest {
    private Db db;
    private Db.Recipe salad;
    private Db.Order open;

    @Before
    public void setUp() {
        db = Db.get(RuntimeEnvironment.getApplication());
        salad = DbTest.seed(db);
        String today = U.today();
        Db.Order sold = DbTest.order(db, salad, 3, today);
        db.completeOrder(sold.id, today);
        open = DbTest.order(db, salad, 2, today);
        Db.Ingredient corn = db.ingredients().get(1);
        corn.units.add(new Db.Unit("can", 250));
        db.saveIngredient(corn);
        db.recordPurchase(corn, 400, null, 1.6, today);
    }

    @After
    public void tearDown() {
        Db.closeAll();
    }

    private static View find(View root, String text) {
        if (root instanceof TextView && ((TextView) root).getText().toString().contains(text)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View v = find(g.getChildAt(i), text);
                if (v != null) return v;
            }
        }
        return null;
    }

    private static View findType(View root, Class<?> type) {
        if (type.isInstance(root)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View v = findType(g.getChildAt(i), type);
                if (v != null) return v;
            }
        }
        return null;
    }

    private static View see(Activity a, String text) {
        ShadowLooper.idleMainLooper();
        View v = find(a.getWindow().getDecorView(), text);
        assertNotNull("expected to see \"" + text + "\"", v);
        return v;
    }

    @Test
    public void everyTabShowsItsData() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        see(a, "$24");                                   // sales: 3 salads at 8
        see(a, "1 open order");
        ProfitChart chart = (ProfitChart) findType(a.getWindow().getDecorView(), ProfitChart.class);
        String spoken = chart.getContentDescription().toString();
        assertTrue(spoken, spoken.endsWith(U.shortMonth(U.today().substring(0, 7)) + ": $22."));   // 24 - 1.60
        see(a, "Expenses in " + U.monthLabel(U.today().substring(0, 7)));
        see(a, "Corn 400 g");                            // the purchase shows as an expense on Home
        see(a, "New expense").performClick();
        ShadowLooper.idleMainLooper();
        assertTrue(ShadowDialog.getLatestDialog().isShowing());
        ShadowDialog.getLatestDialog().dismiss();

        a.show(1);
        see(a, "Due ");
        see(a, "2× Chicken Salad");
        assertEquals(null, find(a.getWindow().getDecorView(), "Sold "));
        see(a, "History").performClick();
        see(a, "3× Chicken Salad");
        see(a, "1 order completed · $24 in sales");
        assertEquals(null, find(a.getWindow().getDecorView(), "New order"));

        a.show(3);
        see(a, "Costs $2.55 · profit $5.45 (68%)");

        a.show(4);
        see(a, "$2.50 per pkt · 1 pkt = 500 g");
        see(a, "$1.60 per pkt · 1 pkt = 400 g · 1 can = 250 g");
        see(a, "0.55 pkt");                              // chicken: 550 g left of 1 kg packets
    }

    @Test
    public void shoppingListIsAChecklistOfWhatToBuy() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        a.show(2);
        // 2 salads still to make; the 3 already sold used up the lettuce and the tomato.
        see(a, "To make your open order · about $5.50");   // a packet of lettuce and one of tomatoes
        see(a, "Need 200 g · have 0 g");
        see(a, "1 pkt");
        see(a, "Already enough in stock");
        see(a, "Need 300 g · have 550 g");               // chicken
        assertEquals(View.GONE, undoButton(a).getVisibility());

        // Tick the first item (lettuce), then take it back with Undo.
        ((android.widget.CheckBox) findType(a.getWindow().getDecorView(), android.widget.CheckBox.class))
                .performClick();
        assertTrue(db.ingredients().get(2).ticked);
        a.show(2);                                       // the tick is still there after coming back
        assertTrue(((android.widget.CheckBox) findType(a.getWindow().getDecorView(),
                android.widget.CheckBox.class)).isChecked());
        see(a, "Untick everything");
        assertEquals(View.VISIBLE, undoButton(a).getVisibility());
        undoButton(a).performClick();
        assertTrue(!db.ingredients().get(2).ticked);
        assertTrue(!((android.widget.CheckBox) findType(a.getWindow().getDecorView(),
                android.widget.CheckBox.class)).isChecked());
        assertEquals(null, find(a.getWindow().getDecorView(), "Untick everything"));

        // Tapping the item records the purchase, which takes it off the list.
        ((View) see(a, "Lettuce").getParent().getParent()).performClick();   // the row body
        ShadowLooper.idleMainLooper();
        android.app.AlertDialog dialog = (android.app.AlertDialog) ShadowDialog.getLatestDialog();
        java.util.List<android.widget.EditText> inputs = new java.util.ArrayList<>();
        collect(dialog.getWindow().getDecorView(), inputs);
        assertEquals("1", inputs.get(0).getText().toString());       // suggested: 1 packet
        inputs.get(1).setText("2");
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals(500, db.ingredients().get(2).stock, 1e-9);
        see(a, "To make your open order · about $3");    // only the tomatoes are left
        see(a, "Need 200 g · have 500 g");
    }

    @Test
    public void orderLineCanBeCustomised() {
        OrderActivity a = Robolectric.buildActivity(OrderActivity.class,
                new Intent().putExtra("id", open.id)).setup().get();
        Db.Ingredient lettuce = db.ingredients().get(2);
        lettuce.extraQty = 30;                           // preset: "extra" lettuce means +30 g
        db.saveIngredient(lettuce);

        ((View) see(a, "tap to customise").getParent()).performClick();
        ShadowLooper.idleMainLooper();
        android.app.AlertDialog dialog = (android.app.AlertDialog) ShadowDialog.getLatestDialog();
        View form = dialog.getWindow().getDecorView();
        java.util.List<android.widget.Spinner> picks = new java.util.ArrayList<>();
        all(form, android.widget.Spinner.class, picks);
        assertEquals(4, picks.size());                   // chicken, corn, lettuce, tomatoes
        assertEquals("Normal", picks.get(2).getSelectedItem());
        assertEquals("Extra (+30 g)", picks.get(2).getItemAtPosition(1));
        assertEquals("Less (−50 g)", picks.get(2).getItemAtPosition(2));     // no preset: half of 100 g
        assertEquals("No Tomatoes", picks.get(3).getItemAtPosition(3));
        picks.get(2).setSelection(1);                    // extra lettuce
        picks.get(3).setSelection(3);                    // no tomato
        java.util.List<android.widget.EditText> inputs = new java.util.ArrayList<>();
        collect(form, inputs);
        assertEquals(1, inputs.size());                  // only the price is typed
        inputs.get(0).setText("9");
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        see(a, "extra Lettuce, no Tomatoes");
        see(a, "Total: $18");
        see(a, "260 g");                                 // lettuce for the 2 servings
        see(a, "Save order").performClick();
        Db.OrderItem saved = db.orderItems(open.id).get(0);
        assertEquals("extra Lettuce, no Tomatoes", saved.custom);
        assertEquals(9, saved.price, 1e-9);
        assertEquals(2.20, saved.cost, 1e-9);            // 2.55 + 0.15 lettuce - 0.50 tomato

        // Opening it again shows the same choices.
        a = Robolectric.buildActivity(OrderActivity.class, new Intent().putExtra("id", open.id)).setup().get();
        ((View) see(a, "extra Lettuce, no Tomatoes").getParent()).performClick();
        ShadowLooper.idleMainLooper();
        picks.clear();
        all(ShadowDialog.getLatestDialog().getWindow().getDecorView(), android.widget.Spinner.class, picks);
        assertEquals("Extra (+30 g)", picks.get(2).getSelectedItem());
        assertEquals("No Tomatoes", picks.get(3).getSelectedItem());
        assertEquals("Normal", picks.get(0).getSelectedItem());
    }

    @Test
    public void orderScreenShowsNeedsAndCanAddServings() {
        OrderActivity a = Robolectric.buildActivity(OrderActivity.class,
                new Intent().putExtra("id", open.id)).setup().get();
        see(a, "Ingredients needed for this order");
        see(a, "Total: $16");
        see(a, "＋").performClick();   // first match is the "more" button of the item row
        see(a, "Total: $24");
        see(a, "Save order").performClick();
        assertEquals(3, db.orderItems(open.id).get(0).qty);
        assertTrue(a.isFinishing());
    }

    @Test
    public void recipeScreenEditsAndSaves() {
        RecipeActivity a = Robolectric.buildActivity(RecipeActivity.class,
                new Intent().putExtra("id", salad.id)).setup().get();
        see(a, "Ingredient cost per serving: $2.55");
        see(a, "68% margin");
        // Corn has a second unit, so its line offers a choice of unit; switching it changes the cost.
        android.widget.Spinner unit = (android.widget.Spinner) findType(a.getWindow().getDecorView(),
                android.widget.Spinner.class);
        assertNotNull(unit);
        assertEquals("g", unit.getSelectedItem());
        unit.setSelection(1);
        see(a, "Ingredient cost per serving: $252.05");     // 100 packets of lettuce instead of 100 g
        unit.setSelection(0);
        see(a, "Ingredient cost per serving: $2.55");
        see(a, "Save recipe").performClick();
        assertTrue(a.isFinishing());
        assertEquals(4, db.recipeLines(salad.id).size());
        assertEquals(2.55, db.recipe(salad.id).cost, 1e-9);
    }

    @Test
    public void ingredientFormUsesBuyingUnitWithASmallUnit() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        Db.Ingredient corn = db.ingredients().get(1);
        Dialogs.ingredient(a, corn, () -> { });
        ShadowLooper.idleMainLooper();
        android.app.AlertDialog dialog = (android.app.AlertDialog) ShadowDialog.getLatestDialog();
        java.util.List<android.widget.EditText> in = new java.util.ArrayList<>();
        collect(dialog.getWindow().getDecorView(), in);
        // name, buying unit, price of one, stock, size of one, small unit, extra, less
        assertEquals(8, in.size());
        assertEquals("pkt", in.get(1).getText().toString());
        assertEquals("1.6", in.get(2).getText().toString());
        assertEquals("1", in.get(3).getText().toString());           // 400 g in stock = 1 pkt
        assertEquals("400", in.get(4).getText().toString());
        assertEquals("g", in.get(5).getText().toString());

        in.get(1).setText("can");
        in.get(2).setText("1");
        in.get(3).setText("2");
        in.get(4).setText("250");
        in.get(5).setText("");
        in.get(6).setText("50");
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
        assertTrue("stays open until the small unit is named", dialog.isShowing());
        in.get(5).setText("g");
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertTrue(!dialog.isShowing());

        corn = db.ingredients().get(1);
        assertEquals("can", corn.buyName());
        assertEquals(250, corn.buySize(), 1e-9);
        assertEquals("g", corn.unit);
        assertEquals(1, corn.units.size());
        assertEquals(500, corn.stock, 1e-9);                         // 2 cans
        assertEquals(1.0 / 250, corn.unitCost(), 1e-12);
        assertEquals(50, corn.extraQty, 1e-9);
        assertEquals(50 * 1.0 / 250 * 0 + 2.55 - 0.20 + 50 / 250.0, db.recipe(salad.id).cost, 1e-9);
        a.show(4);
        see(a, "$1 per can · 1 can = 250 g");
        see(a, "2 can");
    }

    @Test
    public void newIngredientCanBeCountedInWholeUnitsOnly() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        Dialogs.ingredient(a, null, () -> { });
        ShadowLooper.idleMainLooper();
        android.app.AlertDialog dialog = (android.app.AlertDialog) ShadowDialog.getLatestDialog();
        java.util.List<android.widget.EditText> in = new java.util.ArrayList<>();
        collect(dialog.getWindow().getDecorView(), in);
        in.get(0).setText("Eggs");
        in.get(1).setText("pcs");
        in.get(2).setText("0.3");
        in.get(3).setText("12");
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        Db.Ingredient eggs = db.ingredients().get(2);
        assertEquals("Eggs", eggs.name);
        assertEquals("pcs", eggs.unit);
        assertEquals(null, eggs.buy());
        assertEquals(12, eggs.stock, 1e-9);
        assertEquals(0.3, eggs.unitCost(), 1e-9);
    }

    /** The first "Undo" button on screen (the hint text mentions the word too). */
    private static View undoButton(Activity a) {
        ShadowLooper.idleMainLooper();
        java.util.List<android.widget.Button> buttons = new java.util.ArrayList<>();
        all(a.getWindow().getDecorView(), android.widget.Button.class, buttons);
        for (android.widget.Button b : buttons) if ("Undo".contentEquals(b.getText())) return b;
        throw new AssertionError("no Undo button");
    }

    private static void collect(View root, java.util.List<android.widget.EditText> out) {
        all(root, android.widget.EditText.class, out);
    }

    private static <T> void all(View root, Class<T> type, java.util.List<T> out) {
        if (type.isInstance(root)) out.add(type.cast(root));
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) all(g.getChildAt(i), type, out);
        }
    }

    @Test
    public void newOrderAndNewRecipeScreensOpenEmpty() {
        see(Robolectric.buildActivity(OrderActivity.class).setup().get(), "Nothing yet");
        see(Robolectric.buildActivity(RecipeActivity.class).setup().get(), "Nothing yet");
    }
}
