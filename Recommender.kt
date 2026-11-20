package com.mieuxchoisir.app

import java.text.Normalizer

data class Alternative(
    val product: Product,
    val score: Double,
    val retailers: List<String>,
)

enum class Availability(val label: String) {
    SUPERMARKET("Vendus en grande surface (enseigne renseignée)"),
    ANY_STORE("Peu de données enseignes : produits vendus en magasin en France"),
    FRANCE("Peu de données enseignes : produits commercialisés en France"),
}

data class Recommendation(
    val reference: Product,
    val alternatives: List<Alternative>,
    val categoryLabel: String,
    val availability: Availability,
)

object Recommender {

    private const val WANTED = 5
    private const val MAX_CATEGORY_LEVELS = 3

    /** Enseignes de grande distribution en France (clé = fragment de stores_tags, valeur = libellé). */
    private val RETAILERS = linkedMapOf(
        "leclerc" to "E.Leclerc",
        "carrefour" to "Carrefour",
        "auchan" to "Auchan",
        "intermarche" to "Intermarché",
        "super-u" to "Super U",
        "hyper-u" to "Hyper U",
        "systeme-u" to "Système U",
        "magasins-u" to "Magasins U",
        "lidl" to "Lidl",
        "aldi" to "Aldi",
        "casino" to "Casino",
        "geant" to "Géant Casino",
        "monoprix" to "Monoprix",
        "franprix" to "Franprix",
        "cora" to "Cora",
        "netto" to "Netto",
        "leader-price" to "Leader Price",
        "match" to "Match",
        "spar" to "Spar",
        "picard" to "Picard",
        "grand-frais" to "Grand Frais",
        "biocoop" to "Biocoop",
        "naturalia" to "Naturalia",
    )

    fun retailersOf(p: Product): List<String> {
        val tags = p.storesTags.ifEmpty {
            p.stores.split(",").map { slug(it) }.filter { it.isNotBlank() }
        }
        return RETAILERS.filterKeys { key ->
            tags.any { t -> t == key || t.startsWith("$key-") || t.endsWith("-$key") || t.contains(key) }
        }.values.distinct()
    }

    /**
     * Cherche les 5 meilleures alternatives dans la catégorie la plus précise du produit,
     * en remontant la hiérarchie de catégories si nécessaire.
     */
    suspend fun recommend(
        reference: Product,
        onProgress: (String) -> Unit = {},
    ): Recommendation {
        val refNut = reference.nutrition
            ?: throw IllegalStateException(
                "Ce produit n'a pas de valeurs nutritionnelles complètes dans Open Food Facts : comparaison impossible."
            )

        val levels = reference.categories
            .filter { it.startsWith("en:") }
            .reversed() // de la plus précise à la plus générale
            .take(MAX_CATEGORY_LEVELS)
        if (levels.isEmpty()) {
            throw IllegalStateException("Ce produit n'a pas de catégorie renseignée : impossible de trouver des équivalents.")
        }

        val pool = LinkedHashMap<String, Product>()
        var best: Recommendation? = null

        for (tag in levels) {
            onProgress("Recherche dans « ${pretty(tag)} »…")
            OffApi.searchCategory(tag).forEach { p ->
                if (p.code != reference.code && p.nutrition != null) pool.putIfAbsent(p.code, p)
            }
            val rec = rank(reference, refNut, pool.values.toList(), pretty(tag))
            if (best == null || rec.alternatives.size > best.alternatives.size) best = rec
            if (rec.alternatives.size >= WANTED && rec.availability == Availability.SUPERMARKET) {
                return rec
            }
        }
        return best!!
    }

    private fun rank(
        reference: Product,
        refNut: Nutrition,
        candidates: List<Product>,
        categoryLabel: String,
    ): Recommendation {
        // Disponibilité : on privilégie les produits avec une enseigne de grande surface connue.
        val tiers = listOf(
            Availability.SUPERMARKET to candidates.filter { retailersOf(it).isNotEmpty() },
            Availability.ANY_STORE to candidates.filter { it.stores.isNotBlank() || it.storesTags.isNotEmpty() },
            Availability.FRANCE to candidates,
        )

        var fallback: Recommendation? = null
        for ((availability, subset) in tiers) {
            val alts = score(refNut, subset)
            val rec = Recommendation(reference, alts, categoryLabel, availability)
            if (alts.size >= WANTED) return rec
            if (fallback == null || alts.size > fallback.alternatives.size) fallback = rec
        }
        return fallback!!
    }

    /**
     * Score multicritère : chaque nutriment est normalisé (0..1) sur l'ensemble comparé.
     * score = (1 - kcal) + (1 - sucres) + (1 - lipides) + protéines.
     * On ne garde que les produits strictement meilleurs que la référence,
     * qui s'améliorent sur au moins 2 critères sur 4.
     */
    private fun score(ref: Nutrition, candidates: List<Product>): List<Alternative> {
        if (candidates.isEmpty()) return emptyList()
        val all = candidates.mapNotNull { it.nutrition } + ref

        fun range(sel: (Nutrition) -> Double): Pair<Double, Double> =
            all.minOf(sel) to all.maxOf(sel)

        val rK = range { it.kcal }
        val rS = range { it.sugars }
        val rF = range { it.fat }
        val rP = range { it.proteins }
        fun norm(v: Double, r: Pair<Double, Double>) =
            if (r.second - r.first < 1e-9) 0.5 else (v - r.first) / (r.second - r.first)

        fun s(n: Nutrition) =
            (1 - norm(n.kcal, rK)) + (1 - norm(n.sugars, rS)) + (1 - norm(n.fat, rF)) + norm(n.proteins, rP)

        val refScore = s(ref)
        val seen = HashSet<String>()

        return candidates
            .asSequence()
            .mapNotNull { p ->
                val n = p.nutrition ?: return@mapNotNull null
                val improvements = listOf(
                    n.kcal < ref.kcal,
                    n.sugars < ref.sugars,
                    n.fat < ref.fat,
                    n.proteins > ref.proteins,
                ).count { it }
                val sc = s(n)
                if (sc > refScore + 0.05 && improvements >= 2) Alternative(p, sc, retailersOf(p)) else null
            }
            .sortedByDescending { it.score }
            // évite les doublons (même produit, plusieurs codes-barres)
            .filter { seen.add(slug(it.product.brand) + "|" + slug(it.product.name)) }
            .take(WANTED)
            .toList()
    }

    fun pretty(tag: String): String =
        tag.substringAfter(":").replace('-', ' ').replaceFirstChar { it.uppercase() }

    private fun slug(s: String): String =
        Normalizer.normalize(s.lowercase().trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
}
