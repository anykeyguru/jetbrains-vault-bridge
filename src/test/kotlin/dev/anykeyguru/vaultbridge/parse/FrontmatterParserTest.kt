package dev.anykeyguru.vaultbridge.parse

import org.junit.Assert.assertEquals
import org.junit.Test

class FrontmatterParserTest {
    @Test
    fun blockAndInlineLists() {
        val text = """
            |---
            |title: "My Note"
            |aliases:
            |  - First
            |  - 'Second'
            |tags: [infra, "k8s/traefik"]
            |---
            |body
        """.trimMargin()
        val fm = FrontmatterParser.parse(text)
        assertEquals("My Note", fm.title)
        assertEquals(listOf("First", "Second"), fm.aliases)
        assertEquals(listOf("infra", "k8s/traefik"), fm.tags)
        assertEquals("body", text.substring(fm.bodyStart))
    }

    @Test
    fun scalarTagsSeparatedBySpaceOrComma() {
        assertEquals(listOf("a", "b", "c"), FrontmatterParser.parse("---\ntags: a, b #c\n---\n").tags)
    }

    @Test
    fun noFrontmatter() {
        assertEquals(Frontmatter.NONE, FrontmatterParser.parse("# Title\ntext"))
        assertEquals(Frontmatter.NONE, FrontmatterParser.parse("---\nunclosed: yes\n"))
    }

    @Test
    fun crlfAndBom() {
        val fm = FrontmatterParser.parse("﻿---\r\nalias: One\r\n---\r\nbody")
        assertEquals(listOf("One"), fm.aliases)
    }
}
