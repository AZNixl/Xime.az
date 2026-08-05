package com.kingzcheung.xime.plugin.core.config

enum class PluginFieldType { TEXT, SECRET, SELECT, MULTI_SELECT, SWITCH, NUMBER }

data class PluginSettingField(
    val key: String,
    val label: String,
    val type: PluginFieldType,
    val placeholder: String? = null,
    val defaultValue: String? = null,
    val options: List<String> = emptyList(),
    val helpText: String? = null,
    val section: String? = null,
    val required: Boolean = true
)

interface IPluginConfigurable {
    fun getSettingsSchema(): List<PluginSettingField> = emptyList()

    /**
     * 动态选项：表单渲染 SELECT / MULTI_SELECT 时，若 [PluginSettingField.options]
     * 为空则调用本方法异步拉取（插件自行实现，如模型列表等运行时接口数据）。
     * 返回 null 表示无动态选项。
     */
    fun getOptions(key: String): List<String>? = null
}
