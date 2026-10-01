package io.github.rwx.benchmark

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VanillaUnitCatalogTest {
    private val units = listOf(File("assets/units"), File("../assets/units")).first { it.isDirectory }

    @Test
    fun `definition inventory includes every stock ini including templates and hidden transitions`() {
        val definitions = VanillaUnitCatalog.definitions(units)
        assertEquals(units.walkTopDown().count { it.isFile && it.extension == "ini" }, definitions.size)
        assertTrue(definitions.any { it.relativePath == "modular_spider/baseSlot.ini" && it.template })
        assertTrue(definitions.any { it.relativePath == "amphibious_jet/amphibious_jet_transition.ini" && it.hidden })
        assertTrue(definitions.any { it.relativePath == "mech_factory/mechFactoryT2.ini" && it.hidden })
    }

    @Test
    fun `inventory records real parent attachment spawns and alternate forms`() {
        val definitions = VanillaUnitCatalog.definitions(units)
        val spider = definitions.first { it.relativePath == "modular_spider/modular_spider.ini" }
        assertTrue(spider.attachmentSpawns.any { it.startsWith("modularSpider_emptySlot(") })
        val jet = definitions.first { it.relativePath == "amphibious_jet/amphibious_jet.ini" }
        assertTrue("c_amphibiousJet_underwater" in jet.transformations)
    }
}
