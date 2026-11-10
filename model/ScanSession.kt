package com.example.docscanner.model

import androidx.compose.runtime.mutableStateListOf

object ScanSession {

    val pages = mutableStateListOf<ScannedPage>()

    fun add(page: ScannedPage) {
        pages.add(page)
    }

    fun find(id: String): ScannedPage? = pages.firstOrNull { it.id == id }

    fun update(page: ScannedPage) {
        val index = pages.indexOfFirst { it.id == page.id }
        if (index >= 0) pages[index] = page
    }

    fun remove(id: String) {
        pages.removeAll { it.id == id }
    }

    fun clear() = pages.clear()

    fun snapshot(): List<ScannedPage> = pages.toList()
}
