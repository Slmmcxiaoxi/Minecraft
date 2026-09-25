# Minecraft AI Translate

Minecraft AI Translate 是一个 Fabric 客户端模组，通过 OpenAI 兼容接口翻译 Minecraft 地图和服务器中的可见文本。翻译只发生在渲染层，不修改世界、物品、实体、书本、告示牌、Dialog 或网络数据。

正式版：**1.0.0**

## 项目分类

- **项目类型**：Minecraft Fabric 客户端 Mod
- **主要用途**：基于 OpenAI 兼容接口的游戏文本翻译
- **支持平台**：Minecraft Java Edition / Fabric
- **关键词**：`minecraft-mod` `fabric-mod` `client-mod` `translation` `ai`

| Minecraft | Fabric Loader | Fabric API | Cloth Config | Mod Menu（可选） |
| --- | --- | --- | --- | --- |
| 26.1.2 | 0.19.5+ | 0.155.3+26.1.2 | 26.1.154 | 18.0.1 |
| 26.2 | 0.19.5+ | 0.161.0+26.2 | 26.2.155 | 20.0.2 |
| 26.3 | 0.19.5+ | 0.161.0+26.3 | 26.3.159 | 21.0.0-beta.1 |

需要 Java 25。每个 Minecraft 版本使用对应的 JAR，不要跨版本混用。

## 功能

- 聊天、`/say`、`/me`、`/tell`、`/msg`、`/teammsg` 与 `/tellraw`；
- 物品名称、Lore、Tooltip、hover 文本、容器标题；
- 告示牌、书与笔、成书、标题、副标题、动作栏和 Boss 血条；
- 计分板、实体自定义名称、展示实体、Dialog、成就和 Tab 玩家列表；
- 队伍前后缀翻译，同时保护真实玩家名；
- 当前存档词典、全局词典，以及按存档或服务器隔离的磁盘缓存；
- 单键快捷键、界面内原文/译文切换和翻译优先级调度。

## 安装

1. 安装对应版本的 Minecraft、Fabric Loader 和 Java 25。
2. 将对应 Minecraft 版本的 AI Translate JAR、Fabric API 与 Cloth Config 放入该实例的 `mods` 文件夹。
3. Mod Menu 为可选依赖；安装后可以从模组列表进入设置。
4. 启动游戏，按 `O` 或执行 `/aitranslate config` 打开配置。

## 配置

首次使用需要填写 API URL、API Key、模型名称、源语言和目标语言。源语言 `auto` 会自动识别，目标语言留空时采用游戏语言。

主配置位于 `.minecraft/config/ai_translate/ai_translate.json`。缓存默认位于 `config/ai_translate/cache`，词典默认位于 `config/ai_translate/dictionary`。目录不存在时会自动创建。

API Key 为空时模组不会发起翻译请求，也不会导致游戏崩溃；游戏会继续显示原文并提示完成配置。

### 默认快捷键

| 功能 | 按键 |
| --- | --- |
| 打开配置 | `O` |
| 翻译总开关 | `U` |
| 刷新翻译 | `R` |
| 当前界面原文/译文 | `R` |

快捷键只接受单键。绑定时按 `ESC` 取消，按 `Delete` 或 `Backspace` 清除。

## 命令

| 命令 | 作用 |
| --- | --- |
| `/aitranslate`、`/aitranslate status` | 查看状态 |
| `/aitranslate on`、`off`、`toggle` | 控制翻译总开关 |
| `/aitranslate config` | 打开配置菜单 |
| `/aitranslate refresh` | 刷新当前显示 |
| `/aitranslate reload` | 重新加载配置 |
| `/aitranslate perf` | 查看队列与性能信息 |
| `/aitranslate cache current` | 清空当前上下文缓存 |
| `/aitranslate cache clear` | 清空全部缓存 |
| `/aitranslate cache reload` | 从磁盘重新加载当前缓存 |
| `/aitranslate cache save` | 立即保存缓存 |
| `/aitranslate cache export` | 导出当前缓存 |
| `/aitranslate cache cleanup` | 清理孤立缓存 |

## 常见问题

### 没有出现译文

确认总开关和对应文本源开关已开启，并检查 API URL、API Key、模型名称、源语言过滤和目标语言。第一次请求完成前会暂时显示原文。

### 玩家名会被翻译吗？

不会。在线玩家的聊天前缀、私聊、加入/离开消息、Tab、头顶名称和计分板身份受到保护；队伍显示名、前缀和后缀仍可翻译。

### 会修改地图或聊天数据包吗？

不会。模组只构造用于显示的 Component 副本，不修改世界数据，也不改写发往服务器的聊天数据包。

### 缓存会串档吗？

不会。单人存档和多人服务器分别使用独立目录。退出世界时当前上下文会清空，进入其他世界后重新切换。

### 翻译服务不可用怎么办？

原文会继续显示。检查 API 设置并使用配置页中的连接测试；网络失败不会阻止游戏运行。

## 构建

```powershell
./gradlew.bat clean test build
```

Windows 下项目路径包含非 ASCII 字符时，测试工作进程可能无法解析 Loom 的路径 JAR；请在纯英文目录中构建。

根目录是 26.1.2 源码；26.2 和 26.3 的版本化源码分别位于 `versions/26.2` 与 `versions/26.3`。正式产物位于 `releases/<Minecraft 版本>`，三个版本不能交叉安装。

## 隐私

启用翻译后，可见文本会发送到用户配置的 API 服务。模组不会上传尚未发送的聊天输入，但仍应避免向不可信服务发送隐私或服务器机密。

## 许可证

MIT，见 [LICENSE](LICENSE)。
