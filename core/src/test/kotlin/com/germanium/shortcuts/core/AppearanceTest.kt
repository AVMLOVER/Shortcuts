package com.germanium.shortcuts.core

import org.junit.Assert.*
import org.junit.Test

class AppearanceTest {
    @Test fun perShortcutAppearanceRoundTripsWithNestedWorkflows() {
        val source = Shortcut(name = "Styled", icon = "heart", color = "green", steps = listOf(ActionStep(type = "if", branches = listOf(ActionBranch("then")))))
        val codec = XmlCodec()
        assertEquals(source, codec.decode(codec.encode(source)))
        assertTrue(codec.encode(source).toString(Charsets.UTF_8).contains("icon=\"heart\" color=\"green\""))
    }
    @Test fun legacyXmlWithoutAppearanceUsesDefaults() {
        val source = Shortcut(name = "Legacy")
        val codec = XmlCodec()
        val legacy = codec.encode(source).toString(Charsets.UTF_8).replace(" icon=\"layers\" color=\"purple\"", "")
        assertEquals(source, codec.decode(legacy.toByteArray(Charsets.UTF_8)))
    }
    @Test fun unknownFutureStylesArePreservedAndInvalidIdsRejected() {
        val source = Shortcut(name = "Future", icon = "future_symbol", color = "future_color")
        val codec = XmlCodec(); assertEquals(source, codec.decode(codec.encode(source)))
        assertThrows(Problem::class.java) { Shortcut(name = "Bad", icon = "../image") }
        assertThrows(Problem::class.java) { Shortcut(name = "Bad", color = "") }
    }
}
