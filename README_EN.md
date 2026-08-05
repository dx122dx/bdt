# Billy's Mod infrastructure

A general-purpose **Minecraft 1.20.1 Fabric client-side** infrastructure mod (mod id: `billy-inf`).

It does not bind to any specific "target mod". Instead, it provides a **module framework** with a unified command tree, config system, and extension points. The built-in submodule today is the **debugger** — an extensible framework of debug actions (Actions) and toggleable, persisted debug features (Features) that you and other mods can attach as "builtin packs".

> Design philosophy: keep infrastructure decoupled from business logic. Debug tooling for a specific mod is injected by **that mod** (or a companion mod) through the `billy-inf:debugger` extension point, and is silently skipped when the target mod is absent — never slowing down or blocking game startup.

- Package: `com.billy65536.infrastructure`
- Main class: `InfrastructureMod`
- Author: billy65536
- Repository: <https://github.com/dx122dx/billy-inf>
- License: **AGPL-3.0-only**
- Environment: client-only | Java 17 | MC 1.20.1

---

## Table of Contents

- [Features](#features)
- [Installation](#installation)
- [Command Overview](#command-overview)
- [Module Framework](#module-framework)
- [debugger Submodule](#debugger-submodule)
- [Configuration](#configuration)
- [Extending: Inject a Debug Pack for Your Mod](#extending-inject-a-debug-pack-for-your-mod)
- [Building & Development](#building--development)
- [Dependencies & Compatibility](#dependencies--compatibility)
- [License](#license)

---

## Features

- **Module framework**: organize functionality behind the `IModule` interface with Java SPI auto-discovery — add a module without touching startup code.
- **Unified `/inf` command tree**: every module's commands, config access, and info plug into the `/inf` root, with full tab-completion.
- **Debugger**:
  - **Debug Actions** — one-shot operations (e.g. set a state, trigger a flow).
  - **Debug Features** — toggleable, persisted capabilities (e.g. unlock a restriction, disable an apply path), kept across restarts.
  - All identified by `Identifier`, with `run / info / about / enable / disable / list`.
- **Dual-track config**:
  - Fixed options (verbose logging, failure stack traces) go through AutoConfig → `config/billy-inf.json`.
  - Dynamic feature toggles go through a separate Gson store → `config/billy-inf-features.json` (kept apart from AutoConfig so dynamic fields aren't dropped).
- **Resilience first**: any exception during module load, builtin-pack registration, or action execution is caught and **never** blocks game startup or bubbles into the command system.
- **Optional ModMenu integration**: provides a "Settings" entry; everything works without it.

---

## Installation

1. Download `billy-inf-<version>.jar` from the releases page.
2. Drop it into `.minecraft/mods/`.
3. Make sure **Fabric Loader ≥ 0.19.3**, **Fabric API**, and **Cloth Config** are installed at runtime (**ModMenu** is optional).
4. Launch the game and run `/inf info` to verify.

> Want Chunk Scanner debug items? Also install [`cs-dbg`](https://github.com/dx122dx/cs-dbg) (mod id `csdbg`) — it auto-injects its debug pack when Chunk Scanner is present, and skips silently when it isn't.

---

## Command Overview

Root command is `/inf` (also `/billy-inf:inf`).

| Command | Description |
| --- | --- |
| `/inf info [moduleId]` | Show mod / module version, description, contributed commands & config paths |
| `/inf config get <module> <path>` | Read a module config value |
| `/inf config set <module> <path=value> [path=value ...]` | Set config values (multiple, space-separated) |
| `/inf config reset <module> <path>` | Reset a config value to default |
| `/inf dbg action run <id> [args...]` | Execute a debug action |
| `/inf dbg action info <id>` | Show action metadata (name / description) |
| `/inf dbg feat about <id>` | Query a debug feature's current state |
| `/inf dbg feat enable\|disable <id>` | Enable / disable a debug feature |
| `/inf dbg list` | List all registered actions & features |
| `/inf dbg gui` | Open the config screen |

All arguments support prefix completion; config `path` and `value` support hierarchical drill-down completion.

### Examples

```text
# Show debugger module details and all config paths
/inf info debugger

# Open the debug config screen
/inf dbg gui

# List all current debug actions / features
/inf dbg list

# Run a debug action with arguments
/inf dbg action run billy-inf:unlock_all

# Check a feature's state and enable it
/inf dbg feat about billy-inf:disable_apply_all
/inf dbg feat enable billy-inf:disable_apply_all
```

---

## Module Framework

The core of `billy-inf` is a lightweight module framework in the `core` package:

- **`IModule`**: the module extension point. Implement `getId()` / `getVersion()` / `getName()` / `getDescription()` (required), plus optional `onInitializeModule()` / `getConfig()` / `saveConfig()` / `buildCommands()` / `getCommandLiterals()`. "Declare all capabilities at once" — the registry and command registrar take over uniformly.
- **`ModuleRegistry`**: a static singleton registry. Two ways to register:
  - Explicit: `ModuleRegistry.register(module)`;
  - Automatic: `ModuleRegistry.discover()` scans `META-INF/services/com.billy65536.infrastructure.core.module.IModule` via Java SPI (currently only `DebuggerModule`).
  - If any module fails to initialize, it is logged and skipped — other modules are unaffected.
- **`ModuleCommandRegistrar`**: mounts a module's command subtree to the `/inf` root at registration time; the root command only consumes the registration results and never iterates modules itself.

Adding a module only requires: implement `IModule` + append one line to the services file — **no startup-code changes needed**.

---

## debugger Submodule

`debugger` is the only module today, providing two debug extension points:

### Debug Action (`IDebugAction`)

- A one-shot operation. Registered in `ActionRegistry` and triggered via `/inf dbg action run <id> [args...]`.
- Implement `execute(MinecraftClient, String[])` and optional `suggest(client, args)` (argument completion).
- Exceptions are caught by the command layer and turned into red chat feedback; with "Show Stack Trace" on, up to 5 frames are appended.

### Debug Feature (`IDebugFeature`)

- A toggleable, persisted capability. Registered in `FeatureRegistry` and operated via `/inf dbg feat about|enable|disable <id>`.
- `isDefaultEnabled()` decides the initial state when no record exists (default **false** — debug features are off by default).
- `onEnable()` / `onDisable()` callbacks must be idempotent; they mount/unmount event listeners or render hooks.
- Enabled state is persisted by `FeatureStateStore` to `config/billy-inf-features.json` and survives restarts.

### Builtin Packs

**This mod ships no target-specific debug items itself** (`BuiltinsManager.PACKS` is currently empty). Debug packs are injected by **external mods** through the extension point:

- An external mod implements `DebuggerBuiltinProvider` and registers a custom entrypoint `"billy-inf:debugger"` in its `fabric.mod.json`;
- In `contribute(contributor)`, it calls `contributor.add(requiredModId, displayName, XxxBuiltins::register)`;
- `billy-inf` only runs `register` when `requiredModId` is loaded, otherwise the whole pack is skipped.

Builtin packs use **lazy class-loading isolation**: the framework only references each pack's *entry class* method reference, never holding target-mod types in the constant pool, so "skip when target is absent" truly holds. Example: Chunk Scanner's debug pack lives in the separate mod [`cs-dbg`](https://github.com/dx122dx/cs-dbg).

---

## Configuration

The config screen is reachable from ModMenu's "Settings" button or `/inf dbg gui`, with two categories:

| Category | Content | File |
| --- | --- | --- |
| General | Verbose logging, show stack trace on failure | `config/billy-inf.json` (AutoConfig) |
| Debug Features | Per-feature toggles (decided at runtime) | `config/billy-inf-features.json` (Gson) |

> Note: the two configs **must not be merged** into one file — AutoConfig deserializes against a static class structure and silently drops unknown fields; dynamic feature toggles are therefore persisted separately, and the store keeps "persisted but currently unregistered" entries so registration-order changes don't silently reset user toggles.

Initialization order (see `DebuggerModule.onInitializeModule`):

1. `DebuggerConfigLoader.register()` (AutoConfig registration)
2. `FeatureStateStore.load()` (feature state must load before any feature registers)
3. `BuiltinsManager.registerAll()` (collect and register builtin packs)
4. `InfrastructureCommands.register()` (command registration, depends on the module registration above)

---

## Extending: Inject a Debug Pack for Your Mod

Your mod only needs `modImplementation billy-inf` (required at runtime) and implements the extension point:

```java
public final class MyBuiltins implements DebuggerBuiltinProvider {
    @Override
    public void contribute(Contributor contributor) {
        contributor.add(
            "your-mod-id",                       // target mod id; skipped if not loaded
            "Your Mod debug pack",               // log display name
            MyBuiltins::register                 // entry-class method reference (lazy load)
        );
    }

    // Called only when your-mod-id is present
    public static void register() {
        ActionRegistry.register(new MyAction());
        FeatureRegistry.register(new MyFeature());
    }
}
```

Register the entrypoint in `fabric.mod.json`:

```json
{
  "entrypoints": {
    "billy-inf:debugger": ["com.example.mymod.MyBuiltins"]
  }
}
```

Constraints:

- `register` must be a **separate entry class** method reference — do not use a lambda closure that holds target-mod types, or lazy loading breaks.
- Use your own namespace for action/feature ids (e.g. `your-mod-id:foo`); don't occupy `billy-inf`.

---

## Building & Development

```bash
sh ./gradlew build
```

Artifacts land in `build/libs/billy-inf-<version>.jar`.

- Version is `mod_version` in `gradle.properties`, format `date.sequence` (e.g. `20260805.17`).
- Compiled at Java 17 (`--release 17`).
- Dependencies resolve via `flatDir` / Maven repositories; Fabric API and Cloth Config are required at runtime, `modmenu` is compile-time optional.

---

## Dependencies & Compatibility

| Dependency | Requirement | Notes |
| --- | --- | --- |
| fabricloader | `>=0.19.3` | required |
| minecraft | `~1.20.1` | required |
| java | `>=17` | required |
| fabric-api | `*` | required |
| cloth-config | `*` | required (config & GUI) |
| modmenu | `*` | optional (settings entry) |

Environment: `client` (client only).

---

## License

Released under **AGPL-3.0-only**. Source code: <https://github.com/dx122dx/billy-inf>.
