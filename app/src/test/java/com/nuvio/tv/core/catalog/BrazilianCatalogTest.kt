package com.nuvio.tv.core.catalog
import org.junit.Test
import org.junit.Assert.*

class BrazilianCatalogTest {
    @Test fun brazilianQueryIncludesOriginalTitlesWithoutLosingTheEnteredQuery() {
        assertEquals(listOf("Iludida", "Sadakatsiz", "The Unfaithful", "A Woman Scorned"), brazilianSearchQueries("  Iludida  "))
        assertEquals("sadakatsiz", brazilianSearchQueries("sadakatsiz").first())
        assertEquals(4, brazilianSearchQueries("sadakatsiz").size)
    }
    @Test fun unrelatedTitlesAndMovieIdentitiesArePreserved() {
        assertEquals(listOf("Infiel"), brazilianSearchQueries("Infiel"))
        assertEquals("Other", brazilianTitle("tt000000", "series", "Other"))
        assertEquals("Sadakatsiz", brazilianTitle("tt12879200", "movie", "Sadakatsiz"))
        assertEquals("Iludida", brazilianTitle("tmdb:110562", "series", "Sadakatsiz"))
    }
    @Test fun originalEpisodesCannotBeMistakenForTheBrazilianCut() {
        val original = (1..31).map { 1 to it }
        val brazil = (1..78).map { 1 to it }
        assertFalse(hasIludidaBrazilianSeason("tt12879200", original))
        assertTrue(hasIludidaBrazilianSeason("tt12879200", brazil))
        assertFalse(hasIludidaBrazilianSeason("tt12879200", original + original + original))
        assertFalse(hasIludidaBrazilianSeason("tt000000", brazil))
        assertTrue(isIludidaInternationalCut("tt12879200", brazil))
        assertFalse(isIludidaInternationalCut("tt12879200", original))
    }
}
