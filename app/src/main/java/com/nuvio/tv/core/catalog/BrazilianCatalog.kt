package com.nuvio.tv.core.catalog

// Titles are curated by work identity, never by a fuzzy name match.
// Iludida: IMDb tt12879200; TMDB 110562. Episode IDs always come from addons.
internal fun brazilianSearchQueries(query: String): List<String> {
    val trimmed = query.trim()
    val aliases = listOf("Iludida", "Sadakatsiz", "The Unfaithful", "A Woman Scorned")
    return if (aliases.any { it.equals(trimmed, ignoreCase = true) })
        (listOf(trimmed) + aliases).distinctBy { it.lowercase() }
    else listOf(trimmed)
}

internal fun isIludidaWork(id: String): Boolean =
    id == "tt12879200" || id == "tmdb:110562" || id == "tmdb:tv:110562"

internal fun brazilianTitle(id: String, type: String, original: String): String =
    if (type.lowercase() in setOf("series", "tv") && isIludidaWork(id)) "Iludida" else original

internal fun isIludidaInternationalCut(id: String, episodes: List<Pair<Int?, Int?>>): Boolean =
    isIludidaWork(id) && episodes.any { (season, episode) -> season == 1 && (episode ?: 0) > 31 }

internal fun hasIludidaBrazilianSeason(id: String, episodes: List<Pair<Int?, Int?>>): Boolean =
    isIludidaWork(id) &&
        episodes.filter { it.first == 1 && (it.second ?: 0) > 0 }.distinct().size >= 78
