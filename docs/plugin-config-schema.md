# 插件「声明式配置」方案

> 目标：插件（如 funasr ASR）只声明字段/表单，由主 App 负责 UI 渲染与配置存储。
> 实现"一个插件 = 一个平台"，同时让主 App 完整掌控 Compose 的 R8 压缩与混淆，避免插件包体积膨胀。

## 一、核心设计

```
插件(纯逻辑)                       宿主(UI + 存储)
─────────────                      ─────────────
AsrPlugin
 ├─ getSettingsSchema()  ────────►  通用表单渲染器(宿主 Compose)
 ├─ configStore.get("apiKey") ◄───  PluginConfigStore(宿主按 pluginId 存)
 └─ start()/processAudioChunk()     SpeechRecognitionManager 调它
```

原则：
- **插件 = 数据 + 逻辑 + 网络**，不写任何 Compose / UI。
- **UI / 配置存储 = 宿主**，Compose 在宿主内照常被 R8 压缩混淆。
- 插件通过 `compileOnly(project(":plugin-core"))` 编译，UI 库不进入插件 dex。

## 二、plugin-core 新增「声明式配置」接口（纯数据，无 UI）

```kotlin
interface IPluginConfigurable {
    fun getSettingsSchema(): List<PluginSettingField>
}

enum class PluginFieldType { TEXT, SECRET, SELECT, SWITCH, NUMBER }

data class PluginSettingField(
    val key: String,                 // 如 "apiKey"
    val label: String,               // "API Key"
    val type: PluginFieldType,
    val placeholder: String? = null,
    val options: List<String> = emptyList(),   // SELECT 用
    val helpText: String? = null
)
```

- `IPluginEntryClass` 默认 `getSettingsSchema() = emptyList()`，只有带配置的插件才覆写。
- 插件零 Compose 依赖。

## 三、宿主提供「按插件隔离」的配置存储

- `plugin-core` 加 `PluginConfigStore`（`get/set/remove/keys`），挂在 `PluginContext.configStore`。
- 宿主实现 `PluginConfigStoreImpl(hostApp, pluginId)`，落到 `plugin_cfg_$pluginId` 独立 prefs 文件。
- 插件 A 只拿得到 A 的 store，互不串。

```kotlin
interface PluginConfigStore {
    fun get(key: String): String?
    fun set(key: String, value: String)
    fun remove(key: String)
    fun keys(): Set<String>
}
```

## 四、宿主渲染「通用配置表单」

新建 `PluginConfigFormScreen`（复用 `FunAsrSettingsScreen` / `SettingsComponents` 的 Material 3 组件），输入：
- `plugin`（取 `getSettingsSchema()`）
- `configStore`（当前值 + 写回）

按 `PluginFieldType` 渲染：
- `TEXT` → `OutlinedTextField`
- `SECRET` → 密码输入框（`TextVisualTransformation`）
- `SELECT` → 下拉
- `SWITCH` → `Switch`
- `NUMBER` → 数字键盘

保存按钮统一写回 `configStore`。UI 全在宿主，宿主 R8 完全掌控 Compose。

## 五、接通入口（改宿主，不动表情插件路径）

1. `PluginsSettingsScreen` 的 `hasSettings` 判断（约 L232-237）和 `PluginDetailScreen` 的 `when`（约 L66-69），从"只认 EmojiPlugin"改成：

   ```kotlin
   val hasSettings = (pluginInstance as? IPluginEntryClass)?.let {
       it.getSettingsSchema().isNotEmpty()
   } ?: false
   ```

2. 点"设置"跳转 `PluginConfigFormScreen`（传入 pluginId），宿主渲染通用表单。
3. `ExtensionManager` 加 `getEnabledAsrPlugins()`（仿 `getEnabledEmojiPlugins`，约 L184），供 `SpeechRecognitionManager.createBackend()` 插件优先、内置兜底。

## 六、funasr 插件（`plugins/funasr-asr/`）只需

- `class FunAsrAsrPlugin : IPluginEntryClass, IPluginConfigurable, ...`
- `getSettingsSchema()` 返回一个 `SECRET` 字段 `apiKey`
- `onLoad` 里读 `configStore["apiKey"]`
- 保留 `FunAsrWebSocketManager`（`build.gradle.kts` 加 `implementation("com.squareup.okhttp3:okhttp:5.4.0")`，插件自带网络库）
- 不写任何 UI

## 七、体积 / 混淆结论

| 项 | 结论 |
|---|---|
| 插件包 | 只有逻辑 + okhttp，**不含 Compose**，小 |
| 宿主包 | UI/存储全在宿主，Compose 照常被宿主 R8 压缩混淆，**不损失** |
| 唯一新增 keep 规则 | 宿主保住 `IPluginEntryClass` / `IPluginConfigurable` 接口（宿主 R8 静态引用不到，需 keep），量极小 |

相关背景：
- 宿主 `app/build.gradle.kts`：`isMinifyEnabled = true`、`isShrinkResources = true`（约 L76-77）。
- 插件 `plugins/kaomoji/build.gradle.kts`：`compileOnly(project(":plugin-core"))`、`isMinifyEnabled = false`。
- 插件运行时父加载器 = `application.classLoader`（宿主），UI 库复用宿主那份。

## 八、网络能力说明（原理层）

- 插件代码在宿主进程内运行，网络权限由宿主决定。
- 宿主 `app/src/main/AndroidManifest.xml` 已声明 `android.permission.INTERNET`（约 L6），插件在 IME 进程内可直接发起 https / wss / ws。
- `INTERNET` 是 normal 权限，安装即默认授予。
- 明文 http（非 https）：Android 9+ 默认禁止 cleartext，需宿主 manifest 开 `usesCleartextTraffic` 或网络安全配置；插件自身 manifest 无法改变宿主进程策略。
- 插件自带 okhttp（`implementation`），与宿主 okhttp 不同 dex / 类加载器隔离，不冲突。

## 九、落地顺序

1. `plugin-core`：`PluginConfigStore` + `PluginSettingField` / `IPluginConfigurable` + `PluginContext.configStore`。
2. 宿主：`PluginConfigStoreImpl` + `PluginConfigFormScreen` + 设置入口通用化。
3. 宿主：`ExtensionManager.getEnabledAsrPlugins()` + `createBackend()` 插件优先、内置兜底。
4. `plugins/funasr-asr/`：纯逻辑插件 + 单测（参考 `EmojiPluginTest`）。
5. 端到端验证 + 回归（确认未装插件时行为与现状一致，最小修改原则）。

## 十、注意事项

- **同进程共享 UID**：所有插件跑在宿主进程（宿主 UID），不要给插件暴露宿主全局 prefs，只暴露它自己那个 `configStore`；存储层面用 `plugin_cfg_$pluginId` 独立文件隔离。
- **token 明文**：SharedPreferences 明文是常规做法（宿主私有目录）；如需更强可用 AndroidX `EncryptedSharedPreferences`（需给宿主加 `security-crypto` 依赖）。
- **卸载清理**：`onUnload()` 清内存 token；宿主卸载插件时可选清理其 prefs 文件。
- **宿主 R8 keep**：保住 `plugin-core` 的插件接口与反射入口类。
