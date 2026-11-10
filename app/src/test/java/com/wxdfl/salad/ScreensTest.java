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
        U.setCurrency(RuntimeEnvironment.getApplication(), "$");
        db = Db.get(RuntimeEnvironment.getApplication());
        salad = DbTest.seed(db);
        String today = U.today();
        Db.Order sold = DbTest.order(db, salad, 3, today);
        db.completeOrder(sold.id, today);
        open = DbTest.order(db, salad, 2, today);
        db.recordPurchase(db.ingredients().get(1), 400, 1.6, today);
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

    private static View see(Activity a, String text) {
        ShadowLooper.idleMainLooper();
        View v = find(a.getWindow().getDecorView(), text);
        assertNotNull("expected to see \"" + text + "\"", v);
        return v;
    }

    @Test
    public void everyTabShowsItsData() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        see(a, "To prepare for 1 open order");
        see(a, "$24");                 // sales: 3 salads at 8
        see(a, "Tomatoes");

        a.show(1);
        see(a, "To make");
        see(a, "Completed (sold)");
        see(a, "2× Chicken Salad");

        a.show(2);
        see(a, "Costs $2.55 · profit $5.45 (68%)");

        a.show(3);
        see(a, "$2.50 per 500 g");

        a.show(4);
        see(a, "Total spent: $1.60");

        see(a, "New expense").performClick();
        ShadowLooper.idleMainLooper();
        assertNotNull(ShadowDialog.getLatestDialog());
        assertTrue(ShadowDialog.getLatestDialog().isShowing());
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
        see(a, "Save recipe").performClick();
        assertTrue(a.isFinishing());
        assertEquals(4, db.recipeLines(salad.id).size());
        assertEquals(2.55, db.recipe(salad.id).cost, 1e-9);
    }

    @Test
    public void newOrderAndNewRecipeScreensOpenEmpty() {
        see(Robolectric.buildActivity(OrderActivity.class).setup().get(), "Nothing yet");
        see(Robolectric.buildActivity(RecipeActivity.class).setup().get(), "Nothing yet");
    }
}
