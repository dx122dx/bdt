# Billy's Mod infrastructure

一个通用的 Minecraft 1.20.1 **Fabric 客户端**基础设施模组（mod id：`billy-inf`）。

它本身不绑定任何具体的「目标模组」，而是通过一套**模块框架（module framework）**提供统一的命令、配置与扩展能力。当前内置的子模块是 **debugger（调试器）**——一个可扩展的调试动作（Action）与调试特性（Feature）框架，供你和其它模组以「内置组件包」形式挂载调试工具。

> 设计哲学：基础设施与业务解耦。针对某个具体模组的调试项由**外部模组**经 `billy-inf:debugger` 扩展点注入，目标模组缺失时自动跳过，绝不拖慢甚至阻断游戏启动。

- 包名：`com.billy65536.infrastructure`
- 主类：`InfrastructureMod`
- 作者：billy65536
- 仓库：<https://github.com/dx122dx/mod-infrastructure>
- 许可：**AGPL-3.0-only**
- 环境：client-only｜Java 17｜MC 1.20.1

---

## 目录

- [特性](#特性)
- [安装](#安装)
- [命令总览](#命令总览)
- [模块框架](#模块框架)
- [debugger 子模块](#debugger-子模块)
- [配置](#配置)
- [扩展：为你的模组注入调试包](#扩展为你的模组注入调试包)
- [构建与开发](#构建与开发)
- [依赖与兼容性](#依赖与兼容性)
- [授权](#授权)

---

## 特性

- **模块框架**：以 `IModule` 接口 + Java SPI 自动发现组织功能，新增模块无需改动启动代码。
- **统一的 `/inf` 命令树**：所有模块命令、配置读写、信息查询都挂在 `/inf` 根下，带完整的自动补全。
- **调试器（debugger）**：
  - **调试动作 Action** —— 一次性执行的操作（如设置某状态、触发某流程）。
  - **调试特性 Feature** —— 可开关、状态持久化的能力（如解锁某限制、禁用某应用逻辑），重启后保持。
  - 全部以 `Identifier` 为唯一标识，支持 `run / info / about / enable / disable / list`。
- **双轨配置**：
  - 固定配置（详细日志、失败堆栈）走 AutoConfig，存于 `config/billy-inf.json`。
  - 动态特性开关走独立 Gson 存储，存于 `config/billy-inf-features.json`（不与 AutoConfig 合并，避免动态字段被清掉）。
- **健壮性优先**：任何模块/内置包/动作在加载或执行时的异常都被捕获，**绝不**阻断游戏启动或冒泡到命令系统。
- **可选集成 ModMenu**：提供「设置」入口，缺失时不影响任何功能。

---

## 安装

1. 从发布页下载 `billy-inf-<version>.jar`。
2. 放入 `.minecraft/mods/`。
3. 确保已安装 **Fabric Loader ≥ 0.19.3**、**Fabric API**、**Cloth Config**（运行时必需），**ModMenu** 为可选。
4. 启动游戏，输入 `/inf info` 验证。

> 想获得针对 Chunk Scanner 的调试项？另装 [`cs-dbg`](https://github.com/dx122dx/cs-dbg)（mod id `csdbg`）即可——它在 Chunk Scanner 在场时自动注入调试包，不在场时无声跳过。

---

## 命令总览

根命令为 `/inf`（亦可写作 `/billy-inf:inf`）。

| 命令 | 说明 |
| --- | --- |
| `/inf info [moduleId]` | 显示模组/指定模块的版本、描述、贡献的命令与配置路径 |
| `/inf config get <module> <path>` | 读取某模块配置项 |
| `/inf config set <module> <path=value> [path=value ...]` | 批量设置配置项（空格分隔多条） |
| `/inf config reset <module> <path>` | 将配置项重置为默认值 |
| `/inf dbg action run <id> [args...]` | 执行调试动作 |
| `/inf dbg action info <id>` | 查看动作元信息（名称/描述） |
| `/inf dbg feat about <id>` | 查询调试特性当前启用状态 |
| `/inf dbg feat enable\|disable <id>` | 启用 / 禁用调试特性 |
| `/inf dbg list` | 列出全部已注册的动作与特性 |
| `/inf dbg gui` | 打开配置界面 |

所有参数均带前缀补全；配置 `path` 与 `value` 支持层级钻取补全。

### 示例

```text
# 查看 debugger 模块详情与全部配置路径
/inf info debugger

# 打开调试配置界面
/inf dbg gui

# 列出当前所有调试动作 / 特性
/inf dbg list

# 执行某个调试动作并带参数
/inf dbg action run billy-inf:unlock_all

# 查看某个特性状态并启用
/inf dbg feat about billy-inf:disable_apply_all
/inf dbg feat enable billy-inf:disable_apply_all
```

---

## 模块框架

`billy-inf` 的核心是一套轻量模块框架，位于 `core` 包：

- **`IModule`**：模块扩展点。实现 `getId()` / `getVersion()` / `getName()` / `getDescription()`（强制），以及可选的 `onInitializeModule()` / `getConfig()` / `saveConfig()` / `buildCommands()` / `getCommandLiterals()`。「一次声明全部能力」，注册表与命令登记器统一接管。
- **`ModuleRegistry`**：静态单例注册表。两种登记途径：
  - 显式：`ModuleRegistry.register(module)`；
  - 自动：`ModuleRegistry.discover()` 基于 Java SPI 扫描 `META-INF/services/com.billy65536.infrastructure.core.module.IModule`，发现全部实现（当前仅 `DebuggerModule`）。
  - 任一模块初始化失败仅记录并跳过，不阻断其它模块。
- **发现时机**：`discover()` 挂在 Fabric 的 `CLIENT_STARTED` 事件上，即**所有模组的客户端入口点执行完毕之后**才触发。Fabric 按依赖拓扑序调用入口点，billy-inf 必然早于依赖它的模组；若在入口点内直接发现，下游模块会先于其宿主模组自身初始化而读到未就绪状态。因此模块的 `onInitializeModule()` 可以安全依赖宿主模组的初始化结果，但不应在其中做需要更早时机的注册（资源包监听器、注册表条目等）。
- **`ModuleCommandRegistrar`**：在模块登记时统一挂载其命令子树到 `/inf` 根，根命令构建时只消费登记结果，不自行遍历模块。

新增一个模块只需：实现 `IModule` + 在 services 文件追加一行，**无需改动任何启动代码**。

---

## debugger 子模块

`debugger` 是当前唯一的模块，提供两类调试扩展点：

### 调试动作 Action（`IDebugAction`）

- 一次性执行的操作。注册到 `ActionRegistry` 后由 `/inf dbg action run <id> [args...]` 触发。
- 实现 `execute(MinecraftClient, String[])` 与可选的 `suggest(client, args)`（参数补全）。
- 异常由命令层统一捕获并转为红色聊天反馈；开启「显示异常堆栈」后追加最多 5 帧摘要。

### 调试特性 Feature（`IDebugFeature`）

- 可开关、状态持久化的能力。注册到 `FeatureRegistry` 后由 `/inf dbg feat about|enable|disable <id>` 操作。
- `isDefaultEnabled()` 决定无记录时的初始状态（默认 **false**，调试特性默认关闭）。
- `onEnable()` / `onDisable()` 回调须幂等，用于挂载/卸载事件监听或渲染钩子。
- 启用状态由 `FeatureStateStore` 持久化到 `config/billy-inf-features.json`，重启保持。

### 内置调试包（builtin packs）

**本模组自身不内置任何具体目标模组的调试项**（`BuiltinsManager.PACKS` 当前为空）。调试包由**外部模组**通过扩展点注入：

- 外部 mod 实现 `DebuggerBuiltinProvider`，并在其 `fabric.mod.json` 注册自定义 entrypoint `"billy-inf:debugger"`；
- 在 `contribute(contributor)` 中调用 `contributor.add(requiredModId, displayName, XxxBuiltins::register)`；
- `billy-inf` 仅在 `requiredModId` 已加载时才执行 `register`，否则整包跳过。

内置包采用**延迟类加载隔离**：本框架只引用各包的「独立入口类」方法引用，绝不在常量池中持有目标模组类型，从而让「目标缺失即跳过」真正生效。例子：Chunk Scanner 的调试包位于独立模组 [`cs-dbg`](https://github.com/dx122dx/cs-dbg)。

---

## 配置

配置界面可从 ModMenu 的「设置」按钮或 `/inf dbg gui` 进入，含两个分类：

| 分类 | 内容 | 落盘文件 |
| --- | --- | --- |
| 通用设置 | 详细日志（verboseLogging）、失败时显示堆栈（showActionStackTrace） | `config/billy-inf.json`（AutoConfig） |
| 调试特性 | 各特性的开关（由运行时注册决定） | `config/billy-inf-features.json`（Gson） |

> 注意：两条配置**不可合并**写入同一文件——AutoConfig 以静态类结构反序列化，未知字段会被静默丢弃；动态特性开关因此独立持久化，且其存储会保留「已持久化但当前未注册」的条目，避免注册顺序变化导致用户开关被静默重置。

初始化顺序硬约束（见 `DebuggerModule.onInitializeModule`）：

1. `DebuggerConfigLoader.register()`（AutoConfig 注册）
2. `FeatureStateStore.load()`（特性状态须在任何特性注册前载入）
3. `BuiltinsManager.registerAll()`（收集并注册内置包）
4. `InfrastructureCommands.register()`（命令登记，依赖前面已完成的模块登记）

---

## 扩展：为你的模组注入调试包

你的模组只需 `modImplementation billy-inf`（运行时必需），并实现扩展点：

```java
public final class MyBuiltins implements DebuggerBuiltinProvider {
    @Override
    public void contribute(Contributor contributor) {
        contributor.add(
            "your-mod-id",                       // 目标模组 id，未加载则跳过
            "Your Mod debug pack",               // 日志展示名
            MyBuiltins::register                 // 独立入口类的方法引用（惰性加载）
        );
    }

    // 仅在 your-mod-id 在场时才会被调用
    public static void register() {
        ActionRegistry.register(new MyAction());
        FeatureRegistry.register(new MyFeature());
    }
}
```

在 `fabric.mod.json` 注册 entrypoint：

```json
{
  "entrypoints": {
    "billy-inf:debugger": ["com.example.mymod.MyBuiltins"]
  }
}
```

约束：

- `register` 必须是**独立入口类**的方法引用，不要写成持有目标模组类型的 lambda 闭包，否则会破坏惰性加载。
- 动作/特性 id 使用你自己的命名空间（如 `your-mod-id:foo`），不要占用 `billy-inf`。

---

## 构建与开发

```bash
sh ./gradlew build
```

产物位于 `build/libs/billy-inf-<version>.jar`。

- 版本号采用**语义化版本（SemVer）**，如 `0.1.0` / `1.0.0` / `1.0.0-beta.1`。发布以 **git tag** 为唯一真相源：推送形如 `v1.0.0` 的 tag 时，CI 自动把 tag（去掉 `v` 前缀）注入 `gradle.properties` 的 `mod_version` 后再构建发布，无需手动改版本号。
- 编译级别 Java 17（`--release 17`）。
- 依赖通过 `flatDir` / Maven 仓库解析；Fabric API、Cloth Config 为运行时必需，`modmenu` 为编译期可选。

---

## 依赖与兼容性

| 依赖 | 要求 | 说明 |
| --- | --- | --- |
| fabricloader | `>=0.19.3` | 必需 |
| minecraft | `~1.20.1` | 必需 |
| java | `>=17` | 必需 |
| fabric-api | `*` | 必需 |
| cloth-config | `*` | 必需（承载配置与 GUI） |
| modmenu | `*` | 可选（提供设置入口） |

环境：`client`（仅客户端）。

---

## 授权

本项目以 **AGPL-3.0-only** 许可发布。源代码见 <https://github.com/dx122dx/billy-inf>。
