package com.uhmk.pos.core.db

import android.content.Context
import com.uhmk.pos.core.money.Money
import org.json.JSONObject

/**
 * Loads the starting catalogue from `assets/seed_items.json`, generated from the store's catalogue
 * export with the "STU ..." student rows merged into their regular twin.
 *
 * Item ids come from the catalogue handle, so seeding is idempotent — re-running it updates rows
 * rather than duplicating them, and the same product carries the same id on every device.
 */
object CatalogueSeeder {

    private const val ASSET = "seed_items.json"

    /**
     * The live catalogue carries real supplier costs, so it is kept out of version control.
     * A fresh clone has only the example file — fall back to it rather than crashing on a
     * missing asset, so the project builds and runs for anyone who checks it out.
     */
    private const val FALLBACK_ASSET = "seed_items.example.json"

    fun loadFromAssets(context: Context): List<ItemEntity> {
        val raw = readAsset(context, ASSET)
            ?: readAsset(context, FALLBACK_ASSET)
            ?: return emptyList()

        val array = JSONObject(raw).optJSONArray("items") ?: return emptyList()
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val handle = o.getString("handle")
            val regular = Money.fromPesos(o.getLong("regular"))
            val student = Money.fromPesos(o.getLong("student"))
            val cost = Money.fromPesos(o.optLong("cost", 0))
            val tracks = o.optBoolean("trackStock", true)

            ItemEntity(
                id = idFor(handle),
                name = o.getString("name"),
                category = o.optString("category", "General"),
                sku = o.optString("sku", ""),
                costCentavos = cost,
                costKnown = o.optBoolean("costKnown", cost > 0),
                studentCentavos = student,
                regularCentavos = regular,
                boxCostCentavos = 0,
                unitsPerBox = 1,
                stockQty = o.optInt("stock", 0),
                lowStockAt = if (tracks) 5 else 0,
                trackStock = tracks,
                imagePath = null,
                active = true,
                sortIndex = o.optInt("sortIndex", i),
                // A bundled catalogue is a local fallback, not an administrator edit. A signed-in
                // device downloads the shared catalogue before these rows can ever be published.
                updatedAt = 0,
                cloudVersion = 0,
                pendingFields = "",
                dirty = false,
            )
        }
    }

    private fun readAsset(context: Context, name: String): String? = runCatching {
        context.assets.open(name).bufferedReader().use { it.readText() }
    }.getOrNull()

    fun idFor(handle: String): String =
        "itm-" + handle.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
}
