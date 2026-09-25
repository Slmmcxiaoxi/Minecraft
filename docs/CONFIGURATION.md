# 配置项说明

配置文件：`config/ai_translate.json`（由模组自动创建与保存，UTF-8 无 BOM）。

- 游戏内界面：按 `Shift + O`（默认）或执行 `/aitranslate config`。
- 界面**需要 Cloth Config**（自 1.1.0 起是必需依赖：缺少它时 Fabric 会直接拒绝启动并提示
  「需要 cloth-config」）。Mod Menu 是可选的，只影响从模组列表进入配置界面的入口。
- 所有设置也可用 `/aitranslate set <键> <值>` 修改（例如 `/aitranslate set model deepseek-chat`）。
- 手改 JSON 后重进世界（或按手动刷新键）即可生效；界面里保存会立即生效。

## 界面结构（1.1.0 起）

导航栏顺序固定，左右箭头常驻可点；所有可选分类默认**收起**（下表用 `++` 标出），
点击标题展开：

```
【全局】                翻译开关 / 按键绑定++ / 翻译设置++（翻译语言++ · 翻译显示++）
【API 设置】            连接状态（● 圆点 + 连接测试）/ 模型名称 / API URL / API Key++
【翻译选项】            聊天框类++（含 命令类++）/ 方块类++ / 物品类++ / HUD 类++ / 其他类++
【缓存管理】            当前存档（存档名 · 条目数 · 占用 · 最后更新 + 重新加载 / 清空）
                        / 缓存列表++（每世界一行：导出 · 导入 · 清理）
                        / 高级设置++（缓存根目录 · 打开缓存文件夹
                        · 清理缓存++（清理孤儿缓存 · 清理全部缓存））
【高级设置】            API 相关++ / 翻译调度++ / 渲染相关++ / 缓存相关++ / 调试相关++ / 恢复默认设置
```

连接状态用**圆点颜色**表示：灰色 = 尚未测试，绿色 = 连接成功，红色 = 连接失败。
它取代了聊天栏提示（配置界面会盖住聊天栏，提示本来也看不到）。

---

## 全局

| JSON 键 | 界面项 | 默认 | 说明 |
| --- | --- | --- | --- |
| `translateEnabled` | 翻译开关 | `true` | 关闭后**所有**文本立即恢复原文，且不再请求 |
| `sourceLang` | 源语言 | `auto` | 交给模型判断 |
| `targetLang` | 目标语言 | 首次启动填入游戏语言 | 只在配置里为空时按游戏语言填入一次，之后以配置值为准，永不覆盖玩家填写的值 |
| `translationColor` | 译文颜色 | 空 | 留空沿用原文样式；可填 `#RRGGBB` 或 `&a` 形式 |
| `translationPrefix` | 译文前缀 | 空 | 例如 `[译] `，会加在每条译文前 |

### 按键绑定

| JSON 键 | 界面项 | 默认 | 说明 |
| --- | --- | --- | --- |
| `openConfigKeys` | 配置菜单 | `Shift + O` | 打开配置界面 |
| `toggleTranslateKeys` | 翻译总开关 | `Shift + U` | 关闭/开启翻译；关闭后已显示译文立即还原 |
| `refreshTranslateKeys` | 刷新翻译 | `Shift + R` | 强制重建所有可见文本 |
| `toggleOriginalKeys` | 界面内切换（1.1.2 起，原名"界面内切换原文"） | `R` | 在成书 / 书与笔 / Dialog / 成就 / 容器 / 聊天等界面内切换原文与译文（见下） |

**界面内切换**（1.1.0 新增，1.1.2 扩展）：这些界面打开时总开关快捷键是不生效的（界面会拦截按键），
所以另有一个界面内按键与一个可点击的按钮。这一行在同一行里放三个控件：

```
界面内切换        [按键 R]   [界面按钮：是/否]   [位置]
```

| JSON 键 | 界面项 | 默认 | 说明 |
| --- | --- | --- | --- |
| `showScreenButton` | 界面按钮（1.1.3 起，原名 GUI界面按钮） | `true` | 关闭后按钮立即从当前界面移除（按键仍然可用） |
| `buttonAnchor` | （在"调整位置"里） | `TOP_RIGHT` | 锚点：`TOP_LEFT` / `TOP_RIGHT` / `BOTTOM_LEFT` / `BOTTOM_RIGHT` / `CENTER` |
| `buttonOffsetX` / `buttonOffsetY` | （在"调整位置"里） | `-10` / `10` | 相对锚点的偏移（GUI 像素），负值表示向屏幕内侧 |

- 只影响**当前界面**的文本，HUD 与世界的文本不受影响；
- 离开界面后自动恢复为显示译文；
- 即时生效，且**不产生任何新请求**（译文一直在缓存里）；
- **界面里有输入框处于聚焦状态时完全不触发**（1.1.2 起）：字符键到达游戏时是
  `keyPressed` + `charTyped` 两个事件，`EditBox` 只在后者里收字符，
  所以旧规则会在聊天里打 `redstone` 时来回切换；现在只要在打字就让开，按钮不受影响；
- 其它情况只有界面自己没用到这个键时才会触发，因此在书与笔里输入 `R` 不会被抢走；
- 位置**全局统一**（锚点 + 偏移，换分辨率后仍贴着同一个角），
  「调整位置」打开专用拖动界面：半透明背景、屏幕边界线、可拖动按钮（松手吸附到最近的角）、
  底部 重置 / 保存 / 取消，取消（含 ESC）不写入配置；
- 总开关关闭时会提示「没有译文可切换」，按钮显示为禁用；
- **出现范围（1.1.3 起收紧）**：只在游戏内界面出现 ——
  聊天栏、成书、书与笔、Dialog、容器、成就，以及世界已加载时其它带输入框的界面（含其它模组的编辑器）。
  **不出现**在主菜单、选择世界、多人游戏列表、设置界面及其子界面、暂停菜单、
  Mod Menu 的界面，以及模组自己的界面（配置 / 文件选择 / 位置拖动）。
- **不参与键盘焦点链（1.1.3 起）**：方向键与 Tab 不会聚焦到它，点击它也不会抢走输入框的焦点，
  因此聊天界面里方向键翻历史输入、告示牌 / 书与笔 / Dialog 里继续打字都不受影响。

## API 设置

| JSON 键 | 界面项 | 默认 | 说明 |
| --- | --- | --- | --- |
| `apiBaseUrl` | API URL | `https://api.deepseek.com` | OpenAI 兼容服务的地址。`/v1/chat/completions` 会被自动补全；填到 `/v1` 或完整路径都可以。本地模型（llama.cpp / Ollama / LM Studio / vLLM）填例如 `http://127.0.0.1:8080/` |
| `apiKey` | API Key（默认收起） | 空 | 本地服务通常随便填一个非空值即可（如 `3141`）。为空时模组**不发送任何请求**，只在进入世界时提示一次。**始终以圆点显示**（1.1.3 起取消了明文切换按钮与重置按钮），掩码只作用于显示层，保存的是真实内容 |
| `model` | 模型名称 | `deepseek-chat` | 服务端要求的模型名，必须与服务端一致 |

## 翻译选项

每个叶子项都是一个布尔开关。`命令类` 里的开关按**消息本身的语言键**判定，是真的分开控制：

| 分类 | JSON 键 | 界面项 | 默认 |
| --- | --- | --- | --- |
| 聊天框类 | `enableChatTranslate` | 聊天消息 | 开 |
| └ 命令类 | `enableChatPrivateTranslate` | /msg /tell 私聊 | 开 |
| └ 命令类 | `enableChatEmoteTranslate` | /me 表情消息 | 开 |
| └ 命令类 | `enableChatAnnouncementTranslate` | /say 公告 | 开 |
| └ 命令类 | `enableChatScriptTranslate` | /tellraw 与脚本消息 | 开 |
| └ 命令类 | `enableCommandFeedbackTranslate` | 命令反馈 | 开 |
| 方块类 | `enableSignTranslate` | 告示牌 | 开 |
| 方块类 | `enableContainerTranslate` | 容器标题 | 开 |
| 物品类 | `enableItemTranslate` | 物品名称 | 开 |
| 物品类 | `enableTooltipTranslate` | 提示框 | 开 |
| 物品类 | `enableBookTranslate` | 书与笔 / 成书 | 开 |
| HUD 类 | `enableTitleTranslate` | 标题与副标题 | 开 |
| HUD 类 | `enableActionBarTranslate` | 动作栏 | 开 |
| HUD 类 | `enableBossBarTranslate` | Boss 血条 | 开 |
| HUD 类 | `enableScoreboardTranslate` | 计分板 | 开 |
| HUD 类 | `enableDialogTranslate` | Dialog | 开 |
| HUD 类 | `enableAdvancementTranslate` | 成就 | 开 |
| HUD 类 | `enableEntityNameTranslate` | 实体名称 | 开 |
| HUD 类 | `enableTextDisplayTranslate` | 展示实体 | 开 |
| HUD 类 | `enablePlayerListTranslate` | 玩家列表 | 开 |
| 其他类 | `translateWrappedText` | 翻译符号包裹的文本 | 开 |
| 其他类 | `translateBookEditPage` | 书与笔编辑时翻译当前页 | 关 |
| 其他类 | `translateVisibleOnly` | 仅翻译可见内容 | 关 |
| 其他类 | `translateVanillaItemNames` | 翻译原版物品名 | 关 |

> `/tellraw 与脚本消息` 指"内容不是原版聊天语言键的聊天消息"——地图脚本、奖励提示、
> 其它模组发的消息都在这一类里；原版命令反馈另有开关。

## 缓存管理

| JSON 键 | 界面项 | 默认 | 说明 |
| --- | --- | --- | --- |
| `cacheRoot` | 缓存根目录 | `config/ai_translate/cache` | 相对路径基于 `.minecraft` |
| — | 当前存档（页面顶部） | — | 当前存档 / 服务器名、条目数、占用空间、最后更新时间，右侧 **重新加载 / 清空**（清空只清内容并保留列表条目） |
| — | 缓存列表（收起） | — | 每个世界一行（当前世界行尾标 `← 当前`），右侧三个按钮：**导出 / 导入 / 清理**；行内显示目录、条目数、占用与最后更新时间；无缓存时显示"暂无缓存" |
| — | 打开缓存文件夹 | — | 用系统文件管理器打开缓存根目录 |
| — | 清理缓存（收起） | — | 清理孤儿缓存（已不存在的存档 / 服务器）/ 清理全部缓存 |
| `refreshClearsMemoryCache` | 刷新时清空内存缓存 | `false` | 手动刷新键是否同时丢弃内存中的译文 |

导出 / 导入都是 **JSON 文件**（1.1.2 起，旧版是 zip）：导出写 `exports/<存档>.json`，
导入会打开模组自带的文件选择界面（不依赖系统对话框），按存档合并，
同一条目**以更新的一方为准**（旧的不会覆盖新的）。
清理**当前存档**只清空内容并保留列表条目（当前世界仍在往这个桶里写），
清理其它存档则连同条目一起移除；任何操作后列表立即刷新（重建界面并停留在当前页）。
**整体导入 / 导出已在 1.1.3 移除**：每个世界行已有独立的导出 / 导入，整包操作语义重复且容易误操作。

## 高级（请求与诊断）

| JSON 键 | 界面项 | 默认 | 说明 |
| --- | --- | --- | --- |
| `maxRequestsPerSecond` | 每秒请求上限 | `5` | 令牌桶限速，防止把服务端打满 |
| `maxBatchSize` | 批量大小（条） | `20` | 一次请求最多携带多少条文本 |
| `maxBatchChars` | 单次请求字符上限 | `800` | 一次请求最多携带多少字符；超出自动拆批。**长书页主要靠这一项避免超时** |
| `maxTextSegmentChars` | 单条文本拆分阈值（字符） | `400` | 单条超过该长度就按段落/句子拆成多段，各自请求后拼回 |
| `batchIntervalMs` | 请求间隔（毫秒） | `150` | 两批请求之间的最小间隔 |
| `requestTimeoutSeconds` | 超时（秒，基础值） | `30` | 短请求的超时，同时是超时下限 |
| `longRequestTimeoutSeconds` | 超时上限（秒，长文本） | `120` | 长请求允许等待的上限（实际超时随请求体字符数缩放） |
| `maxRetries` | 每请求重试次数 | `3` | 退避 400 / 800 / 1600 ms |
| `failureThreshold` | 连续失败多少次才提示 | `5` | 低于该值的失败完全静默（保留原文并稍后重试）；配置类错误按 2 次计 |
| `autoDisableOnApiFailure` | 连续失败后关闭总开关 | `true` | 达到阈值才提示并关闭，同时说明恢复方式 |
| `httpThreads` | HTTP 线程数 | `2` | 2–4 足够 |
| `memoryCacheSize` | 内存缓存上限（条） | `5000` | 超过后按 LRU 淘汰 |
| `logPerformanceStats` | 定期输出性能日志 | `false` | 每 5 分钟一行 `[perf]` |
| `debugLog` | 调试日志 | `false` | 输出每个文本源的替换结果、批次内容、跳过原因等 |
| `logBookPages` | 书籍逐页日志 | `false` | 只针对书页：页码 / 页数 / 字符数 / 缓存命中 / 是否显示译文；失败时附原因与原文摘要 |
| ~~`skipVanillaText`~~ | ~~跳过原版已有文案~~ | — | **1.1.2 移除**：它会跳过地图自己写的、恰好与原版某个词相同的文本（例如队伍前缀 `[Guardian]`，`Guardian` 是原版怪物名）。游戏只本地化 `translatable` 组件，字面量文本永远不会被游戏本地化，跳过它只会留下英文 |
| `translateVanillaItemNames` | 翻译原版物品/方块名 | `false` | 客户端语言不是目标语言时，把 `translatable` 名按英文再翻译一次 |

### 开发用（不建议在正常游玩时打开）

| JSON 键 | 默认 | 说明 |
| --- | --- | --- |
| `useMockProvider` | `false` | 离线测试模式：不联网，直接返回假译文（仅内存，不写缓存文件） |
| `autoSelfTest` | `false` | 进世界后自动运行脚本化自检场景（**会修改世界**：放置告示牌与箱子、召唤实体、创建计分板等），只在测试存档使用 |

## 缓存管理

| JSON 键 | 界面项 | 默认 | 说明 |
| --- | --- | --- | --- |
| `cacheRoot` | 缓存目录 | `config/ai_translate/cache` | 缓存根目录，按世界/服务器分桶 |
| `refreshClearsMemoryCache` | 刷新时清空内存缓存 | `false` | 手动刷新键是否同时丢弃内存中的译文（会重新请求） |

缓存布局（每个桶一个目录）：

```
config/ai_translate/cache/
  singleplayer/<世界名>/         或  multiplayer/<服务器地址>/
    book.json  chat.json  sign.json  tooltip.json  item.json ...   ← 按文本源分文件
    metadata.json                                                  ← 条数与最后更新时间
```

删除某个目录即可让那个世界重新翻译；界面「缓存管理」页也能按世界查看、删除、导出/导入缓存。

## 快捷键

| JSON 键 | 界面项 | 默认 | 说明 |
| --- | --- | --- | --- |
| `toggleTranslateKeys` | 翻译总开关 | `Shift + U` | 关闭/开启翻译；关闭后已显示译文立即还原 |
| `openConfigKeys` | 配置菜单 | `Shift + O` | 打开配置界面 |
| `refreshTranslateKeys` | 刷新翻译 | `Shift + R` | 强制重建所有可见文本（译文迟到或未刷新时使用） |
| `toggleOriginalKeys` | 界面内切换 | `R` | 只在成书 / 书与笔 / Dialog / 成就 / 容器 / 聊天等界面内生效（这些界面会拦截普通快捷键）；界面里有输入框聚焦时不触发 |

按键在界面里点击后**就地录制**（按下组合即可），也支持 `/aitranslate keys ...` 命令。

## 命令

| 命令 | 说明 |
| --- | --- |
| `/aitranslate on` / `off` / `toggle` | 总开关 |
| `/aitranslate status` | 当前状态 |
| `/aitranslate perf` | 请求数、批次、成功/失败、平均延迟、排队与连续失败 |
| `/aitranslate cache` | 缓存条目与命中率 |
| `/aitranslate sources` | 各文本源开关一览 |
| `/aitranslate source <来源> <on\|off>` | 单独开关某个来源 |
| `/aitranslate config` | 打开配置界面 |
| `/aitranslate set <键> <值>` | 直接改配置（例如 `/aitranslate set apiKey 3141`） |
| `/aitranslate selftest` | 运行脚本化自检场景（需 `-Daitranslate.dev=true`，会修改世界） |
