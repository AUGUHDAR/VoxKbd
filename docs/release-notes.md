# Vox Kbd 1.0.0 — Release Notes

> 把物理键盘，复制成无限套。
>
> Minecraft 26.2 Fabric 客户端模组 · 单 JAR ≈ 107 KB · 跨平台（Windows / Linux / macOS / Android via FCL、ZL2）

## 它是什么

Vox Kbd 在游戏输入层新增一套系统级合成键 `VOXKBD_<虚拟键盘序号>_<物理键>`（如 `VOXKBD_1_W`），并以此为基础向玩家开放“虚拟键盘”机制，使玩家能够在不改动原版按键系统的前提下，把同一套物理键映射出多套互相独立、可在原版按键设置中分别绑定的可绑定键码。

可以把虚拟键盘理解为“把物理键盘复制多份”：每一份都是完整的 124 个物理键副本，可在原版按键设置中单独绑定到任意功能。需要 200 个独立快捷键时，创建 8 套虚拟键盘即可获得 992 个可绑定键码。

## 1.0.0 正式版特性

- **眼见为实锁模型**：总控 UI 红色键 = 永远发原版键；非红色键 = 激活虚拟键盘时发 `VOXKBD_*` 合成键。规则在原版按键设置抓键时也完全一致。
- **实时持久化**：每次锁 / 解锁 / 选项变更立即写入 `config/voxkbd.json`，启动时也立即回写，磁盘与内存永不出现抽奖式偏差。
- **VOXKBD 永久跟随**：任何功能一旦绑定到合成键，对应物理键永久保持解锁，不受其他配置变更影响。
- **默认键盘常驻**：与原版基底层完全一致，不可删除、不可修改，是切换循环的常驻成员。
- **三个切换键 + 零枚举合成键**：原版按键设置列表只出现“上一个键盘”“下一个键盘”“打开 Vox Kbd”，不混入任何合成键条目。
- **单 JAR 进程内捕获**：桌面端通过 JDK Foreign Function & Memory API 直调 `user32` 钩子，零 JNA 依赖；Android 端复用 FCL/ZL2 的 GLFW 注入通道。

## 安装

将 `voxkbd-mod-1.0.0.jar` 放入 `.minecraft/mods/`，启动游戏，按 `` ` `` 打开总控 UI。配置文件位于 `config/voxkbd.json`。

## 构建

```
git clone https://github.com/AUGUHDAR/VoxKbd
cd VoxKbd
./gradlew :voxkbd-mod:build
```

注意：26.2 的 fabric mappings 不在公共仓库，需要使用本仓库自带的本地 `com.voxcore.mappings`（`D:\voxkbd-local-maven`）。详见 `docs/功能文档.md` §5.1。

## 已知限制

- 仅支持 Minecraft 26.2，其他版本暂不兼容。
- 桌面端全局钩子首次启动需要 JVM 的 native access，Java 25 已默认开启。
- 暂无俄语、法语等小语种翻译，未翻译的词条会回退到英文。

## 许可

见 `LICENSE`。
