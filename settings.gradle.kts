rootProject.name = "VoxKbd"

// Vox Kbd multi-module build (see docs/功能文档.md §5.1)
//   voxkbd-core      - loader-agnostic shared library (naming, keycode table, config schema, protocol)
//   voxkbd-daemon    - standalone desktop Java process (input capture -> translate -> OS injection)
//   voxkbd-mod       - Fabric mod (lock/switch mgmt, UI, i18n, config, java detection, keybinding reg)
//   voxkbd-mixin     - Fabric mixin (Android GLFW injection)
//   voxkbd-fclbridge - optional Android FCL/ZL2 native bridge helper
include("voxkbd-core")
include("voxkbd-daemon")
include("voxkbd-mod")
include("voxkbd-mixin")
include("voxkbd-fclbridge")
