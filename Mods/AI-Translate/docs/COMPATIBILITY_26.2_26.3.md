# Minecraft 26.2 / 26.3 兼容性评估

日期：2026-09-23

## 结论摘要

- 模组只替换渲染用 `Component`，没有直接 OpenGL、Vulkan、Blaze3D 着色器或 framebuffer 调用。
- 26.2 文本着色器重构不直接影响当前实现；两个新版本均已实际启动到主菜单，Mixin 运行时加载无报错。
- 项目已使用 Loom 1.17、Gradle 9.5.1、Java 25 和 Mojang官方映射，无需进行 Yarn 到官方映射迁移。
- 26.2 与 26.3 必须分别构建，不能共用声明了不同 Minecraft 版本范围的 JAR。

## 26.2

依赖基线：Fabric Loader 0.19.5、Fabric API 0.161.0+26.2、Cloth Config 26.2.155、Mod Menu 20.0.2。

- 实验性 Vulkan：无原始 OpenGL 调用，不受直接影响。
- 文本着色器：无 shader/rendertype 注入，不受直接影响。
- 映射与 Fabric API：`Minecraft.screen/setScreen`、聊天 HUD、Toast 和实体类型接口已按 26.2 官方命名迁移。
- Component：提取器基于公开 Component/Contents 类型；通过组件与 Mixin 测试确认。
- 名称牌距离：范围检测读取实体距离，不覆盖原版可见性，因此新属性仍由游戏决定。

## 26.3

依赖基线：Fabric Loader 0.19.5、Fabric API 0.161.0+26.3、Cloth Config 26.3.159、Mod Menu 21.0.0-beta.1。

- `posteffect`：模组不接触后处理管线，不受直接影响。
- 输入后端：26.3 移除了 GLFW 键盘接口。快捷键已迁移到 Minecraft `InputConstants`/SDL 扫描码，并将 26.2 及更早版本保存的 GLFW 键码自动迁移到配置版本 6。
- 其他 API：告示牌正反面改用 `SignTextSlot`，成就展示数据改用 record 访问器，目录打开功能改用 Java Desktop API。
- Gothic 语言：Auto 模式检测失败时默认尝试翻译；手动源语言仍可使用语言代码。
- Dialog selector/score/nbt：保持数据层只读；无法由客户端解析的节点跳过并显示原文，literal 与 translatable 内容继续处理。

## 回归范围

聊天命令、tellraw、物品/Lore、告示牌、书与笔/成书、标题/副标题/动作栏、Boss 血条、计分板、实体名称、Dialog、成就、容器标题、展示实体、玩家列表、队伍装饰、符号包裹文本、词典和缓存管理。

## 验证结果

- 26.2：303 项自动测试通过，完整构建通过，实际客户端启动到主菜单通过。
- 26.3：304 项自动测试通过，完整构建通过，实际客户端启动到主菜单通过。
- 两个版本均使用空 API Key 启动，安全回退到原文。
- 真实 API 输出质量、多人服务器消息结构、操作系统 IME 和完整画面位置仍属于实机人工验收项，自动测试不能替代这些观察。
