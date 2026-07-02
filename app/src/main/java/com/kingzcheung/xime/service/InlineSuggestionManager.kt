package com.kingzcheung.xime.service

import android.os.Bundle
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
            val small = InlinePresentationSpec.Builder(
                Size(120, 20), Size(300, 80)
            ).build()
            val medium = InlinePresentationSpec.Builder(
                Size(200, 56), Size(500, 136)
            ).build()
            val large = InlinePresentationSpec.Builder(
                Size(300, 100), Size(800, 300)
            ).build()
            InlineSuggestionsRequest.Builder(listOf(small, medium, large))
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
