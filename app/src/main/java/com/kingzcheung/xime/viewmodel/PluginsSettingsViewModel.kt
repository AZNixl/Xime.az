package com.kingzcheung.xime.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kingzcheung.xime.plugin.core.model.PluginInfo
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PluginsUiState(
    val extensions: List<PluginInfo> = emptyList(),
    val isLoading: Boolean = true,
    val errorMsg: String? = null
)

class PluginsSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val TAG = "PluginsSettingsViewModel"
    
    private val _uiState = MutableStateFlow(PluginsUiState())
    val uiState: StateFlow<PluginsUiState> = _uiState.asStateFlow()
    
    val loadedPlugins = PluginManager.loadedPluginsFlow
    
    init {
        refreshPlugins()
    }
    
    fun refreshPlugins() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMsg = null) }
            
            try {
                withContext(Dispatchers.IO) {
                    PluginManager.scanAndInstallSystemPlugins()
                    PluginManager.loadEnabledPlugins()
                }
                
                val extensions = PluginManager.getAllInstallPlugins()
                
                _uiState.update { it.copy(
                    extensions = extensions,
                    isLoading = false
                )}
            } catch (e: Exception) {
                Log.e(TAG, "Error loading plugins", e)
                _uiState.update { it.copy(
                    isLoading = false,
                    errorMsg = e.message
                )}
            }
        }
    }
    
    fun isPluginEnabled(pluginId: String): Boolean {
        return SettingsPreferences.isPluginEnabled(context, pluginId)
    }
    
    fun setPluginEnabled(pluginId: String, enabled: Boolean) {
        SettingsPreferences.setPluginEnabled(context, pluginId, enabled)
        
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (enabled) {
                    PluginManager.launchPlugin(pluginId)
                } else {
                    PluginManager.unloadPlugin(pluginId)
                }
            }
        }
    }
    
    fun uninstallPlugin(pluginId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                PluginManager.unloadPlugin(pluginId)
                PluginManager.installerManager.uninstallPlugin(pluginId)
            }
            
            _uiState.update { state ->
                state.copy(extensions = state.extensions.filter { it.id != pluginId })
            }
        }
    }
}