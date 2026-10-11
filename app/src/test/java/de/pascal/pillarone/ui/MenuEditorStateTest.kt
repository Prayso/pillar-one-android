package de.pascal.pillarone.ui

import de.pascal.pillarone.protocol.MenuConfiguration
import de.pascal.pillarone.protocol.MenuPage
import org.junit.Assert.*
import org.junit.Test

class MenuEditorStateTest {
    private val configuration = MenuConfiguration(
        1, 1, listOf(MenuPage(1, "Maps", true), MenuPage(2, "Music", true), MenuPage(3, "Hidden", false)),
    )
    private val editor = MenuEditorState().loaded("device", configuration)

    @Test fun movingChangesDraftOnlyAndRespectsBoundaries() {
        assertFalse(editor.dirty)
        val moved = editor.move(2, -1)
        assertEquals(listOf(2, 1, 3), moved.draft!!.pages.map { it.id })
        assertEquals(configuration, moved.source)
        assertTrue(moved.dirty)
        assertFalse(moved.move(2, 1).dirty)
        assertEquals(editor, editor.move(1, -1))
        assertEquals(editor, editor.move(3, 1))
        assertEquals(editor, editor.move(99, 1))
        assertEquals(editor, editor.move(1, 2))
    }

    @Test fun startupCannotBeHiddenAndHiddenPageCannotBeStartup() {
        assertEquals(editor, editor.visibility(1, false))
        assertEquals(editor, editor.startup(3))
        assertEquals(editor, editor.startup(99))
        val changed = editor.startup(2).visibility(1, false)
        assertEquals(2, changed.draft!!.startupPageId)
        assertFalse(changed.draft.pages.first { it.id == 1 }.visible)
        assertTrue(changed.dirty)
    }

    @Test fun readbackReplacesSourceAndDraftIncludingUnchangedRevision() {
        val dirty = editor.move(2, -1)
        val read = dirty.loaded("device", configuration)
        assertFalse(read.dirty)
        assertEquals(configuration, read.draft)
        val saved = dirty.loaded("device", dirty.draft!!.copy(revision = 2))
        assertFalse(saved.dirty)
        assertEquals(2L, saved.source!!.revision)
    }

    @Test fun firmwareRestrictedPagesCannotBecomeStartup() {
        val restricted = editor.loaded("device", configuration.copy(
            pages = configuration.pages.map { if (it.id == 2) it.copy(startupAllowed = false) else it },
        ))
        assertEquals(restricted, restricted.startup(2))
        assertTrue(restricted.visibility(2, false).dirty)
    }
}
