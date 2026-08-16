package com.uhmk.pos.core.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val Context.salesLayoutDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "uhmk_sales_layout")

data class ProductPage(
    val id: String,
    val name: String,
    val itemIds: Set<String>,
)

data class SalesLayout(
    val pages: List<ProductPage> = emptyList(),
    val pinnedItemIds: Set<String> = emptySet(),
)

/** Device layout for quick product access. Main always remains the complete live catalogue. */
class SalesLayoutStore(private val context: Context) {
    private object Keys {
        val pages = stringPreferencesKey("custom_product_pages")
        val pinned = stringSetPreferencesKey("pinned_product_ids")
    }

    val layout: Flow<SalesLayout> = context.salesLayoutDataStore.data.map { preferences ->
        SalesLayout(
            pages = decodePages(preferences[Keys.pages].orEmpty()),
            pinnedItemIds = preferences[Keys.pinned].orEmpty(),
        )
    }

    suspend fun addPage(name: String, itemIds: Set<String>): String {
        val id = "page-" + UUID.randomUUID()
        updatePages { it + ProductPage(id, name.cleanPageName(), itemIds) }
        return id
    }

    suspend fun updatePage(id: String, name: String, itemIds: Set<String>) = updatePages { pages ->
        pages.map { page ->
            if (page.id == id) page.copy(name = name.cleanPageName(), itemIds = itemIds) else page
        }
    }

    suspend fun deletePage(id: String) = updatePages { pages -> pages.filterNot { it.id == id } }

    suspend fun setPinned(itemId: String, pinned: Boolean) {
        context.salesLayoutDataStore.edit { preferences ->
            val current = preferences[Keys.pinned].orEmpty()
            preferences[Keys.pinned] = if (pinned) current + itemId else current - itemId
        }
    }

    private suspend fun updatePages(transform: (List<ProductPage>) -> List<ProductPage>) {
        context.salesLayoutDataStore.edit { preferences ->
            preferences[Keys.pages] = encodePages(transform(decodePages(preferences[Keys.pages].orEmpty())))
        }
    }

    private fun String.cleanPageName(): String = trim().ifBlank { "New page" }.take(40)

    private fun encodePages(pages: List<ProductPage>): String = JSONArray().apply {
        pages.forEach { page ->
            put(
                JSONObject()
                    .put("id", page.id)
                    .put("name", page.name)
                    .put("items", JSONArray(page.itemIds.toList()))
            )
        }
    }.toString()

    private fun decodePages(raw: String): List<ProductPage> = runCatching {
        if (raw.isBlank()) return@runCatching emptyList()
        val array = JSONArray(raw)
        buildList {
            repeat(array.length()) { index ->
                val value = array.getJSONObject(index)
                val items = value.optJSONArray("items") ?: JSONArray()
                add(
                    ProductPage(
                        id = value.optString("id").ifBlank { "page-" + UUID.randomUUID() },
                        name = value.optString("name", "New page"),
                        itemIds = buildSet {
                            repeat(items.length()) { itemIndex ->
                                items.optString(itemIndex).takeIf(String::isNotBlank)?.let(::add)
                            }
                        },
                    )
                )
            }
        }
    }.getOrDefault(emptyList())
}
