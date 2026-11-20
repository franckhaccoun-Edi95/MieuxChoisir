package com.mieuxchoisir.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Valeurs nutritionnelles pour 100 g / 100 ml. */
data class Nutrition(
    val kcal: Double,
    val sugars: Double,
    val fat: Double,
    val proteins: Double,
)

data class Product(
    val code: String,
    val name: String,
    val brand: String,
    val imageUrl: String?,
    val nutriscore: String?,
    val stores: String,
    val storesTags: List<String>,
    val categories: List<String>,
    val nutrition: Nutrition?,
)

class ProductNotFoundException(msg: String) : Exception(msg)

/**
 * Client minimal de l'API publique Open Food Facts (données ouvertes, licence ODbL).
 * Doc : https://openfoodfacts.github.io/openfoodfacts-server/api/
 */
object OffApi {
    private const val BASE = "https://world.openfoodfacts.org"
    // Open Food Facts demande un User-Agent identifiant l'application.
    private const val USER_AGENT = "MieuxChoisir/1.0 (Android; app open source personnelle)"

    private const val FIELDS =
        "code,product_name,product_name_fr,generic_name_fr,brands,image_front_small_url," +
            "nutriscore_grade,stores,stores_tags,categories_tags,categories_hierarchy,nutriments"

    suspend fun getProduct(barcode: String): Product = withContext(Dispatchers.IO) {
        val json = httpGetJson("$BASE/api/v2/product/${enc(barcode)}.json?fields=$FIELDS")
        if (json.optInt("status", 0) != 1 || !json.has("product")) {
            throw ProductNotFoundException(
                "Produit $barcode introuvable dans Open Food Facts."
            )
        }
        parseProduct(json.getJSONObject("product"))
            ?: throw ProductNotFoundException("Fiche du produit $barcode incomplète.")
    }

    /** Produits vendus en France dans une catégorie donnée (ex. "en:plain-yogurts"). */
    suspend fun searchCategory(categoryTag: String, pageSize: Int = 200): List<Product> =
        withContext(Dispatchers.IO) {
            val url = "$BASE/api/v2/search" +
                "?categories_tags=${enc(categoryTag)}" +
                "&countries_tags=en:france" +
                "&sort_by=popularity_key" +
                "&page_size=$pageSize" +
                "&fields=$FIELDS"
            val json = httpGetJson(url)
            val arr: JSONArray = json.optJSONArray("products") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i -> parseProduct(arr.optJSONObject(i)) }
        }

    private fun parseProduct(p: JSONObject?): Product? {
        if (p == null) return null
        val code = p.optString("code").takeIf { it.isNotBlank() } ?: return null
        val name = listOf("product_name_fr", "product_name", "generic_name_fr")
            .map { p.optString(it).trim() }
            .firstOrNull { it.isNotBlank() } ?: "Produit $code"
        val cats = (p.optJSONArray("categories_hierarchy") ?: p.optJSONArray("categories_tags"))
            ?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        val storesTags = p.optJSONArray("stores_tags")
            ?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        return Product(
            code = code,
            name = name,
            brand = p.optString("brands").split(",").firstOrNull()?.trim().orEmpty(),
            imageUrl = p.optString("image_front_small_url").takeIf { it.startsWith("http") },
            nutriscore = p.optString("nutriscore_grade").takeIf { it.length == 1 },
            stores = p.optString("stores").trim(),
            storesTags = storesTags,
            categories = cats,
            nutrition = parseNutrition(p.optJSONObject("nutriments")),
        )
    }

    private fun parseNutrition(n: JSONObject?): Nutrition? {
        if (n == null) return null
        fun num(key: String): Double? =
            if (n.has(key) && !n.isNull(key)) n.optDouble(key).takeIf { !it.isNaN() && it >= 0 } else null

        val kcal = num("energy-kcal_100g") ?: num("energy_100g")?.let { it / 4.184 }
        val sugars = num("sugars_100g")
        val fat = num("fat_100g")
        val proteins = num("proteins_100g")
        if (kcal == null || sugars == null || fat == null || proteins == null) return null
        // Écarte les fiches manifestement erronées
        if (kcal > 950 || sugars > 100 || fat > 100 || proteins > 100) return null
        return Nutrition(kcal, sugars, fat, proteins)
    }

    private fun httpGetJson(url: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            if (code == 429) throw Exception("Trop de requêtes vers Open Food Facts, réessayez dans une minute.")
            if (code !in 200..299) throw Exception("Open Food Facts a répondu $code.")
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
