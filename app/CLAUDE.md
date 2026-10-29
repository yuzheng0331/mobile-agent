# app

Android 外壳：`AgentService`（无障碍服务，实现 `core.run.Device`）和 `MainActivity`（任务列表）。逻辑尽量放 `core`，这里只做平台适配。

## 验证

- 电脑上没有单测，只能真机验证：构建 `:app:assembleDebug`，`adb install -r` 覆盖安装（手机亮屏时点“允许”，覆盖安装不会关掉无障碍服务）或用文件管理器装，只用小号。不要 `am force-stop`：会关掉无障碍服务。
- 改了 `AgentService` 的行为，报告时附上 App 显示的停止原因和每步“找 / 动 / 等 / 查”耗时。

## 线程

- 无障碍事件在主线程到达；`Device` 的调用会阻塞（`awaitSettle` 轮询），只能在工作线程调（`MainActivity` 的单线程 executor）。
- `capture()` 把 `UiNode` 和真实的 `AccessibilityNodeInfo` 对应起来，`press()` 只对最近一次 capture 的节点有效。

## OCR 模型

- ONNX 由 `:app:fetchOcrModels` 下载：检测的放 `src/main/assets/ocr/`，识别的只放 `src/debug/assets/ocr/`（测速页和 core 的测试用）。识别用的 `src/main/assets/ocr/litert/*.tflite` 由 `tools/probe/ocr_litert_assets.sh <Python 3.12，装 onnx2tf[tensorflow]==2.6.9>` 生成。都不进 git；tflite 缺了构建会失败（`checkLiteRtModels`）。
- 日志“OCR 识别用 GPU / CPU”：GPU 不接时自动用 LiteRT 的 CPU。
- 改了 `LiteRtModels.CONFIG` 的尺寸，要同时改生成脚本里的尺寸。

## 输入法

- `AgentIme` 没有键盘、不读不打字，只为让进程按前台调度（vivo 后台只给 0–5 号核）。只在需要 OCR 的任务运行期间选中，结束切回原键盘（`AgentService.useFastCores`）；改动时保证任何退出路径都会切回。

## 后台模式（虚拟屏）

- `shell/ShellServer` 以 shell 身份运行（`app_process` 从已安装的 APK 启动），只做建虚拟屏、在上面启动 App、发返回键、给 Phone Agent 分配后台角色四件事，只接受我们 App 的 uid，App 进程一死它就退出。App 早于拿到 uid 的调用（连上就要角色）会等它拿到再处理。
- 谁来启动它：`shell/AdbLauncher` 通过本机的无线调试（Kadb 1.3.0 连 127.0.0.1），在无障碍服务连上时和跑后台任务前；配对码从通知输入（`shell/Pairing`）。开发时也可以用 `tools/probe/shell_start.sh`。Kadb 的 GPL 依赖 spake2-java 已排除，同名的 `com.flyfish233.crypto.spake2.Spake2Context` 转到 `core/adb/Spake2`：升级 Kadb 时先用 `javap` 核对它引用的签名没变。
- adb 密钥和证书在 `filesDir/adb/`：拿到它们就能拿到这台手机的 shell，不能写日志、不能导出。
- 三档，能用哪档用哪档（`Background`）：**角色模式**（持有 `COMPANION_DEVICE_APP_STREAMING` 角色，App 自己建屏、自己启动 App；重启后还在，覆盖安装更新后也还在（实测两次），丢了的话 shell 服务下次连上时自动重新分配）→ **shell 模式** → **前台**（勾了后台运行但两样都没有时，改在主屏跑并说明）。
- `AgentService.display` 不为 0 时，读树、截图、手势都在那块屏上；启动和返回走 shell 服务，角色模式下自己启动；“等稳定”只看那块屏的界面事件，toast 不按屏过滤。
- 虚拟屏上**绝不用全局返回**：它发给顶层焦点屏，而 `STEAL_TOP_FOCUS_DISABLED` 让焦点一直在主屏，会按到用户正在用的 App。角色模式没有返回键：每次任务结束销毁虚拟屏（`Background.release`，上面的 App 页面随之销毁），下次在新屏上从首页开始，不靠返回退回首页（试过 `FLAG_ACTIVITY_CLEAR_TASK`：微信窗口全黑、无障碍看不到）；但用户在主屏用过的 App 会带着停下的页面被拉过来，所以 `back` 先点文字或描述是“返回”/“Navigate up”的节点，找不到就点左上角 (20dp, 24dp)；画面变没变由 runner 比截图判断。返回图标不在那里的 App 以后在任务包里写位置。
- 目标 App 正在主屏前台时，后台的 `launch` 不启动它（会把它从用户手里拉走），返回 false，`MainActivity` 在停止原因里说明。
- 虚拟屏没有状态栏：它比主屏矮 `displayTop`（主屏状态栏高度），读到的坐标下移、点击的坐标上移这么多，任务包看到的始终是主屏坐标。
- 截图回调不能直接持有像素数组（系统会长时间留着回调对象，10 MB 一张的截图会撑爆堆），见 `screenshotOnce`。
- 后台运行不切加速输入法：用户可能正在主屏打字。

## 不能动的

- 不靠云端：自己的联网只有 App 更新、拉任务包、调任务包声明过的自有网站接口，以及启动器连本机 adbd（127.0.0.1），代码集中在一处；界面树、截图、OCR 文字不交给任何外部服务。`INTERNET` 权限目前只为启动器而加。
- `<queries>` 只列任务包会启动的 App；加任务包时一起加。
- 调试入口（广播接收器、电脑命令）只能进 debug 构建。
- 界面树、截图不写日志、不落盘。
