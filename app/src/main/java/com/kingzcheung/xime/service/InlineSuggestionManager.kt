package com.kingzcheung.xime.service

import android.os.Bundle
import android.text.style.TextAppearanceSpan
import android.util.Log
import android.util.Size
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class InlineSuggestionManager {

    var suggestions by mutableStateOf<List<InlineSuggestion>>(emptyList())
        private set

    var isAvailable: Boolean = false
        private set

    @RequiresApi(34)
    fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? {
        return try {
            val style = android.widget.inline.Style.Builder()
                .setAttributes(
                    TextAppearanceSpan("sans-serif-medium", android.graphics.Typeface.BOLD, 14)
                )
                .build()
            val spec = InlinePresentationSpec.Builder(
                Size(180, 56),
                Size(500, 136),
            ).setStyle(style).build()
            InlineSuggestionsRequest.Builder(listOf(spec))
                .setMaxSuggestionCount(3)
                .build()
        } catch (e: Throwable) {
            Log.w("InlineSuggestionManager", "onCreateInlineSuggestionsRequest failed", e)
            null
        }
    }

    @RequiresApi(34)
    fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        isAvailable = true
        val newSuggestions = response.inlineSuggestions
        // Ignore empty response if we already have suggestions — keeps inflate in flight
        if (newSuggestions.isEmpty() && suggestions.isNotEmpty()) {
            Log.d("InlineSuggestionManager", "onInlineSuggestionsResponse: ignoring empty, keeping ${suggestions.size} pending suggestions")
            return true
        }
        suggestions = newSuggestions
        Log.d("InlineSuggestionManager", "onInlineSuggestionsResponse: ${newSuggestions.size} suggestions stored")
        return true
    }

    fun clear() {
        suggestions = emptyList()
        isAvailable = false
    }
}
