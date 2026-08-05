# Billy's Debug Tools

一个通用的 Minecraft 1.20.1 Fabric **客户端**调试工具模组。

提供统一的调试扩展框架：

- **Action** —— 一次性执行的调试动作
- **Feature** —— 可开关、状态持久化的调试特性

两者均以 `Identifier` 为唯一标识，通过命令与配置界面统一管理。

## 独立运行

本模组不绑定任何特定模组。针对具体目标模组的调试项以「内置组件包」形式存在
（`builtin/<modid>/`），仅当对应模组被加载时才注册生效；目标模组缺失时本模组照常启动，
相应调试项不会出现在列表与界面中，其 Mixin 也会被自动跳过。

当前内置组件包：

| 内置包 | 所需模组 | 内容 |
| --- | --- | --- |
| `chunkscanner` | Chunk Scanner | 配置锁定机制相关的 2 个特性 + 4 个动作 |

## 命令

根命令 `/bdt`，别名 `/billysdebugtools`。

| 命令 | 说明 |
| --- | --- |
| `/bdt action run <id> [args...]` | 执行调试动作 |
| `/bdt action info <id>` | 查看动作元信息 |
| `/bdt feat about <id>` | 查询调试特性状态 |
| `/bdt feat enable\|disable <id>` | 启用 / 禁用调试特性 |
| `/bdt list` | 列出全部已注册动作与特性 |
| `/bdt gui` | 打开配置界面 |

## 配置

配置界面可从 ModMenu 的「设置」按钮或 `/bdt gui` 进入，包含两个分类：

- **通用设置** —— 持久化到 `config/bdt.json`
- **调试特性** —— 持久化到 `config/bdt-features.json`

## 构建

```bash
sh ./gradlew build
```

Chunk Scanner 为编译期可选依赖，通过 `flatDir` 读取 `../chunkscanner/build/libs`
的本地构建产物，版本号自动复用其 `gradle.properties` 中的 `mod_version`。

## License

AGPL-3.0-only
