# Mixin 离线校验器

`MixinValidator.java` 用 JDK 的 Class-File API（`java.lang.classfile`）直接读取 Minecraft 的
class 文件，在**不启动游戏**的前提下校验本模组的全部 Mixin。开发过程中发现的四个启动崩溃问题里，
有三个是它能提前抓到的（详见根目录 README 的「调试记录」）。

## 校验内容

1. `@Mixin` 目标类存在，且**类型匹配**（目标是接口时 Mixin 必须声明为 interface，accessor mixin 除外）
2. 每个 `method = "..."` 描述符在目标类或其父类/接口中真实存在
3. 处理方法的 **static 修饰符**与目标一致（`@Redirect` 比对被重定向的方法，其余比对所在方法）
4. 每个 `@At target = "L...;"` 成员存在
5. 每个 `@Redirect` 的**目标指令真的位于所声明的方法体内**（能抓出「成员存在但写错了所在方法」）
6. `@Invoker` / `@Accessor` 成员存在
7. `@Shadow` 成员存在
8. `@ModifyVariable` 是否显式写了 `argsOnly`（默认 `true` 只匹配方法参数，是常见的踩坑点）
9. **每个 Mixin 类是否在 `ai_translate.client.mixins.json` 里注册** —— 漏注册既不是编译错误也不是 Mixin
   错误，类只是永远不会被转换，直到某处把它转型时才崩（`Illegal classload request for accessor mixin ...`）
10. **`@Inject` 回调类型是否与目标返回类型匹配**（void → `CallbackInfo`，有返回值 → `CallbackInfoReturnable`）
    —— 写错时运行时报 `Invalid descriptor ... CallbackInfoReturnable is required`，而 `defaultRequire: 0`
    会让游戏照常启动、功能静默失效
11. Mixin 中未标注 `@Unique` 等语义的普通 `static` 辅助方法必须为 `private`，避免启动时报
    `contains non-private static method` 并拒绝整个 Mixin。

另外，`@Redirect` 命中多个调用点时会在末尾以 `INFO` 列出（Mixin 会把该重定向应用到**每一个**匹配点，
知道有几处很重要：计分板 3 处、标题 2 处、容器标题 2 处、实体名 2 处）。实体名那两处已在游戏里验证：
`EntityRenderer#submitNameDisplay` 的第二处 `submitNameTag`（头顶名牌）确实被重定向
（日志 `[hook] ENTITY_NAME 'Shadow Stalker' -> '[译] Shadow Stalker'` 就来自这条路径）。

## 使用方式

```bat
:: 1. 找到 Loom 缓存里的 Minecraft 合并 jar（版本号按 gradle.properties）
set MCJAR=%USERPROFILE%\.gradle\caches\fabric-loom\26.1.2\minecraft-merged.jar

:: 2. 编译并运行
javac -d tools\out tools\MixinValidator.java
java -cp tools\out MixinValidator "%MCJAR%" src\client\java
```

输出示例（当前代码）：

```
minecraft jar : %USERPROFILE%\.gradle\caches\fabric-loom\26.1.2\minecraft-merged.jar
mixins found  : 18
checks        : 107
RESULT        : OK - every mixin target, handler and instruction resolved
```

若游戏更新后某个注入点变了，这里会直接报出 `@Redirect instruction NOT found inside ...`，
不必反复启动游戏排查。

---

# 异常模型桩服务器

`StubServer.java` 是一个**故意行为不正确**的 OpenAI 兼容桩，用来验证
「模型返回的元素个数不对时，文本不会丢」这条兜底逻辑（详见根目录 README 的 12.2）：

- 一次请求 `> 1` 条文本 → 故意少返回 3 条（模拟本地小模型偶发的「合并/截断」）；
- 一次请求 `= 1` 条文本 → 正确返回 1 条。

```bat
:: 1. 启动桩（默认 8099 端口）
java tools\StubServer.java 8099

:: 2. 把配置界面的 Base URL 改成 http://127.0.0.1:8099，然后进入测试存档
::    预期日志：Expected N translations but got M -> 之后出现逐条请求 -> 文本最终仍被翻译
```

如果日志里只有 `Expected ... but got ...` 却没有任何 `[hook] ... -> '...'`，
说明逐条兜底失效了（这正是修复前的表现）。
