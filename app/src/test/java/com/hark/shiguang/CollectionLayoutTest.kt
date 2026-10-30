package com.hark.shiguang

import org.junit.Assert.assertEquals
import org.junit.Test

class CollectionLayoutTest {
    @Test fun orderKeepsSavedAndAppendsNew() {
        assertEquals(CollectionLayout.DEFAULT, CollectionLayout.order(""))
        val o = CollectionLayout.order("people,bogus,albums,people")
        assertEquals(listOf("people", "albums", "memories", "places", "scene", "info", "tags", "media"), o)
    }

    @Test fun moveStopsAtEnds() {
        val l = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), CollectionLayout.move(l, "b", -1))
        assertEquals(l, CollectionLayout.move(l, "a", -1))
        assertEquals(l, CollectionLayout.move(l, "c", 1))
        assertEquals(l, CollectionLayout.move(l, "x", 1))
    }

    @Test fun parseSet() {
        assertEquals(setOf("tags", "media"), CollectionLayout.parseSet("tags, media,,"))
    }
}
