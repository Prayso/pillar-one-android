package de.pascal.pillarone.ui

import de.pascal.pillarone.protocol.MenuConfiguration

data class MenuEditorState(
    val deviceId: String? = null,
    val source: MenuConfiguration? = null,
    val draft: MenuConfiguration? = null,
) {
    val dirty: Boolean get() = draft != source

    fun loaded(deviceId: String, configuration: MenuConfiguration) =
        MenuEditorState(deviceId, configuration, configuration)

    fun move(pageId: Int, direction: Int): MenuEditorState {
        val configuration = draft ?: return this
        val index = configuration.pages.indexOfFirst { it.id == pageId }
        val destination = index + direction
        if (index < 0 || direction !in listOf(-1, 1) || destination !in configuration.pages.indices) return this
        val pages = configuration.pages.toMutableList()
        val page = pages.removeAt(index)
        pages.add(destination, page)
        return copy(draft = configuration.copy(pages = pages))
    }

    fun visibility(pageId: Int, visible: Boolean): MenuEditorState {
        val configuration = draft ?: return this
        if (!visible && configuration.startupPageId == pageId) return this
        return copy(draft = configuration.copy(
            pages = configuration.pages.map { if (it.id == pageId) it.copy(visible = visible) else it },
        ))
    }

    fun startup(pageId: Int): MenuEditorState {
        val configuration = draft ?: return this
        if (configuration.pages.none { it.id == pageId && it.visible && it.startupAllowed }) return this
        return copy(draft = configuration.copy(startupPageId = pageId))
    }
}
