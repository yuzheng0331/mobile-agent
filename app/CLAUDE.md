# app

Android 外壳：`AgentService`（无障碍服务，实现 `core.run.Device`）和 `MainActivity`（任务列表）。逻辑尽量放 `core`，这里只做平台适配。

## 验证

- 电脑上没有单测，只能真机验证：构建 `:app:assembleDebug`，用文件管理器装 APK（不用 `adb install`），开无障碍，只用小号。
- 改了 `AgentService` 的行为，报告时附上 App 显示的停止原因和每步“找 / 动 / 等 / 查”耗时。

## 线程

- 无障碍事件在主线程到达；`Device` 的调用会阻塞（`awaitSettle` 轮询），只能在工作线程调（`MainActivity` 的单线程 executor）。
- `capture()` 把 `UiNode` 和真实的 `AccessibilityNodeInfo` 对应起来，`press()` 只对最近一次 capture 的节点有效。

## 不能动的

- manifest 不加 `INTERNET` 权限。
- `<queries>` 只列任务包会启动的 App；加任务包时一起加。
- 调试入口（广播接收器、电脑命令）只能进 debug 构建。
- 界面树、截图不写日志、不落盘。
