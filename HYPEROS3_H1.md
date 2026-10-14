# txtoi · HyperOS 3 实验分支 H1

基于 [TheKingBucket001/txtoi](https://github.com/TheKingBucket001/txtoi) v0.6.2 / `e575406` 的独立兼容分支。

> **测试版，不是官方版。** 仅验证 Android 16 / HyperOS 3 的系统查询路径和原生 TextView 菜单控制；不保证所有应用生效，也不保证菜单最终排序位置。

## 本次改动

- 小米 / Redmi / POCO：优先 Hook Android 16 的 `ComputerEngine.queryIntentActivitiesInternal`，发生接口不兼容时退回原版公开查询入口。
- 新增 `HyperOsMenuClassifier`，避免在 HyperOS 中使用 ColorOS 的 `ExtLoader` 和厂商私有分类接口。当前排序只调整 PROCESS_TEXT 查询层，可能被最终工具栏再次排序。
- 新增应用侧 `Editor$TextActionModeCallback` 菜单隐藏，对八种系统菜单 ID 控制显示：复制、剪切、粘贴、全选、分享、纯文本粘贴、撤销、重做。
- 独立包名 `io.github.selectionmenucontrol.hyperos3`，不会覆盖原版应用。设置存储复用原版 PROCESS_TEXT 键，新系统按钮使用另外的 `Settings.Global` 键。
- 保留原版 ColorOS Hook，新增测试构建工作流。

## GitHub Actions 获取 APK

在本分支打开 **Actions → HyperOS 3 debug APK → 最新运行记录 → Artifacts**，下载 `txtoi-hyperos3-h1-debug-apk`，解压得到 `app-debug.apk`。

构建通过不代表真机适配成功；请不要在没有回滚手段的情况下安装系统级 Hook 测试版。

## 安装和测试

1. 在 LSPosed 关闭原版 txtoi 的 Hook，避免两套模块同时修改文字菜单。
2. 安装测试 APK；开启模块，作用域至少勾选 `system`。需要测试“复制/分享”等内置操作时，另外勾选具体目标应用（如 `com.android.settings`）。
3. 重启手机。先测试菜单扩展项隐藏与恢复，再测试排序；最后测试一个系统操作开关（建议先试“分享”）。
4. **应用侧支持范围有限**：原生 TextView/EditText 可能生效；WebView/Compose/自绘 ActionMode 不保证生效。
5. 查看日志：`adb logcat -d -v time -s SelectionMenuControl:D AndroidRuntime:E`。
6. 如果有问题，先在 LSPosed 禁用测试模块并重启。内置操作配置可通过 Root 清除：`settings delete global selection_menu_control_builtin_v1`。

## 需要特别留意

- 分类器不再因 ColorOS 接口缺失直接禁用拖动，但不能证明拖动后的最终菜单顺序一定改变。
- 系统操作隐藏只匹配标准 `android.R.id.*`，不根据按钮名称做危险的模糊匹配。
- `Settings.Global` 在第三方应用进程中是否允许读取，需要根据 HyperOS 3 真机日志验证；读取不到时默认保持原菜单。
- 不采集、不保存选中文字内容，不修改系统 APK。
- `system_server` Hook 存在风险，建议随时保留 LSPosed 的模块禁用方法。

完整反馈最好包含：系统版本、LSPosed 版本、出现问题的应用包名、菜单前后截图、`SelectionMenuControl` 日志。
