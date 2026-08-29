# Vox Kbd

为大型 Minecraft 整合包提供**可无限扩展的虚拟键盘层**的 Fabric 模组（详见 [`docs/功能文档.md`](docs/功能文档.md)，v0.4）。核心思路（决策 D2/D16）：在输入层**新增**系统级合成键 `VOXKBD_<虚拟键盘序号>_<物理键标识>`（如 `VOXKBD_1_W`），在不改动原版按键体系的前提下解决"按键不够用"的问题。

## 模块结构（对应 §5.1）

| 模块 | 形态 | 职责 | 平台 | 编译状态 |
|------|------|------|------|----------|
| `voxkbd-core` | 纯 Java（无 MC 依赖） | 命名规则、固定物理键序列表、键码分配表、配置 schema、JSON-line 协议、运行时输入状态 | 全平台 | ✅ javac 验证通过 |
| `voxkbd-daemon` | 纯 Java（被 mod `include`） | 捕获物理输入 → 按激活键盘+锁状态翻译；Windows 全局键盘钩子（WH_KEYBOARD_LL，经 **JDK 内置 FFM API** 直调 user32，无需任何原生绑定库）+ HeadlessCapture；现**进程内**随 mod 一起运行，不再独立成进程 | 仅桌面 | ✅ 已打包进单 JAR |
| `voxkbd-mod` | Fabric 模组 | 锁/切换管理、总控/配置 UI、i18n、配置持久化、桌面 Java 探测/缓存/守护进程监督、GLFW KeyBinding 注册、窗口聚焦监听 | 全平台 | ✅ 已构建为 MC 26.2 JAR（`jars/voxkbd-mod-0.1.0.jar`，loom 1.18.0-alpha.16 + Gradle 9.7.0 + JDK 25） |
| `voxkbd-mixin` | Fabric mixin（安卓） | 包住 MC `glfwSetKeyCallback`，进程内生成 `VOXKBD_*` 合成键事件注入 GLFW | 仅安卓 | ✅ javac 验证通过 |
| `voxkbd-fclbridge` | 安卓辅助 | 反射调用 FCL/ZL2 原生 `GLFW_invoke_Key` / `CallbackBridge.sendKeycode`（类加载器隔离安全，D6） | 仅安卓 | ✅ javac 验证通过 |

## 关键工程决策：单 JAR 进程内投递（玩家只需一个文件）

规格 D16 要求合成键注册为 GLFW **扩展键码（400+）**，而独立进程无法向 MC 的 GLFW 注入 400+ 键码（OS 钩子只能产生真实键码范围内的按键）。因此本模组**不做守护进程拆分**：所有逻辑打包进**一个** Fabric JAR，玩家直接丢进 `mods` 文件夹即可，无需下载任何第二个文件。

- **默认基底层（D20）**：游戏启动时激活层就是**原版**——所有按键与未装模组时完全一致，走路/操作零影响；只有玩家主动切换才进入虚拟键盘。
- **原版控制界面只注册三个绑定（§3.3）**：上一个键盘 / 下一个键盘 / 打开 Vox Kbd，默认键位 `[` / `]` / `` ` ``（均为原版从不占用的键，可在原版按键设置中修改）。合成键 `VOXKBD_*` 绝不出现在按键控制列表里。
- **未锁的键**：mod 自己在进程内安装 Windows 低层键盘钩子（WH_KEYBOARD_LL，经 JDK 内置 FFM API 调 user32），捕获并**抑制**原生按键，按激活虚拟键盘直接以 `VOXKBD_<kb>_<phys>`（400+）投递给 MC 的 GLFW（经 `GlfwDeliverer` 回调注入，满足 D16）。投递经 MC 主线程队列（`Minecraft.execute`）编排，保证与原版一致的线程模型。
- **锁住的键**：钩子不抑制，原生键直送 MC 按原版处理（满足"锁定键仍可用"）。
- **聚焦门控（D7）**：MC 窗口失焦时钩子不捕获，键照常归系统。
- **屏幕门控**：任意 MC 界面（聊天/背包/菜单/按键设置）打开期间，钩子一律原生放行——GUI 打字、菜单操作、按键绑定永不误劫持。
- **保留键**：ESC 与八个物理修饰键（Ctrl/Alt/Shift/Win 左右键）永不被系统级捕获抑制（ESC 在 MC 中硬编码于 KeyMapping 之外；修饰键被抑制会破坏 AltGr 打字、系统快捷键与组合键前缀本身）。
- **组合键+数字开箱即用（D8/D9）**：即使数字键被默认锁覆盖（1–9 是原版快捷栏绑定），按住前缀（默认 `Ctrl+Alt`）按数字仍会触发切换；`Ctrl+Alt+0` 随时切回原版基底层。
- **合成键按需出现**：全新安装的控制界面只有三个 Vox Kbd 条目；玩家在配置界面点「添加」创建某套虚拟键盘的那一刻，该套的 `VOXKBD_*` 合成键才注册进原版按键控制（可正常绑定动作）。删除键盘只移除配置项、不注销已注册的绑定（D19，防误删不可挽回）。
- **安卓（FCL/ZL2）**：mixin 重定向 26.2 的回调注册点 `InputConstants.setupKeyboardCallbacks`（26.2 已无 `Window.setKeyCallback`），在进程内完成捕获+翻译+GLFW 投递；mod 启动时经反射把共享 `InputState` 绑定给 mixin。mixin 配置为软失败（`defaultRequire=0`），任何注入失配只告警不崩溃。
- **配置热更新（D14）**：外部编辑 `voxkbd.json` 后合并回启动时的同一 Config 实例，锁/切换管理器与 UI 无需重接线即可看到新值。
- `voxkbd-daemon` 模块仍以纯 Java 实现捕获/翻译管线，仅作为被 mod `include` 进单 JAR 的库；其独立 `fatJar` 任务可生成可选的可执行 jar，但**模组运行不需要它**（真正的单文件交付物就是模组 JAR 本身）。

## 构建

```bash
./gradlew build                 # 全量构建（wrapper 使用 Gradle 9.7.0；MC 26.2 需 JDK 25）
./gradlew :voxkbd-mod:build     # 仅构建单文件交付物：voxkbd-mod/build/libs/voxkbd-mod-0.1.0.jar
```

**交付物（玩家唯一需要的文件）**：`jars/voxkbd-mod-0.1.0.jar`（**约 0.1 MB，零第三方依赖**）—— 已内嵌 `voxkbd-core` / `voxkbd-daemon` / `voxkbd-mixin` / `voxkbd-fclbridge`（全部位于 `META-INF/jars/` 并登记在 `fabric.mod.json` 的 `jars` 数组）。Windows 钩子通过 MC 26.2 必备的 Java 25 运行时自带的 FFM API（`java.lang.foreign`）调用 user32，因此**不再打包 JNA 等任何原生绑定库**；未启用 `--enable-native-access` 时 JVM 仅打印一次告警、功能不受影响。直接放进 `.minecraft/mods/` 即可，不需要任何第二个文件。

- `voxkbd-core` / `voxkbd-daemon` 为纯 Java，可在无 MC 环境下独立编译/运行。
- `voxkbd-mod` / `voxkbd-mixin` / `voxkbd-fclbridge` 需 Fabric Loom + 目标 MC 版本（规格 D1 目标为 **Java 版 26.2**）。26.2 的映射由本地 Maven 仓库 `D:\voxkbd-local-maven` 中的 `com.voxcore.mappings:26.2` 提供（公共 Fabric maven 暂无 26.2 映射）；该映射的 named 命名空间即 Mojang 官方名称，故 mod 源码以 Mojang 官方名书写。详见 `gradle.properties`。

### 本地验证（无需 Gradle / MC）

纯 Java 模块已用 `javac` + `libs/` 下的预下载 jar 验证：

```bash
# core
javac -cp "libs/gson-2.11.0.jar" -d build-core $(find voxkbd-core/src/main/java -name "*.java")
# daemon（FFM 全局钩子，无外部依赖）
javac -cp "libs/gson-2.11.0.jar;build-core" -d build-daemon $(find voxkbd-daemon/src/main/java -name "*.java")
# mixin + fclbridge（含 LWJGL GLFW + Mixin 注解）
javac -cp "libs/gson-2.11.0.jar;build-core;libs/lwjgl-3.3.4.jar;libs/lwjgl-glfw-3.3.4.jar;libs/mixin-0.8.5.jar" \
      -d build-mixin $(find voxkbd-mixin/src/main/java voxkbd-fclbridge/src/main/java -name "*.java")
```

验证脚本（`build-smoke/`）：

- `Smoke.java` —— core 配置/翻译逻辑 24 项断言（零键盘起步、组合键掩码换算、原版层直通、锁/聚焦翻译策略、热更新合并），全部通过。
- `HookLiveTest.java` —— **本机 Windows 实测**：FFM `SendInput` 注入按键，断言钩子捕获按下/抬起并系统级抑制、锁定键原生直通、干净卸载；带与不带 `--enable-native-access` 均为 LIVE ALL PASS。
- `DiagHook.java` —— FFM 裸调诊断（SetWindowsHookExW 安装/卸载 + GetLastError）。

## 配置（JSON，决策 D11/D14）

配置文件 `voxkbd.json`（见 `voxkbd-core` 的 `Config` schema）：Java 路径缓存、聚焦门控、原版锁跟随、切换设置（上一/下一/打开配置 UI、组合键前缀、多位数字防抖）、虚拟键盘列表（显示名可资源包覆盖，D15）、键码分配参数、动态锁集合（运行时生成，绝不硬编码，§3.4）。

## 路线图进度

- **阶段 0 脚手架**：✅ Gradle 多模块 + wrapper + 文档对齐。
- **阶段 1 核心架构与通信骨架**：✅ core + daemon（探测/缓存/降级；单 JAR 模式下为进程内管线）。
- **阶段 2 虚拟键盘与切换**：✅ 切换管理 + 组合键多位数字（D8/D9）+ 聚焦门控（D7）+ 屏幕门控。
- **阶段 3 锁系统**：✅ 默认锁 / 原版锁跟随 / UI 单键锁（红背景，左右键）逻辑 + 未保存强制锁（D18）。
- **阶段 4 总控/配置 UI + i18n**：✅ 全部 UI 文案走语言文件（en_us / zh_cn，含全物理键标签），无硬编码（§3.8）；键盘增删改、初始页常驻显示。
- **阶段 5 通知与多版本验证**：⬜ 已实现动作条/霸屏/Title 提示与时长配置；仍需在真实 MC 26.2 运行环境下回归测试（Windows 钩子实测、安卓 FCL/ZL2 实测）。

> 说明：本仓库按"方案级"实现（§5），部分参数（如 `VOXKBD_BASE`/`STRIDE`、固定物理键序列表内容）在对接真实 LWJGL/MC 时可微调，不阻塞设计。
