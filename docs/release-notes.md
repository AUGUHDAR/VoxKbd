# Vox Kbd 1.0.1 — Release Notes

> 把物理键盘，复制成无限套。
>
> Minecraft 客户端模组 · Fabric / Forge / NeoForge · 1.20 ~ 26.3 · Windows / Linux / macOS

## 1.0.1 改了什么

**从单版本单加载器，扩到 27 个包。** 1.0.0 只有 Minecraft 26.2 的 Fabric 一个包，1.0.1 覆盖三个加载器：

| 加载器 | 覆盖范围 | 包数 |
| --- | --- | --- |
| Fabric | 1.20 ~ 26.3 | 9 |
| Forge | 1.20 ~ 26.2（Forge 尚未发布 26.3） | 9 |
| NeoForge | 1.20.1 ~ 26.3 | 9 |

**分组的依据是 API 断点，不是版本号本身。** 同一个包里可以装下多个 Minecraft 版本，前提是这些版本之间没有破坏性变更。举两个例子：`1.20.2_1.20.6` 一个包服务 1.20.2 到 1.20.6；`1.21_1.21.5` 一个包服务 1.21 到 1.21.5 全部小版本。分成更细的包只会把同一份代码抄成多份，然后各自长 bug。

**修复：左右修饰键不再互相顶替。** Windows 钩子之前只读虚拟键码，左 Ctrl 和右 Ctrl 拿到同一个值，左 Alt 和右 Alt 也一样。现在读 `KBDLLHOOKSTRUCT.flags` 的 `LLKHF_EXTENDED` 位，左右分开识别。

**修复：切换键不再被自己的钩子吞掉。** 判定"这个键归模组所有"时，之前拿 Windows 虚拟键码去比对一张 GLFW 键码表，两边根本不是一套编号。按下 `` ` `` 或 `[` `]` 时有几率被钩子截走，切不出键盘。现在两边统一成 GLFW 键码。

**26.3 是新的输入后端。** Minecraft 26.3 移除了 GLFW，改用 SDL3。合成键不再走 GLFW 回调，改从 `KeyboardHandler#keyPress` 注入；窗口焦点从回调改成轮询；键码空间整体换成 SDL scancode。这条链路目前只做过编译验证，没有在真实 26.3 客户端上跑过。26.3 的包按 beta 对待。

## 安装

挑对应加载器和 Minecraft 版本的包，放进 `.minecraft/mods/`，启动游戏，按 `` ` `` 打开总控界面。配置文件在 `config/voxkbd.json`。

包里不含 Fabric API，Fabric 端需要自行安装。

## 已知限制

- 26.3 的按键注入链路未经实机验证，见上。
- 26.3 的 Android（FCL / ZL2）注入后端没有对应实现，该版本桌面端只走 Windows 钩子。
- 1.20 ~ 1.21.11 的包里带 JNA；26.x 用 JDK 内置的 FFM，不带。
- 暂时只有简体中文和英文两套翻译。

## 许可

见 `LICENSE`。

---

# Vox Kbd 1.0.0 — Release Notes

> 把物理键盘，复制成无限套。
>
> Minecraft 26.2 Fabric 客户端模组 · 单 JAR ≈ 107 KB · Windows / Linux / macOS / Android（FCL、ZL2）

## 它是什么

Vox Kbd 在游戏输入层新增一套系统级合成键 `VOXKBD_<虚拟键盘序号>_<物理键>`（如 `VOXKBD_1_W`），并在此之上开放“虚拟键盘”机制，让玩家在不改动原版按键系统的前提下，把同一套物理键映射出多套互相独立、可在原版按键设置中分别绑定的可绑定键码。

把虚拟键盘理解成“把物理键盘多复制几份”：每一份都是完整的 124 个物理键副本，可在原版按键设置中单独绑定。需要 200 个独立快捷键时，建 8 套虚拟键盘即可获得 992 个可绑定键码。

## 1.0.0 正式版特性

- **眼见为实锁模型**：总控 UI 红色键永远发原版键；非红色键激活虚拟键盘时发 `VOXKBD_*` 合成键。规则在原版按键设置抓键时也成立。
- **实时持久化**：每次锁 / 解锁 / 选项变更立刻写入 `config/voxkbd.json`，启动时也立即回写，磁盘与内存始终一致。
- **VOXKBD 永久跟随**：任何功能一旦绑定到合成键，对应物理键永久保持解锁，不受其他配置变更影响。
- **默认键盘常驻**：与原版基底层一致，不可删除、不可修改，是切换循环的常驻成员。
- **三个切换键 + 零枚举合成键**：原版按键设置列表只出现“上一个键盘”“下一个键盘”“打开 Vox Kbd”，不混入任何合成键条目。
- **单 JAR 进程内捕获**：桌面端通过 JDK Foreign Function & Memory API 直调 `user32` 钩子，零 JNA 依赖；Android 端复用 FCL / ZL2 的 GLFW 注入通道。

## 安装

把 `voxkbd-mod-1.0.0.jar` 放进 `.minecraft/mods/`，启动游戏，按 `` ` `` 打开总控界面。配置文件在 `config/voxkbd.json`。

## 构建

```
git clone https://github.com/AUGUHDAR/VoxKbd
cd VoxKbd
./gradlew :voxkbd-mod:build
```

26.2 的 fabric mappings 不在公共仓库，需要本仓库自带的本地 `com.voxcore.mappings`（`D:\voxkbd-local-maven`），见 `docs/功能文档.md` §5.1。

## 已知限制

- 仅支持 Minecraft 26.2，其它版本暂不兼容。
- 桌面端全局钩子首次启动需要 JVM 的 native access，Java 25 已默认开启。
- 暂无俄语、法语等小语种翻译，未翻译词条回退到英文。

## 许可

见 `LICENSE`。
