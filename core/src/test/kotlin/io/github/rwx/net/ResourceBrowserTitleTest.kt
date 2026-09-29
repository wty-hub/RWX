package io.github.rwx.net

import kotlin.test.Test
import kotlin.test.assertEquals

class ResourceBrowserTitleTest {
    @Test
    fun `rtsbox numeric entities are decoded`() {
        assertEquals("ZSY工作室&隔离区V3.9.5.1", "ZSY工作室&#038;隔离区V3.9.5.1".decodeHtmlEntities())
        assertEquals("&", "&#38;".decodeHtmlEntities())
        assertEquals("&", "&#x26;".decodeHtmlEntities())
        assertEquals("—", "&#8212;".decodeHtmlEntities())
    }

    @Test
    fun `named entities are decoded`() {
        assertEquals("A & B", "A &amp; B".decodeHtmlEntities())
        assertEquals("\"quoted\"", "&quot;quoted&quot;".decodeHtmlEntities())
        assertEquals("a < b > c", "a &lt; b &gt; c".decodeHtmlEntities())
        assertEquals("it's", "it&apos;s".decodeHtmlEntities())
        assertEquals("R&B", "R&AMP;B".decodeHtmlEntities())
    }

    @Test
    fun `text without decodable entities is kept verbatim`() {
        assertEquals("升级模组7.0", "升级模组7.0".decodeHtmlEntities())
        assertEquals("A & B", "A & B".decodeHtmlEntities())
        assertEquals("&unknown;", "&unknown;".decodeHtmlEntities())
        assertEquals("&amp", "&amp".decodeHtmlEntities())
        assertEquals("&#;", "&#;".decodeHtmlEntities())
        assertEquals("100% & more", "100% & more".decodeHtmlEntities())
    }
}
