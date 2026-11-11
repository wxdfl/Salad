# Salad

A small, offline Android app for running a home-based food business.

- **Home** – sales, expenses and profit for the month, and the list of that month's expenses
  (packaging, gas, delivery…) with a button to add more.
- **Orders** – add what a customer ordered. Each line can be customised for that order only
  (extra lettuce, no tomato, an added ingredient, a different price). Marking an order as
  completed records the sale and takes the ingredients out of stock.
  **History** shows the completed orders and sales of any month.
- **Shopping** – a checklist of what to buy so every open order can be made: how much is
  needed, how much you have and how much is missing. Tick things off as you shop; tap an item
  to record the purchase, which adds it to stock and logs the expense.
- **Recipes** – what you sell, its price, and the ingredients in one serving.
  Shows the cost and profit per serving.
- **Stock** – your raw materials, their price and how much you have.
  An ingredient can have extra units of any size you choose (1 can = 250 g, 1 scoop = 20 g…);
  use them in recipes and when buying, and the shopping list shows how many cans to get.

Data is stored only on the phone. Use **⋮ → Back up data** to save a copy somewhere safe
(and **Restore from backup** to bring it back, e.g. on a new phone).

## Example

Recipe *Chicken Salad* = 100 g lettuce, 150 g chicken, 50 g corn, 1 tomato.
An order for 2 shows: lettuce 200 g, chicken 300 g, corn 100 g, tomatoes 2 pcs –
each compared with your stock, with the amount still to buy and its estimated cost.

## Build

Needs JDK 17+ and the Android SDK (platform 35). Open the folder in Android Studio, or:

```
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest   # tests
```

Every push also builds the APK on GitHub: open the **Actions** tab, pick the latest run and
download the `salad-apk` artifact.

The app is plain Java with no libraries (the APK is well under 100 KB) and runs on Android 8.0+.
The release build is signed with the debug key so it can be installed directly; an update only
installs over an existing copy if it was built on the same machine, so back up your data first.
