# BetterGI 调研：提速方式与扩展机制

日期：2026-10-24。源码：本机克隆 `C:\Users\R_Crusoe\AppData\Local\Temp\bgi`，commit `4118e28`（浅克隆，只有一条提交，看不到历史提交信息）。下文路径都相对这个目录。只读了静态源码和仓库内文档，没有运行程序，所以文中耗时数字都来自它自己的代码注释或文档。凡是源码里找不到的，标 **未确认**。

## 0. 结论先行

1. **BetterGI 的“快”主要靠少做事，不靠更强的模型。** 固定 50 ms 节拍，截图线程只保留最新一帧；先用便宜的模板匹配，命中后才跑 OCR，位置已知时只跑识别、不跑检测；ROI 尽量小；同一帧内缓存灰度图。OCR 默认强制 CPU，启动时预热一次。
2. **任务流程本身并不快。** 任务代码里有 754 处写死毫秒数的 `Sleep/Delay`，“等元素出现/消失”只有 92 处，而且这类等待是按固定间隔轮询（250 ms 或 1000 ms，第一次检查前先睡一个间隔）。没有“画面稳定”这类事件驱动的等待。手机端的 `waitForSettle` 已经比它好，等待策略不要照搬。
3. **它的扩展方式是“代码封闭、数据开放”，不是插件系统。** 触发器、动作处理器、识别类型都是 C# 里写死的列表或 `switch`。社区能贡献的是数据（识别 JSON、战斗策略文本、地图追踪 JSON、键鼠录制 JSON）和受限 JS 脚本。分发方式是一个中央 git 仓库加镜像，用户按路径订阅，启动时自动更新。
4. **LLM 只在运行前用。** AutoCombo 让 LLM 通过 function calling，从一份白名单动作目录里逐节点搭行为树；运行时按 35 ms 节拍确定性地 tick 这棵树。和此前粗读的结论一致。
5. **对手机 v1 最值得借鉴的三点：** (a) 微信 OCR 走“版面规则先定位，裁剪后只跑识别”；(b) 任务包里的找法、动作、预期做成“名字 → 内置实现”的封闭注册表，未知名字在校验阶段报错；(c) 任务包、录制与补教数据都带“最低 App 版本 / 测试过的目标 App 版本”字段。详见第 3 节。

---

## 1. 问题 1：低延迟、高频识别是怎么做到的

### 1.1 截图层

| 项 | 做法 | 证据 |
|---|---|---|
| 可选方式 | 5 种：BitBlt、DwmGetDxSharedSurface、WGC、WGC HDR、WGC V2。**没有 DXGI Desktop Duplication**（全仓搜不到 `DuplicateOutput`/`OutputDuplication`） | `Fischless.GameCapture/CaptureModes.cs:5-26`，`GameCaptureFactory.cs:48-59` |
| 默认 | BitBlt；配置解析失败也回退 BitBlt | `BetterGenshinImpact/Core/Config/AllConfig.cs:44`；`GameTask/Runtime/Win32/Win32RuntimeProvider.cs:101-112` |
| 截的是窗口，不是屏幕 | BitBlt 用 `GetDC(hWnd)`；WGC 用 `CreateItemForWindow`；DWM 用 `DwmGetDxSharedSurface(hWnd)`。因此窗口被遮挡也能截（最小化除外） | `BitBlt/BitBltSession.cs:66`；`Graphics/GraphicsCapture.cs:66`；`DwmSharedSurface/SharedSurfaceCapture.cs:35-37,99` |
| WGC：推帧、只留最新帧 | `FrameArrived` 回调里把帧转成 Mat 存进 `_latestFrame`，消费方 `Capture()` 只克隆最新帧。帧池 2 个缓冲。新帧构造成功后才替换，失败时保留旧帧 | `Graphics/GraphicsCapture.cs:89-103,196-268,254-257,270-286` |
| 帧率上限 | WGC 在回调里丢掉间隔小于 16 ms 的帧，注释写“限制最高处理帧率为 62fps” | `Graphics/GraphicsCapture.cs:213-218` |
| WGC V2 的优化（带实测注释） | 在帧到达时用 draw 写入自有纹理，注释写“实测：消费时刻直接从跨进程表面回读会固定等待一个 DWM 周期（~20ms/帧），到达时写入则无此等待”；GPU 上把 BGRA 打包成 BGR24，“回读量 8.3→6.2MB -25%”；没有新帧时直接返回缓存副本，“零 GPU/PCIe 回读”；可设 `MinUpdateInterval` 把 DWM 推帧率降到识别需要的频率（Win11 24H2+） | `Graphics/GraphicsCaptureV2.cs:49-51,70-73,75,112-114,139-145,182-183,625-633`；配置 `AllConfig.cs:64-69` |
| BitBlt：拉取 | 每次 `Capture()` 同步拷贝。窗口尺寸每 1000 ms 才检查一次；失败后重建会话，重试一次 | `BitBlt/BitBltCapture.cs:139-142,163-178` |
| 截图失败 | 业务侧再重试 3 次，每次间隔 30 ms | `GameTask/Common/TaskControl.cs:286-314` |
| 后台 | 截图不需要前台；但游戏不在前台时，只运行声明了 `IsBackgroundRunning` 的触发器，没有这样的触发器就跳过这一帧，不截图 | `GameTask/TaskTriggerDispatcher.cs:367-408` |

### 1.2 触发器调度（`TaskTriggerDispatcher`）

- **节拍：** `System.Timers.Timer`，间隔默认 50 ms（`AllConfig.cs:59-62` `_triggerInterval = 50`），由 `GameRuntimeService` 启动时传入（`GameTask/Runtime/GameRuntimeService.cs:256`；`TaskTriggerDispatcher.cs:226-250`）。设计文档也写“每 50ms 执行一次 Tick：截一帧图，然后按优先级依次调用各触发器的 `OnCapture`”（`Docs/design/realtime-trigger.md:15`）。
- **不排队、不堆积：** Tick 一开始 `Monitor.TryEnter`，上一帧还没跑完就直接放弃这一帧，并计一次 “skipped tick”（`TaskTriggerDispatcher.cs:302-309`）。所以识别慢时帧率自动降下来，不会越积越多。
- **独立任务推理时暂停：** `PauseForInference()` 计数大于 0 时 Tick 直接返回（`TaskTriggerDispatcher.cs:39-44,310`）。推理测速也用这把锁，先暂停自动识别再测（`Core/Recognition/ONNX/InferenceBenchmarkCoordinator.cs:12-21`）。
- **触发器列表写死，按优先级排序：** `GameTaskManager.CreateTriggers()` new 出 9 个触发器，再按 `Priority` 降序排（`GameTask/GameTaskManager.cs:28-44`）。优先级：Placeholder/Test 9999、GameLoading 999、AutoPick 30、AutoEat 25、QuickTeleport 21、AutoSkip 20、AutoFishing 15、SkillCd 10、MapMask 1（grep `Priority =>`；表格见 `Docs/design/realtime-trigger.md:17-27`）。
- **每帧决定谁跑：** 不在任务中时看用户配置 `IsEnabledByConfig`；任务中只运行通过 `AddTrigger` 租用的触发器。状态变化时回调 `OnEnabled/OnDisabled`（`TaskTriggerDispatcher.cs:545-626`；接口说明 `GameTask/ITaskTrigger.cs:5-66`）。脚本只能租用 AutoPick、AutoSkip、AutoEat 三个（`TaskTriggerDispatcher.cs:59-64,172-186`）。
- **独占：** 有一个触发器 `IsExclusive`（例如钓鱼界面）时，本帧只跑它（`TaskTriggerDispatcher.cs:472-487`）。
- **按界面分类过滤：** 每帧先判断当前界面（对话 / 大地图 / 未知，`GameTask/Common/BgiVision/BvStatus.cs:56-69`）。界面刚变化后的 30 s 内所有触发器都跑；之后只跑 `SupportedGameUiCategory` 与当前界面一致的（`TaskTriggerDispatcher.cs:492-510`）。
- **每帧固定开销：** 截图、`CaptureContent`（把截图缩放到 1080P 坐标系，`GameTask/CaptureContent.cs:91-99`）、聊天界面检测 `Bv.DetectChatUi`（`TaskTriggerDispatcher.cs:465`），之后才轮到触发器。
- **手动 GC：** 每 2 s 调一次 `GC.Collect()`（`TaskTriggerDispatcher.cs:521-525`）。
- **触发器与任务分工：** 接口注释写明“需要短时间内持续循环获取游戏图像的，使用触发器；需要休眠等待且有一定流程的，应自行实现 Task”（`ITaskTrigger.cs:10-11`）。
- **生效延迟：** 开关触发器“下一帧生效，不超过 50ms”（`Docs/design/realtime-trigger.md:600`）。

### 1.3 识别对象：模板、颜色、OCR、ROI、多分辨率、缓存

- **识别类型：** `TemplateMatch`、`ColorMatch`、`OcrMatch`、`Ocr`、`ColorRangeAndOcr`、`Detect`（YOLO）（`Core/Recognition/RecognitionTypes.cs:3-12`）。
- **识别对象带 ROI 和参数：** `RegionOfInterest`；模板阈值默认 0.8、默认单通道、`CCoeffNormed`；可选掩码（纯绿色当透明）、二值化匹配、最大匹配数；OCR 的替换字典、包含 / 正则匹配（`Core/Recognition/RecognitionObject.cs:24,64-115,198-222`）。
- **ROI 真的裁小再匹配：** `ImageRegion.Find` 先算出有效 ROI，`new Mat(roi, effectiveRegionOfInterest)` 再做 `MatchTemplate`（`GameTask/Model/Area/ImageRegion.cs:119,141-182`）。
- **多分辨率：** 统一换算到 1080P 坐标系（`CaptureContent.cs:98` `DeriveTo1080P()`）。素材按 `Assets\{宽}x{高}` 目录找，找不到就用 `1920x1080` 并按比例缩放（`GameTaskManager.cs:65-90`）。参考画布机制把 `ReferenceImageSize/ReferenceBoundingBox` 换算成当前截图上的搜索框，宽高比不同时按锚点贴边（`GameTask/Model/Area/ImageRegionReferenceSearchHelper.cs:11-14,85-88,128,143`）。**没有看到多尺度金字塔搜索**，是“已知分辨率下一次缩放”。
- **识别对象配置化：** 每个功能目录下的 `Assets/Recognition.json`。ROI 用表达式写（`cw/ch/s` 变量，`rect()`、`cutLeftTop()` 等函数，底层 NCalc），模板和区域都可以起别名复用（`Docs/technical/recognition-json.md:5-120`）。
- **缓存：** 识别对象按（任务, 对象名, 截图宽, 高）缓存（`Core/Recognition/RecognitionAssets.cs:14,28-33,63-73`）。同一帧内灰度图只转换一次（`ImageRegion.cs:23-37` `CacheGreyMat`）。
- **典型的分级识别（AutoPick，每帧跑）：** `GameTask/AutoPick/AutoPickTrigger.cs`
  1. 模板匹配 F 键图标，没命中就结束这一帧（`:231-233`）；
  2. 命中后，在 F 键旁边的小 ROI 里匹配聊天气泡、设置图标，排除 NPC 对话（`:266-290`）；
  3. 默认不拾取、又没有拾取规则时直接放弃 OCR（`:301-305`）；
  4. 文字区域上方 3 行算 Sobel 梯度，判断“已经在拾取中”就跳过（`:330-338`）；
  5. 文字识别：Yap 小模型（输入 1×1×32×384，`Core/Recognition/ONNX/SVTR/PickTextInference.cs:41-49`）或 Paddle。Paddle 先做轮廓找文字边界，**找到就只跑识别、不跑检测**（`OcrWithoutDetector`，`:349-357`），找不到才跑完整 det+rec（`:375-378`）。
  `OcrWithoutDetector` 全仓有 28 处引用（grep），是常用手段。

### 1.4 OCR 与 ONNX 推理

| 项 | 做法 | 证据 |
|---|---|---|
| 运行时 | Microsoft.ML.OnnxRuntime；执行提供者可选 CPU / DirectML / OpenVINO / CUDA / TensorRT，GPU 选项一律加 CPU 兜底（注释：“只用dml不加cpu的话在很多场景下性能很差”） | `Core/Recognition/ONNX/BgiOnnxFactory.cs:81-107,171-228` |
| OCR 默认强制 CPU | `_cpuOcr = true`；OCR 会话在 `CpuOcr && ocr` 时只用 CPU | `Core/Config/HardwareAccelerationConfig.cs:89`；`BgiOnnxFactory.cs:138-146` |
| OCR 线程策略 | 4 种模式：跟随全局 / ORT 自动 / 游戏流畅优先 / 自定义。“游戏流畅优先”：Det 用一半逻辑核，Rec 用四分之一并限制在 2–4（注释引用 PR #3722）；默认开启自旋 | `Core/Recognition/ONNX/PaddleOcrCpuOptions.cs:5-53`；`BgiOnnxFactory.cs:230-260` |
| 模型 | PP-OCR v4/v5 mobile det、v6 small det、多语种 rec，均为 `slim.onnx`；另有 YOLO（拾取、钓鱼、矿物、古树）、分类模型（Q/E 技能、角色头像）。所有模型集中注册，注释说这样“方便预先对模型预热和缓存管理” | `Core/Recognition/ONNX/BgiOnnxModel.cs:22-120` |
| 模型大小 | 模型通过 NuGet 包 `BetterGI.Assets.Model` 1.0.38 分发，不在仓库里，本机也没有缓存 → **未确认** | `BetterGenshinImpact/BetterGenshinImpact.csproj:54` |
| 检测输入尺寸 | `LimitSideLen = 960`，`LimitType = "max"` | `Core/Recognition/OCR/Paddle/Det.cs:19-25` |
| 识别批处理 | 默认 batch = min(8, 核数)，先按宽度排序再分批 | `Core/Recognition/OCR/Paddle/Rec.cs:44-58` |
| 预热 | `PaddleOcrService` 构造时对一张测试图跑一次完整 OCR | `Core/Recognition/OCR/Paddle/PaddleOcrService.cs:265-274` |
| 懒加载 | OCR 服务第一次用到时才创建（`??=`）；YOLO 会话 `Lazy<>` 延迟创建 | `Core/Recognition/OCR/OcrFactory.cs:19-28`；`Core/Recognition/ONNX/BgiYoloPredictor.cs:18,33` |
| 推理串行 | OCR 整体一把 `_inferenceLock`；Det、Rec 各有会话锁 | `PaddleOcrService.cs:296,314`；`Det.cs:96`；`Rec.cs:121` |
| TensorRT / OpenVINO 编译缓存 | 按模型、设备、设置分目录缓存 engine/timing | `BgiOnnxFactory.cs:148-160,262-283` |
| 输入格式 | 注释反复强调“推荐传入三通道BGR mat……三通道最快” | `PaddleOcrService.cs:283-306` |
| 单次 OCR 耗时 | 代码里有 Stopwatch，但打印语句被注释掉了；仓库里没有记录数字 → **未确认** | `PaddleOcrService.cs:317-330` |

### 1.5 等待与超时

- **以固定 sleep 为主。** `GameTask/` 下 `Sleep(数字)`/`Delay(数字)` 共 754 处，最常见的是 500 ms（102 处）、1000（100）、300（95）、100（74）、200（69）；`WaitFor/WaitUntil/WaitForElementAppear/WaitForAction` 共 92 处（2026-10-24 grep 统计）。
- **轮询式等待：** `NewRetry.WaitForAction` 循环 `Delay(delayMs)` 后再检查，**第一次检查前也先睡一个间隔**，默认 10 次 × 1000 ms（`GameTask/Common/NewRetry.cs:78-90`）。`WaitForElementAppear` 同样是“先执行重试动作 → 等 1000 ms → 截图找”（`:123-150`）。
- **新 API `BvLocator/BvFlow`（类似 Playwright）：** 默认超时 10000 ms、轮询间隔 250 ms（`Core/BgiVision/BvLocator.cs:32-34`），`BvPage` 的默认间隔是 1000 ms（`Core/BgiVision/BvPage.cs:29-34`）。重试次数 = 超时 / 间隔（`BvLocator.cs:133-159`）。提供 `ClickUntilDisappears`：点击，然后一直点到目标消失（`:119-125`）。`BvFlow` 支持“动作 + Until 条件”：执行动作 → 等间隔 → 查目标，没满足就重做动作，超时时报“动作 X 执行 N 次后，等待 Y 超时”（`Core/BgiVision/BvFlow.cs:335-377`）；纯等待步骤在 `:398-420`。
- **没有基于画面变化的等待。** 全仓找不到“画面稳定 / 帧差”类等待；`Cv2.Absdiff` 只出现在一个四选一找差异的工具函数里（`Core/Recognition/OpenCv/ImageDifferenceDetector.cs:9-15,99`）。
- **`TaskControl.Sleep`：** 睡之前先检查是否暂停、游戏窗口是否在前台，不在前台就尝试恢复焦点（`GameTask/Common/TaskControl.cs:23-39,97-137`）。暂停时每 1000 ms 轮询一次（`:56-83`）。

### 1.6 并发与线程模型

- **截图回调线程**（WGC 的 `FrameArrived`）写最新帧，读写锁保护；读取方拿克隆（`GraphicsCapture.cs:39,196-286`）。V2 用“单飞闸门”让并发消费方排队（`GraphicsCaptureV2.cs:62-67,607-611`）。
- **调度器线程**：`System.Timers.Timer` 在线程池上回调 Tick。Tick 里所有触发器串行执行，所以触发器内部不用加锁（`ITaskTrigger.cs:16`）。
- **独立任务**：`Task.Run` 跑在线程池（`GameTask/TaskRunner.cs:117-141`）；全局 `TaskSemaphore = new(1,1)`，同一时间只跑一个独立任务（`TaskControl.cs:20`；`TaskRunner.cs:49`）。
- **推理**：OCR 全局串行（见 1.4），独立任务推理时可暂停 Tick（1.2）。
- **JS 脚本**：每次执行新建一个 V8 引擎，结束时 `Interrupt` 并 `Dispose`（`Core/Script/Project/ScriptProject.cs:79-160`）。
- **自带性能面板**：每帧记录截图耗时、触发器耗时、总处理耗时，遮罩上显示 5 s 窗口内的峰值和每秒跳帧数（`Service/Model/OverlayMetric/DispatcherTickMetrics.cs:10-55`；`Service/OverlayMetricsService.cs:24,254-257`）；`SpeedTimer` 按阶段打点（`TaskTriggerDispatcher.cs:435-446,508,515`；`AutoPickTrigger.cs:229,382,417`）。**典型数值仓库里没有记录 → 未确认。**

---

## 2. 问题 2：任务、触发器、识别、脚本怎么组织和扩展

### 2.1 总体分层

```
截图器 (Fischless.GameCapture, IGameCapture)      ← 可换实现：5 种 CaptureModes
输入后端 (Core/Input, IInputBackend)              ← 可换实现：Win32 / WebSdk / Gamepad
识别 (RecognitionObject + ImageRegion.Find, OCR, YOLO)
  ├─ 实时触发器 ITaskTrigger（50 ms 一帧，写死 9 个）
  └─ 独立任务 ISoloTask（Task.Run，单任务信号量）
        └─ 数据驱动的执行器：战斗策略、地图追踪、键鼠回放、JS 脚本、AutoCombo 行为树
调度器/配置组 (ScriptGroup) → 一条龙 → 新的 Pulonia 任务树（设计中）
```

- `IGameCapture` 只有 `Start/Capture/Stop`（`Fischless.GameCapture/IGameCapture.cs`），由工厂 `switch` 创建（`GameCaptureFactory.cs:48-59`）。
- `IInputBackend` 是“一组可以整体切换的输入输出：Win32 (SendInput + PostMessage)、WebSdk、Gamepad”（`Core/Input/IInputBackend.cs:5-24`）。
- `ISoloTask` 只有 `Name` 和 `Start(ct)`（`GameTask/ISoloTask.cs:9-32`）。
- `ITaskTrigger`：`Name/Priority/IsEnabledByConfig/IsExclusive/IsBackgroundRunning/SupportedGameUiCategory/OnEnabled/OnDisabled/OnCapture`（`GameTask/ITaskTrigger.cs:18-66`）。

### 2.2 扩展点逐个看

| 扩展点 | 注册方式 | 配置格式 | 第三方能否加 |
|---|---|---|---|
| 实时触发器 | `GameTaskManager.CreateTriggers()` 里 new（`GameTaskManager.cs:28-44`） | C# | 不能。脚本只能租用 3 个已有触发器并传参数（`TaskTriggerDispatcher.cs:59-64,165-186`）。设计文档把“不扩大脚本可启用的范围”列为非目标（`Docs/design/realtime-trigger.md:122`） |
| 识别对象 | `RecognitionAssets.Get(任务, 对象名)` 读 `Assets/Recognition.json`（`RecognitionAssets.cs:63-73`） | JSON + NCalc 表达式（`Docs/technical/recognition-json.md`） | 只对本体开发者开放（文件在本体目录里）。JS 可以自己 new `RecognitionObject`（`Core/Script/EngineExtend.cs:70`） |
| 地图追踪的路点动作 | `ActionFactory` 里用字符串 `switch` 映射到 `IActionHandler`，未知名字抛异常（`GameTask/AutoPathing/Handler/ActionFactory.cs:12-39`；接口 `IActionHandler.cs:8-11`） | 路径 JSON：`info`（名称、作者、版本、`bgiVersion`）+ `positions`（x、y、type、move_mode、action、action_params）（`GameTask/AutoPathing/Model/PathingTaskInfo.cs:12-22`；`Waypoint.cs:38-63`） | 能贡献路径数据，不能加动作种类 |
| 战斗策略 | `CombatScriptParser` 解析文本（`GameTask/AutoFight/Script/CombatScriptParser.cs:15-68`） | 每行“角色名 动作(参数), …”，例如 `钟离  s(0.2), e(hold), wait(0.2), w(0.2)`（`User/AutoFight/群友分享/万能战斗策略（萌新推荐）.txt`） | 能，社区直接分享 txt |
| 键鼠录制 | `Core/Recorder/*` 录制与回放 | JSON：`macroEvents` + `info`（含 `bgiVersion`、录制时的窗口 x/y/宽/高）（`Core/Recorder/Model/KeyMouseScript.cs:11-12`；`KeyMouseScriptInfo.cs:8-23`） | 能 |
| JS 脚本 | 目录 + `manifest.json`（`Core/Script/Project/Manifest.cs:18-29`）；ClearScript V8 执行（`ScriptProject.cs:79-160`） | `manifest.json`：name、version、`bgi_version`、main、settings_ui、library、saved_files、`http_allowed_urls` | 能。这是唯一的“代码级”扩展 |
| AutoCombo 行为树 | LLM 建树 → 运行时 tick | 节点词汇表就是 `AutoComboBuildCatalog` 里带 `[Description]` 的工厂方法，由源生成器转成 LLM 工具（`GameTask/AutoCombo/ComboBuild/AutoComboBuildCatalog.cs:12-61`） | 不能加节点；LLM 只能在白名单内组合 |
| 配置组 / 调度器 | `ScriptGroupProject` 四种类型：Javascript、KeyMouse、Pathing、Shell（`Core/Script/Group/ScriptGroupProject.cs:136-167,409-412`） | JSON | 用户自己编排 |

### 2.3 JS 脚本的沙箱与 API 面

- 宿主对象是显式白名单，注释提醒“这个方法会添加一些全局方法和对象，不要随便添加，以免安全风险”（`EngineExtend.cs:30-49`）。`xHost`（ExtendedHostFunctions）因“有越权的安全风险”被注释掉（`:38`）。
- 文件访问限制在脚本目录（`LimitedFile(workDir)`，`EngineExtend.cs:46`）。HTTP 要满足两个条件：用户在全局设置里打开“JS HTTP权限”，并且 URL 在 manifest 的 `http_allowed_urls` 里，否则抛 `UnauthorizedAccessException`（`Core/Script/Dependence/Http.cs:25-43`）。
- 暴露的能力：键鼠、`captureGameRegion`、`RecognitionObject/ImageRegion`、OpenCvSharp 全部类型、YOLO、`dispatcher`（加触发器、`SoloTask` 调内置任务）、内置任务参数类型、`BvPage/BvLocator`、HTML 遮罩、通知（`EngineExtend.cs:41-109,151-173`）。
- 版本兼容只做提示：`bgi_version` 高于当前版本时只记一条错误日志，脚本照样运行（`Manifest.cs:59-63`）。

### 2.4 社区贡献与分发

- 中央仓库 `bettergi-scripts-list`，内置两个源：CNB 镜像和 GitHub；GitCode 因“10MB限制”下线（`Core/Script/ScriptRepoUpdater.cs:79,515-517`）。客户端用 LibGit2Sharp 拉取（`:27-28`）。
- 用户按路径订阅；启动时如果开了自动更新，就先静默更新仓库，再把订阅的路径检出到用户目录；仓库里已删除的路径（“幽灵订阅”）会被过滤，避免误删用户文件（`ScriptRepoUpdater.cs:151-205,253-301,435-506`）。
- 战斗策略、键鼠、路径、JS 都放在这个仓库里，由社区 PR 维护（仓库结构不在本克隆中，**具体审核流程未确认**）。

### 2.5 游戏版本更新后怎么维护

- **识别素材与本体一起发版：** `Recognition.json` 和模板图都放在本体的 `GameTask/<任务>/Assets/` 下（`RecognitionAssets.cs:66`），界面变了就改本体、发新版本。
- **多语言 OCR 文本：** `BvResxHelper.*.resx`（en/fr/it/zh-Hans/zh-Hant）放各语言界面文字（`GameTask/Common/BgiVision/`）；OCR 误识别用 `ReplaceDictionary` 修正（`RecognitionObject.cs:196-198`）。
- **社区数据带版本字段：** 路径、键鼠、JS 都有 `bgiVersion`（2.2 表），但只用于提示。
- **设计中的 Pulonia 补上了版本固定：** “运行前保存完整计划、引用展开结果、有效配置、账号绑定、资产版本与文件指纹快照”；遇到“版本更新或未知界面”时保存诊断截图和步骤（`Docs/design/pulonia/execution.md:59,75,87-92`）。任务树 JSON 带 `schema_version`，执行器按 `TaskType → IPuloniaTaskExecutor` 注册查找（`Docs/design/pulonia/task-model.md:83-114`；`execution.md:26`）。注意它还是“讨论稿，尚未实施”（`Docs/design/automation-system.md:3`）。
- **触发器生命周期整改：** 曾经因为“清空就是销毁、恢复就是重建”出过一批 bug，后改成实例常驻、按名单启停（`Docs/design/realtime-trigger.md:29-53`）。这对手机端是个教训：扩展点要有明确的生命周期。

---

## 3. 对手机端 v1 的启示

对照 `spikes/ARCHITECTURE-mobile-v1.md`：Kotlin、无障碍读树 + 截图 OCR、官方任务包、v1 先求稳定。

### 3.1 值得借鉴（按收益排序）

| # | 做法 | BetterGI 证据 | 安卓可行性 | 改动 | 怎么落到 v1 |
|---|---|---|---|---|---|
| 1 | **位置已知时只跑识别、不跑检测** | `AutoPickTrigger.cs:349-357`；`PaddleOcrService.cs:312-323` | 可行：onnxruntime-android 直接跑 rec 模型 | 小 | 微信步骤先用版面规则（列表第一行、聊天区最下面的气泡）算出裁剪框，`ocrText` 找法加 `crop` 模式只跑 rec；找不到再退回全图 det+rec。det 是 OCR 的大头（本仓库桌面端 det 已经单独上了 DirectML，见 ADR 0009 A4），这一条在手机上收益最大 |
| 2 | **便宜的检查放前面，命中才做贵的** | AutoPick 的五级过滤（1.3） | 可行 | 小 | 运行时固定顺序：界面树 → toast/事件 → 局部 OCR → 全图 OCR。`find` 列表本来就按顺序尝试，只需在校验里禁止把全图 OCR 写在树找法前面 |
| 3 | **找法、动作、预期做成封闭的注册表，未知名字在校验时报错** | `ActionFactory.cs:12-39`（未知名字抛异常）；AutoCombo 的白名单目录 | 可行 | 小 | `core` 里一张 `Map<String, FinderFactory>`，`Expect`、`Action` 同理。任务包只写名字和参数，加载时就校验，不等跑到这一步才失败 |
| 4 | **任务包和本地补丁都带版本字段** | manifest `bgi_version`、路径 / 键鼠 `bgiVersion`（`Manifest.cs:21`；`KeyMouseScriptInfo.cs:19`）；Pulonia 的版本快照（`execution.md:87-92`） | 可行：`PackageManager` 能读目标 App 的 `versionName` | 小 | 任务包加 `schemaVersion`、`minAgentVersion`、`testedWith: {包名: 版本}`。补教补丁记录目标 App 版本，版本变了就提示“补丁可能失效”（文档 §5 已写只对该 App 版本生效，这里把它落成字段） |
| 5 | **相对坐标的区域写法** | `Recognition.json` 的 `cw/ch` 表达式与 `cutLeftTop` 等函数（`recognition-json.md:77-116`） | 可行 | 小 | 版面规则和 OCR 裁剪框用 0–1 的比例矩形 `[x, y, w, h]`，不引入表达式引擎；只在需要时加 `cutTop(0.2)` 这类几个固定函数 |
| 6 | **“做动作直到条件满足”，失败信息写清做了几次** | `BvLocator.ClickUntilDisappears`（`BvLocator.cs:119-125`）；`BvFlow.ExecuteActionStep`（`BvFlow.cs:335-377`） | 可行 | 小 | 给步骤加 `retry: {times, until}`，主要用于“点了没反应”。错误码带上尝试次数和耗时，运行清单里直接展示 |
| 7 | **每步分阶段计时，可在界面上看** | `SpeedTimer`；`DispatcherTickMetrics`；遮罩峰值 / 跳帧（1.6） | 可行 | 小 | 日志里每步记 找目标 / 动作 / 等稳定 / 查预期 四段耗时（只记数字，符合“内容不落盘”），调试版运行清单显示 |
| 8 | **OCR 启动预热、懒加载、单实例串行** | `PaddleOcrService.cs:265-274`；`OcrFactory.cs:19-28`；`_inferenceLock` | 可行 | 小 | 只在任务包含 OCR 步骤时，在启动目标 App 的同时预热，避开第一步的冷启动；OCR 会话进程内单例，串行调用 |
| 9 | **OCR 线程数可调，默认偏保守** | `PaddleOcrCpuOptions.cs:43-46`（det 半数核、rec 2–4） | 可行：ORT 的 `intraOpNumThreads` | 小 | Neo5 上实测 2/4 线程，选定值写进默认配置；大小核的影响要真机测 |
| 10 | **后台“守卫”（触发器的租约模式）** | `AddTrigger` 租约、`OnEnabled/OnDisabled`（`TaskTriggerDispatcher.cs:172-186,545-626`） | 可行：挂在无障碍事件上，不需要每帧截图 | 中 | v1 不做。v1.1 可以加一种 `Watcher`：任务运行时监听界面事件，自动关掉“版本更新 / 权限”弹窗。由任务包声明启用，任务结束自动撤销 |

### 3.2 不适用或不该照搬

| 做法 | 原因 |
|---|---|
| 50 ms 固定节拍、每帧截图跑触发器 | 手机上费电。无障碍截图有调用频率限制（`takeScreenshot` 调得太快会返回 `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT`；具体间隔凭记忆约 1/3 s；2026-10-25 Neo5 实测 320–410 ms）。界面树本身就有事件推送，应该由事件驱动 |
| 大量写死的 `Sleep(500)` 和先睡后查的轮询 | 正是 BetterGI 流程慢、不稳的来源（1.5）。v1 的“事件 + 300 ms 静默”等稳定方式更好，保留 |
| WGC / DXGI / GPU 打包回读等截图优化 | 平台专属。安卓对应的是 `takeScreenshot` 或 MediaProjection；v1 每步只截一张，不是瓶颈 |
| 每 2 s 手动 `GC.Collect()` | .NET 大 Mat 的权宜之计；ART 上不需要，反而会卡 |
| 内嵌 V8 跑社区 JS | 体积、安全审查和“只在检查更新时联网”的承诺都冲突（2026-10-28 起不变量改为“不靠云端”：App 更新、拉任务包、调自有网站接口都可以联网，见架构文档决定记录）。v1 的任务包只放数据 |
| git 仓库 + 订阅 + 启动时自动更新 | 和“只在用户点检查更新时联网”冲突（2026-10-28 起不变量改为“不靠云端”：App 更新、拉任务包、调自有网站接口都可以联网，见架构文档决定记录）。手机端用签名 zip / JSON（文档 §10 已定） |
| 按分辨率分目录的模板图 | 手机分辨率、DPI、深色模式差异更大；优先用文字和树属性，模板图只作为最后手段 |
| 键鼠坐标录制作为共享格式 | 坐标跨机型不可移植。只保留为本机补教补丁（文档已这么定） |
| TensorRT / OpenVINO / DirectML 多执行提供者 | 安卓上可选的是 NNAPI / XNNPACK / QNN，效果因机型而异；v1 先 CPU，留一个开关（后来实测：NNAPI 不可用，识别改用 LiteRT 的 GPU，见 `docs/ocr-experiments.md` 4.6） |
| LLM 现场建树（AutoCombo） | v1 不用模型。但它的形态（LLM 只在运行前、在白名单词汇表内组合，运行时确定性执行）可以作为将来“学习模式”的范式：模型产出的是任务包 JSON，不是运行时决策 |

### 3.3 骨架做成什么样的扩展点（建议，可直接并入架构文档 §3–§5）

BetterGI 的经验是：**代码层只给开发者扩展，用户和社区只扩展数据；数据只能引用代码里已经注册的名字。** v1 照这个思路，扩展点是 `core` 里几张编译期注册表，不做运行时插件加载：

```
core/
  ext/
    Perception.kt   interface Perceiver { suspend fun snapshot(scope: Scope): ScreenState }
                    // 内置：TreePerceiver、OcrPerceiver(mode = CROP_REC | FULL)
    Finders.kt      object Finders { val registry: Map<String, FinderFactory> }
                    // text, desc, id, listItem, lastMessage, ocrText（v1 只做这些）
    Actions.kt      object Actions { val registry: Map<String, ActionFactory> }
                    // launch, tap, long, back
    Expects.kt      object Expects { val registry: Map<String, ExpectFactory> }
                    // anyText, gone, toast
    PackSource.kt   interface PackSource { fun list(): List<PackRef>; fun load(ref): Pack }
                    // BundledSource(assets/)，SignedFileSource（用户点检查更新后下载、验签）
  pack/
    Validator.kt    // 未知名字、缺参数、版本不匹配 → 加载时报错，不等运行到这一步
  run/
    StepTimer.kt    // 每步 找/动/等/查 四段耗时
```

对应的任务包字段增量（在文档 §4 示例上加）：

```json
{
  "schemaVersion": 1,
  "minAgentVersion": "1.0.0",
  "testedWith": {"com.tencent.mm": "8.0.50"},
  "steps": [
    {"do": "long",
     "target": {"intent": "最后一条消息",
       "find": [{"lastMessage": true},
                {"ocrText": {"region": [0, 0.12, 1, 0.76], "mode": "crop"}}]},
     "expect": {"anyText": ["复制"]},
     "retry": {"times": 2, "until": "expect"}}
  ]
}
```

（`testedWith` 中的版本号是示例。）

建议的文档修改：

- **§3 结构：** `core/` 下加 `ext/`（上面四张注册表和 `PackSource`）。写明“v1 的扩展点是编译期注册表；任务包只能引用已注册的名字”。
- **§4 任务包：** 加 `schemaVersion`、`minAgentVersion`、`testedWith`、`retry`；`ocrText` 加 `region`（比例矩形）和 `mode`（`crop` 只跑识别，`full` 跑检测加识别）；校验规则写明未知名字直接拒收。
- **§5 运行时：** 第 1 步“找目标”写明代价顺序：树 → 局部 OCR（只识别）→ 全图 OCR；OCR 在任务启动时预热；每步记录四段耗时。保留“事件 + 300 ms 静默”的等待，**不引入固定 sleep**。
- **§10 未决：** “骨架的可插拔程度”改为已定：v1 采用编译期注册表加数据驱动的任务包；运行时插件、脚本引擎、后台 Watcher 留到 v1.1 以后，分别单独评估。
- **新增风险项：** `takeScreenshot` 的调用频率限制需要在 M3 前真机测出来，决定微信步骤“找目标”重试的最小间隔。

### 3.4 本报告没覆盖或无法确认的

- 各触发器每帧的实际耗时、单次 OCR 耗时、模型文件大小：仓库里没有记录（标为未确认）。
- 社区脚本仓库的审核流程：不在本克隆里。
- OneDragon 的对照：由另一个会话负责。
