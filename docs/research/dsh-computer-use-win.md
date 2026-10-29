# dsh-computer-use-win 调研：资源占用、响应速度与后台运行

日期：2026-10-27。源码：本机克隆 `C:\Users\R_Crusoe\AppData\Local\Temp\dsh`，commit `e4482b8`（2026-10-25，版本 0.5.2，完整克隆 80 个提交）。只读了静态源码和仓库内文档，没有运行任何程序，文中的耗时数字都来自它自己的文档或代码注释。源码里找不到依据的，标 **未确认**。

文件名在仓库里唯一，下文省略目录：`server.mjs`、`browser.mjs`、`takeover.mjs`、`activity.mjs`、`dsh-plugin.mjs`、`settings.mjs` 在 `mcp/`；`windows-uia.ps1`、`windows-takeover.cs` 在 `scripts/`；`dev-notes.md`、`tool-context.md` 在 `docs/`；`architecture.md`、`safety.md`、`mcp-tools.md` 在 `docs/wiki/`。

## 0. 结论先行

1. **它只是一组工具，不带任何模型。** 它是 DeepSeek Harness（DSH）的 MCP 插件：Node 写的 MCP 服务，加一个常驻的 PowerShell 后端（`package.json:4`）。仓库里没有视觉模型、没有 API key（`README.md:291`，`architecture.md:31`），唯一的网络请求是连本机浏览器调试端口（`browser.mjs:683`）。决策由 DSH 宿主里的大模型做，模型在本地还是远程、每步耗时多少，这个仓库里看不到，**未确认**。
2. **CPU/GPU 占用小，空闲时基本不烧资源，但不是零。** 感知全是按需的：UIA 读树、GDI/PrintWindow 截图、Windows 自带的 WinRT OCR，没有截图循环。代码自己只在 WGC 兜底截图时临时建一个 D3D11 设备。常驻的小开销有：MCP 服务每 250 ms 读两个 JSON 文件；接管助手一旦启动就常驻，带一个 35 ms 定时器和一个全局低级键盘钩子；agent 浏览器空闲 10 分钟才关。仓库里**没有任何 CPU、GPU、内存实测数字**。
3. **速度主要靠“少起进程、少传像素、少传上下文”。** 常驻后端把 health 从约 540 ms 降到 6 ms、读树（深度 2）从约 585 ms 降到 103 ms（`dev-notes.md:92-101`）；窗口裁剪加降采样把截图从 3.8 MB 降到 91 KB（`dev-notes.md:104-106`）；工具列表从 43,495 字符压到 9,046（精简）或 510（懒启动）（`tool-context.md:13,47`）。慢的地方：动作内部有 19 处写死的 5–120 ms sleep；桌面上的 `wait_for` 是 250 ms 轮询，每轮整跑一次 UIA 查找或截图加 OCR；全仓没有订阅任何 UIA 或 WinEvent 事件。浏览器通道相反，是事件驱动的（加载事件、网络请求、DOM 250 ms 静默）。
4. **桌面应用上它做不到真正后台，只做到“少打扰”。** 没有隔离桌面、虚拟显示器、第二会话（全仓搜不到 `CreateDesktop`、`WTS*`、`IddCx` 等）。它的办法是一条从安静到吵闹的梯子：先发窗口消息或调 UIA 模式（`BM_CLICK`、`EM_REPLACESEL`、`WM_VSCROLL`、Invoke 等，不动鼠标、不切前台）；不行再“借用前台”：记下用户的前台窗口和鼠标位置，把目标拉到前面，检查落点没被挡住，用 SendInput 点完，再把鼠标和前台还回去。截图对被遮挡的窗口用 PrintWindow，再不行用 WGC，最后才截屏幕区域并标 `occludedPossible`。
5. **“两个光标”只在浏览器里成立。** DSH 自己启动一个 Chromium，用 CDP 的 `Input.dispatchMouseEvent` / `insertText` 在页面里点击和填写，后台标签页也能截图，页面里画一个小箭头表示它在操作的位置。桌面上那个“DSH 标签”只是跟着系统光标走的透明窗口，不是第二个光标（`windows-takeover.cs:325-333,348-349`）。
6. **对两个项目最值得拿的：** gui-agent 先做“借用前台并归还 + 落点检查”和窗口级截图，两项都不碰只读 ADR；真正不抢前台的控件动作和 CDP 输入要改 ADR-0007、ADR-0018 的只读不变量，需要项目所有者决定。phone-agent 的 `performAction` 本来就相当于 dsh 的 UIA 模式调用，但安卓上目标 App 必须正显示在某块屏幕上才能读树、截图和操作，主屏上只能前台，真后台还是只能靠虚拟屏；能直接借的是“安静动作优先、需要真实触摸时再升级”“落点被别的窗口挡住就停”“自己的叠加层不进截图”。详见第 5 节。

---

## 1. 它是什么

```
DSH（宿主，跑大模型）
 └─ dsh-plugin.mjs：设置、轮次跟踪（同一时间只允许一段对话操作桌面）
     └─ 官方 MCP 桥 ── stdio ──> server.mjs（Node，校验参数、排队、租约）
           ├─ windows-uia.ps1 -Persistent（常驻 PowerShell，读树/截图/OCR/输入，一次一个请求）
           ├─ browser.mjs（同进程，CDP over WebSocket，连 DSH 自己的浏览器）
           └─ windows-takeover.ps1 → windows-takeover.cs（WinForms：屏幕提示、Esc 停止）
```

- 架构说明：“One persistent PowerShell backend serializes desktop requests; queued request time is not counted as execution time”（`architecture.md:3`）。
- 运行时零 npm 依赖，`package.json:82-100` 只有 dev 和可选 peer 依赖。
- 动作结果只报 `verification: "sent"`，看到预期变化才报 `observed`（`architecture.md:21`；`server.mjs:1046-1052`）。

---

## 2. 问题 1：CPU / GPU 占用

### 2.1 感知手段

| 感知 | 做法 | 本地/远程 | 证据 |
|---|---|---|---|
| 窗口列表 | Win32 枚举，不进 UIA | 本地 | `architecture.md:9` |
| 控件树 | .NET `System.Windows.Automation`，`CacheRequest` 一次取 12 个属性，`TreeScope.Element`，惰性 TreeWalker | 本地 | `windows-uia.ps1:524-549,1641-1661` |
| 截图 | 前台无遮挡用屏幕裁剪（`CopyFromScreen`），否则 PrintWindow → WGC → 屏幕区域 | 本地 | `windows-uia.ps1:2193-2333`，见 4.3 |
| OCR | Windows 自带 `Windows.Media.Ocr`，用 C# 小助手编译后加载 | 本地 | `windows-uia.ps1:1202-1257` |
| 网页 | CDP `Accessibility.getFullAXTree`、`Page.captureScreenshot` | 本地（连 DSH 自己的浏览器） | `browser.mjs:1169-1171,1771-1784` |
| 检测模型 / 视觉大模型 | 没有。“No visual model is bundled” | — | `architecture.md:31`；`README.md:291` |
| 决策 | DSH 宿主的大模型 | **未确认** | 仓库内无模型调用 |

### 2.2 模型、推理设备与 GPU

- **OCR 引擎每次调用都新建。** `OcrEngine.TryCreateFromUserProfileLanguages()` 在每次 `Recognize` 里调用，没有缓存（`windows-uia.ps1:1234`）；输入是磁盘上的 PNG，用 `StorageFile` 读、`BitmapDecoder` 解码（`:1230-1233`）。本机中文识别资源是 `C:\Windows\OCR\zh-cn\MsOcrRes.orp`（2.3 MB），引擎在 `C:\Windows\System32\Windows.Media.Ocr.dll`；跑在 CPU 还是 GPU 是系统内部实现，**未确认**。
- **本机实测（2026-10-28，合成图片，白底黑字微软雅黑 14 号，不截屏，每档 6 次）**：引擎首次创建 27 ms，语言 zh-Hans-CN，单图边长上限 10000。识别耗时随文字量增长：1920×1080、90 行约 610–880 ms；1000×700、40 行约 170–260 ms；600×40 单行约 5–19 ms。按 dsh 的做法每次读 PNG、解码、新建引擎，只比复用引擎多几毫秒，主要开销在识别本身。中文按单字切词（“文 件 传 输 助 手 …”），所以 dsh 的文字查询忽略空白。真实界面（小字、彩色背景、图标）上的速度和准确率没测。
- **代码自己用到 GPU 的只有 WGC 兜底。** BitBlt 和 PrintWindow 读的是 DWM 合成的结果，GPU 那一侧由系统承担，没有测。每次 WGC 截图都新建一个硬件 D3D11 设备和捕获会话，拿到一帧就释放（`windows-uia.ps1:1445-1450,1492`）。帧回读到内存后用 GDI+ 存成 PNG。WGC 还打开了 `IsCursorCaptureEnabled`，所以截图里可能带鼠标指针（`:1450`）。
- **浏览器**：agent 浏览器是普通的 Chrome/Edge 进程（可见或 `--headless=new`），它自己的 GPU 占用没有测，**未确认**（启动参数 `browser.mjs:782-786`）。

### 2.3 截图方式与频率

- **没有截图循环。** 只有 `snapshot`、`screenshot`、`ocr`、`find` 的 OCR 兜底、`wait_for` 的文字条件会截图，每次调用截一张。
- **默认分辨率不小。** `snapshot` 默认 `captureWindow: false`，截整个虚拟屏，再缩到宽 1600（`server.mjs:146-157`）；OCR 默认缩到宽 1920（`windows-uia.ps1:3183`；`find` 的兜底也是 1920，`server.mjs:1030`）。缩放用 `HighQualityBicubic`（`windows-uia.ps1:2305`）。
- **每张图都绕一圈磁盘。** 存 PNG 到 `%TEMP%`，再读回来转 base64（`windows-uia.ps1:2312-2315`）；OCR 再从这个 PNG 文件解码一次。WGC 路径更绕：助手先存一个 PNG，后端读进 Bitmap，再存一次（`:2244-2259`）。每次截图前还扫一遍 `%TEMP%`，删 30 分钟前的旧图（`:2195-2199`）。
- PrintWindow 用 `PW_RENDERFULLCONTENT`（标志 2），然后每 24 px 取一个像素检查是不是全黑（`windows-uia.ps1:2171-2185`）。

### 2.4 读树的开销控制

- 节点数、深度、时间三个上限：`snapshot` 默认 5 层 / 250 节点，`accessibility_tree` 6 / 500，`find` 8 / 1200（`server.mjs:159-160,177-178,210-212`）；时间预算默认 5000 ms，在两次 provider 调用之间检查（`server.mjs:117-121`；`windows-uia.ps1:1663-1670`）。
- **进 UIA 之前先探活。** `SendMessageTimeout(WM_NULL, SMTO_BLOCK|SMTO_ABORTIFHUNG, 200 ms)`，注释写“Never enter the UIA provider here”（`windows-uia.ps1:270-275`）。
- **卡死就换进程。** 观察超时（预算 + 10 s）后杀掉后端，下次调用再懒启动；同时用新进程补一张截图，结果仍标失败（`server.mjs:890-909,999-1016`）。
- **Chromium 预热。** 对每个可见的 `Chrome_RenderWidgetHostHWND`（最多 8 个）读一次页面根节点的 `ControlType`，再每 50 ms 查一次，最多等 1.5 s 且不超过预算的一半（`windows-uia.ps1:2386-2437`）。实测预热 71–100 ms，页面树 50–450 ms 后到（`dev-notes.md:192,200-203`）。

### 2.5 常驻进程与空闲开销

| 组件 | 何时启动 | 空闲时在做什么 | 证据 |
|---|---|---|---|
| PowerShell 后端 | 第一次调用时懒启动 | 阻塞在 `ReadLine`，不轮询；没有空闲退出，超时、取消或服务退出时才杀 | `server.mjs:836-842,1246`；`windows-uia.ps1:3305` |
| 接管助手（WinForms） | 第一次需要真实输入，或设置页点“预览” | **启动后常驻到服务关闭**。35 ms 一次的定时器一直在跑（未接管时每次 tick 几乎什么都不做）；构造时就装了 `WH_KEYBOARD_LL`，用户每一次按键都经过它，未接管时立即放行 | `takeover.mjs:130,152-160,203-211`；`windows-takeover.cs:150-161,305-310` |
| 接管期间 | — | 每 500 ms 枚举一次显示器并把提示窗口置顶；鼠标标识只在指针动了才重绘 | `windows-takeover.cs:157,179-189,447` |
| MCP 服务 | DSH 的 MCP 桥启动它（`architecture.md:3`） | 有配置文件或活动文件时，每 250 ms 同步读两个 JSON | `takeover.mjs:60-63,81-104`；文件路径由 `dsh-plugin.mjs:77-78` 传入 |
| 宿主插件 | DSH 进程内 | 每 250 ms 比较一次设置；有对话在用桌面时每 1 s 写一次活动文件（5 s 过期） | `dsh-plugin.mjs:133-140`；`activity.mjs:31,57` |
| agent 浏览器 | 第一次开标签页 | 每 60 s 查一次，空闲 10 分钟关闭 | `browser.mjs:124,645,874-886` |

- 提示层每块显示器一个全屏分层窗口（`windows-takeover.cs:495-520`）。按 32 位位图推算，一块 2560×1440 的屏约 14.7 MB 内存，这是推算，**未实测**。
- **实测数字：** 仓库里只有延迟数字（见 2.4、3.1），没有 CPU%、GPU%、内存或功耗。`test/bench-latency.mjs`、`test/bench-persistent.mjs` 只测延迟，而且后端路径写死成作者机器的 `E:/PythonFiles/...`（`bench-latency.mjs:4`）。**未确认。** 要实测需要启动它的后端和接管助手，按交接要求先问用户。

---

## 3. 问题 2：响应速度

### 3.1 一步的链路和各段耗时

```
模型决策（DSH）→ MCP 调用 → 校验参数、取租约、排队 → 后端执行 → 返回 sent
            → 模型再发一次 wait_for 或观察 → observed
```

| 段 | 做法 | 已知耗时 | 证据 |
|---|---|---|---|
| 模型决策 | DSH 宿主 | **未确认** | — |
| 工具列表（每轮都进上下文） | 全部 / 精简 / 懒启动 | 20,243 / 9,030 / 510 字符（不是 token 数） | `tool-context.md:13,47` |
| 后端启动 | 常驻进程；C# P/Invoke、OCR、WGC 助手按源码哈希编译成 DLL 缓存在 `%TEMP%` | 冷启动约 500 ms；DLL 缓存省 300–500 ms；热调用 6–103 ms | `dev-notes.md:92-103`；`windows-uia.ps1:412-422,1259-1296,1511-1552` |
| 排队 | 后端一次只跑一个请求；超时从真正发出时开始算 | — | `server.mjs:724-736` |
| 读树 | 见 2.4 | health 6 ms、list_windows 44 ms、tree（深度 2）103 ms | `dev-notes.md:94-96` |
| 截图 / OCR | 见 2.3 | **未确认** | — |
| 动作内部 | 固定 sleep，见 3.2 | 一次坐标点击 ≥ 130 ms，加激活 60–120 ms，再加归还前的两次 `WM_NULL`（每次最多 1 s） | `windows-uia.ps1:650,671,837,1866-1874` |
| 验证 | 动作只返回 sent，确认要模型再调一次 `wait_for` 或观察 | 至少多一轮模型调用，**耗时未确认** | `server.mjs:1046-1052`；`README.md:151-154` |

### 3.2 等待：固定 sleep、轮询还是事件

- **动作内部是固定 sleep。** `windows-uia.ps1` 里 21 处 `Start-Sleep`：19 处写死 5–120 ms，另外 2 处是 `wait_for` 的间隔和 `wait` 工具的参数（2026-10-27 grep）：
  - 激活：最小化窗口还原后 80 ms，正常切换后 60 ms，按 Alt 的方法 120 ms（`:644,650,671`）
  - 坐标点击：移动后 40 ms，按下 30 ms，抬起后 60 ms（`:1866-1874`）；滚轮：30 + 60 ms（`:2835,2844`）
  - 粘贴：写剪贴板后 50 ms，Ctrl+V 后 80 ms（`:1908,1911`）
  - 后台点击（PostMessage）：30 + 40 ms，双击再加 60 ms；后台打字每个字符 5 ms（`:1161-1172,1187`）
  - 聚焦文本框后 80 ms（`:982`）；Chromium 预热轮询 50 ms（`:2426`）
- **“等目标处理完”用的是 `WM_NULL`。** `BM_CLICK` 用 PostMessage 发出去，再 `SendMessageTimeout(WM_NULL)` 等按钮处理完（最多 2 s，`:893-895`）；借用前台的输入结束后发两次 `WM_NULL`（每次最多 1 s）再决定要不要还前台（`:837`）。动作里除了固定 sleep，就靠它等对方。
- **`wait_for` 是轮询。** 默认间隔 250 ms、超时 5 s（`server.mjs:475-476`）。第一次检查不先睡（和 BetterGI 不同），之后每轮要么枚举全部窗口，要么整跑一次 `find`（UIA 预算 1 s），要么整跑一次 `ocr`（截图 + PNG + OCR）（`windows-uia.ps1:3097-3121`）。`wait` 工具就是固定 sleep（`:3277-3281`）。
- **桌面上没有事件驱动的等待。** 全仓搜 `EventHandler`、`AddAutomation`、`StructureChanged`、`SetWinEventHook`、`FocusChanged`，0 处（2026-10-27 grep）。
- **浏览器通道是事件驱动的。** 动作后先等 120 ms，再按事件分段等：它自己引起的导航先等文档（≤15 s），再等 load（≤3 s）；在途请求 ≤3 s；最后在页面里用 `MutationObserver` 等 250 ms 无变化，硬上限 1.5 s（`browser.mjs:483-488,1214-1233`；`CHANGELOG.md:15`）。浏览器通道的 `wait_for` 仍是按 `intervalMs` 轮询 AX 树（`browser.mjs:1729-1739`）。

### 3.3 缓存、预热、并行、跳过模型的快路径

| 类别 | 有什么 | 证据 |
|---|---|---|
| 缓存 | RuntimeId 注册表（最多 6000 条、5 分钟，校验 HWND/PID）；窗口位置缓存用于“归位”（窗口移动后补偿旧坐标）；按标题记下的窗口身份 5 分钟后重新记；Chromium 页面状态按 renderer HWND 记 30 s；编译好的 DLL | `windows-uia.ps1:581,1753,1009-1041,1058-1075,2373`；`dev-notes.md:150` |
| 不缓存 | 观察结果每次重走一遍；OCR 引擎每次新建；WGC 会话每次新建 | `windows-uia.ps1:1234,1445` |
| 预热 | 只有 Chromium 无障碍树预热。OCR 没有预热，第一次用时还要现场编译助手（之后有 DLL 缓存） | `windows-uia.ps1:1259-1296,2386-2437` |
| 并行 | 没有。桌面请求在一个后端里串行；浏览器通道和后端是两条路，同一个标签页里的动作也是一次一个 | `architecture.md:3,27`；`server.mjs:724-736` |
| 省一轮模型调用的快路径 | `find` 在 UIA 稀疏（≤20 节点）或被截断且没找到时，自动附上 OCR 结果；安静尝试需要真实输入时，服务端自己带提示重跑，不回给模型；`type_text` 自动挑 `EM_REPLACESEL`；浏览器动作结果附带页面变化 | `server.mjs:1020-1038,860-869`；`windows-uia.ps1:2858-2878`；`browser.mjs:1189`；`README.md:262` |
| 不经模型的实时循环（类似 BetterGI 触发器） | 没有 | — |

---

## 4. 问题 3：能否后台运行、不影响前台

### 4.1 输入注入路径

| # | 路径 | API | 动真实鼠标 | 切前台 | 能否确认送达 | 证据 |
|---|---|---|---|---|---|---|
| 1 | Win32/WinForms 按钮 | `PostMessage(BM_CLICK)` + `WM_NULL` 等待 | 否 | 否 | 返回值 | `windows-uia.ps1:882-902`；实测 `dev-notes.md:211` |
| 2 | Win32 输入框 | `EM_SETSEL` + `EM_REPLACESEL`（`SendMessageTimeout`）；后台打字时用 `GetGUIThreadInfo` 找到后台线程里的焦点框 | 否 | 否 | 比较文本长度 / 读回值 | `windows-uia.ps1:904-945,362-367,2858-2878`；`dev-notes.md:213-214` |
| 3 | 自带滚动条的控件 | `WM_VSCROLL` / `WM_HSCROLL`，或 UIA ScrollPattern | 否 | 否 | — | `windows-uia.ps1:2044` 起 |
| 4 | UIA 模式 | Invoke / Toggle / SelectionItem / ExpandCollapse / Value | 可能（ListView 项的 Invoke 是一次真实双击） | 可能（托管 Win32 代理和 Chromium 会自己切前台） | 返回值 | `windows-uia.ps1:1954-1994`；`dev-notes.md:210` |
| 5 | 投递鼠标/字符消息 | `PostMessage(WM_*BUTTON*)`、`WM_CHAR` | 否 | 否 | **不能**，结果标 `verified:false`；WinUI 3 会丢，Chromium/Electron/UWP 可能忽略 | `windows-uia.ps1:1136-1189`；`dev-notes.md:69-72`；`README.md:285` |
| 6 | 真实输入 | `SetCursorPos` + SendInput 按键事件；剪贴板 + `SendKeys ^v`；SendInput `KEYEVENTF_UNICODE`；`SendKeys` 组合键 | 是 | 是（借用） | 返回值 | `windows-uia.ps1:173-200,1862-1918,2938-2966` |
| 7 | 网页 | CDP `Input.dispatchMouseEvent` / `dispatchKeyEvent` / `insertText` | 否 | 否（JS 弹窗有时会短暂到前台） | 动作后看页面变化 | `browser.mjs:1356-1363,1616,1639`；`README.md:269` |

- 路径 4 外面包了一层守卫：调用前记下前台和鼠标，调用后如果被 provider 改了就还回去；但如果调用打开了菜单、下拉列表或对话框，就不还（`windows-uia.ps1:849-880`）。
- 鼠标移动用 `SetCursorPos`，不用 SendInput 的绝对坐标：作者在多显示器上测到 ABSOLUTE 归一化把 X 减半（`dev-notes.md:66-68`）。
- **先安静、再升级。** 服务端用 `isQuietCall` 把调用分成安静和需要真实输入两类（`browser.mjs:213-224`）。元素动作先带 `_wcuQuiet` 跑；后端发现要真实输入时，在发出任何输入之前抛 `WCU_NEEDS_INPUT`，服务端再按正常租约重跑（`server.mjs:860-869`；`windows-uia.ps1:2107-2110`）。安静的调用不启动接管助手，所以没有屏幕提示，也不挂 Esc（`takeover.mjs:124-136`）。

### 4.2 “借用前台”的完整流程

1. **记录**：用户当前的前台窗口、鼠标位置、目标进程已有的弹出窗口（`Start-Input`，`windows-uia.ps1:786-804`）。
2. **激活**：先用不按键的方法（本线程 `AttachThreadInput` 到前台线程，再 `BringWindowToTop` + `SetForegroundWindow`），不行再按一下 Alt 解前台锁；完成后核对前台是否真的变了，没变就不发输入（`:633-679,380-389`）。
3. **落点检查**：`WindowFromPoint` 取根窗口，不是目标或目标同进程的窗口就抛 `POINT_COVERED`，什么也不发（`:806-819`）。DSH 自己的提示层是分层、鼠标穿透的，`WindowFromPoint` 会跳过它（`dev-notes.md:215`）。
4. **按键前再查一次前台**，用户在这期间切走了就不发（`Assert-InFront`，`:693-704`）。
5. **归还**：先发两次 `WM_NULL` 等目标处理完输入；鼠标还在 DSH 放的位置才移回去；前台还给用户原来的窗口。以下情况不还：`activate:true`；目标线程处于菜单模式（`GetGUIThreadInfo` 标志 `0x1C`）；出现了动作前没有的弹出窗口；用户已经切到别的窗口；原来的窗口已经关了（`Complete-Input`，`:821-847,371-376`）。
6. **记住 DSH 的窗口**：前台还给用户以后，不指定窗口的后续输入和观察仍对准 DSH 刚操作的窗口，按宿主的轮次标识 `_wcuSeries` 区分（`:762-770`；`architecture.md:15`）。

**停止**：接管助手的低级键盘钩子只看物理按键（忽略带注入标志 `0x12` 的事件），按 Esc 或自定义快捷键就置一个命名事件；后端在每次发输入前检查它（`windows-takeover.cs:269-277,305-316`；`windows-uia.ps1:104-122`）。左上角急停（鼠标贴角 500 ms）还在代码里（`windows-uia.ps1:1094-1127`），但 MCP 服务调用时总是关掉它（`server.mjs:886`）。

### 4.3 截图能不能截被遮挡的窗口

| 顺序 | 条件 | 方法 | 被遮挡能截 | 证据 |
|---|---|---|---|---|
| 1 | 目标是前台、完全在屏幕内、Z 序上方没有相交的可见窗口 | 屏幕裁剪；截后再查一次前台、Z 序和位置，变了就丢掉 | — | `windows-uia.ps1:2135-2156,2217-2234` |
| 2 | 其他情况 | PrintWindow（`PW_RENDERFULLCONTENT`），全黑视为失败 | 能 | `:2158-2191,2236` |
| 3 | PrintWindow 失败 | WGC 一帧，最多等 4 s；编译不了（缺 .NET Core 引用包）就整台机器禁用 | 能 | `:2241-2259,1511-1552` |
| 4 | 都失败 | 截窗口所在的屏幕区域，标 `occludedPossible: true` | 不能（截到的可能是上面的窗口） | `:2261-2280` |
| 5 | 最小化或隐藏 | 退到整屏截图并标 `windowCaptureFailed`；OCR 直接报错 | 不能 | `:2285-2295,3204` |

- 先用屏幕裁剪的原因：0.3.0 在常驻进程里对网易云（CEF）做 PrintWindow，返回成功但下半部分纯白，独立进程却正常；原因没查清，于是前台无遮挡时改用真实屏幕像素（`dev-notes.md:160-164`）。
- 浏览器后台标签页：不在绘制的标签页单次 `Page.captureScreenshot` 永远不返回，每 300–500 ms 再请求一次，0.4–6 s 内能拿到；正在绘制的约 50 ms（`browser.mjs:1766-1784`）。无头模式下每次动作和截图前把标签页切到最前，约 13 ms（`browser.mjs:1244-1252`）。
- 从代码推断（**未实测**）：提示层是覆盖整块显示器的可见顶层窗口，`TopLevelWindows()` 不过滤它（`windows-uia.ps1:246-250`），所以提示显示时第 1 条的“上方无相交窗口”很可能不成立，会走 PrintWindow。另外“远程与录屏可见”默认打开（`settings.mjs:17`），此时提示层不排除在截图之外（`windows-takeover.cs:370,511`），整屏截图会带上边缘光晕和提示条。

### 4.4 隔离桌面、虚拟显示器、第二会话、虚拟光标

- **都没有。** 代码里搜 `CreateDesktop`、`SwitchDesktop`、`OpenDesktop`、`WTS`、`IddCx`、`VirtualDisplay`，0 处；“RDP”只出现在“UIA 看不到的应用”的注释里（`windows-uia.ps1:1194,2604,3178`）。
- **桌面上的鼠标标识**：一个分层、鼠标穿透、不激活、置顶的小窗口，每 35 ms 读一次 `GetCursorPos` 跟着系统光标移动，旁边画 “DSH” 标签。注释写明系统光标仍归 Windows 管（`windows-takeover.cs:325-333,348-349,365-368`）。它表示“现在是 DSH 在用你的鼠标”，不是一个独立的光标。
- **屏幕提示**：每块显示器一个全屏分层窗口，画边缘光晕和提示条；可选 `SetWindowDisplayAffinity(WDA_EXCLUDEFROMCAPTURE)`（`0x11`）让远程和录屏看不到（`windows-takeover.cs:495-520,511`）。
- **浏览器里的小箭头**：画在页面的隔离 world（`Page.createIsolatedWorld`）的封闭 shadow root 里，鼠标事件穿透，截图前隐藏（`browser.mjs:141-143,173,1101,1756-1762`；`architecture.md:27`）。这是唯一一个和用户鼠标互不干扰的“第二个光标”。
- agent 浏览器默认可见、不抢前台（新标签页 `background: true`，`browser.mjs:1440`），用临时配置和隔离的浏览上下文；从不连接用户自己的浏览器（`safety.md:47`；`dev-notes.md:251`）。

### 4.5 哪些场景必须前台

| 场景 | 原因 | 证据 |
|---|---|---|
| 按坐标点击、拖拽、滚轮（目标不是带滚动模式的元素） | 走系统输入队列，落在指针下的窗口 | `server.mjs:234`（click 说明）；`windows-uia.ps1:2688-2700,2825-2846` |
| 非 Win32 输入框里打字（WinUI、WPF、Chromium 等） | 自动模式只对 Win32 Edit 用 `EM_REPLACESEL`，其他走剪贴板粘贴 | `windows-uia.ps1:2855-2881` |
| 组合键、功能键 | 后台只能投递单个可打印字符 | `windows-uia.ps1:2945-2964` |
| WinUI 3、UWP、Chromium、Electron 的后台投递 | 应用丢弃或忽略合成消息 | `dev-notes.md:69-72`；`README.md:285` |
| 被遮挡且 PrintWindow/WGC 都失败的窗口的截图和 OCR；最小化的窗口 | 只能截屏幕像素；最小化的要先还原 | 4.3 |
| 托管 Win32 代理、Chromium 的大部分 UIA 模式调用 | provider 自己切前台（随后被还回去） | `dev-notes.md:210` |
| 浏览器里的 iframe 内容 | 浏览器通道读不到，退回桌面 UIA | `README.md:269` |

---

## 5. 启示

“可行性”和“改动”是对本仓库和 gui-agent 现状的判断；标“工程判断”的条目不是 dsh 的证据，需要实测。

### 5.1 给桌面 gui-agent（`D:\gui-agent`）

现状：点击是 SendInput 绝对坐标移动加按下抬起，不还鼠标（`src/gui_agent/environments/windows/win32_backend.py:656-668`）；激活是 `SetForegroundWindow` 加一次 `AttachThreadInput`，不还前台（`:597-648`）；截图是整个虚拟屏的 BitBlt（`screenshot_capture.py:85-100`）；等稳定按 1000 ms 轮询（`orchestrator/settle.py:31`）；UIA 后端按 ADR-0007 第 8 条只读，不得调用 click/type/set-focus（`docs/adr/0007-uia-client-library-and-boundary.md:74-75`）；Edge 的 CDP 通道按 ADR-0018 只读（`docs/adr/0018-edge-trusted-state-channel.md:1,8-10`）。

| # | 做法 | dsh 证据 | 可行性 | 改动 | 怎么落到 gui-agent |
|---|---|---|---|---|---|
| 1 | **借用前台并归还**：动作前记前台窗口和鼠标；动作后发两次 `WM_NULL` 等目标处理完，鼠标没被用户动过才移回，前台还给用户；菜单模式、新弹窗、用户已切走时不还 | `windows-uia.ps1:786-847` | 高，只用已有的 Win32 调用，不碰只读 ADR | 小（`win32_backend.py`、`desktop.py` 各加一段） | `click` / `type_text` / `press_key` 包进 `begin_input` / `end_input`；结果里记 `foreground: returned/kept(原因)`、`pointer: returned/left`，进事件日志（ADR-0020）。和 S-031“每阶段只试一次、不循环抢焦点”（`win32_backend.py:601-602`）不冲突，但多了一次还前台的激活，要在 ADR-0003 里写明 |
| 2 | **落点检查**：`WindowFromPoint` → 根窗口，不是目标或同进程窗口就拒绝发输入（`POINT_COVERED`），报出挡住它的窗口；按键前再查一次前台 | `windows-uia.ps1:693-704,806-819` | 高 | 小 | 放在 SendInput 之前，失败按 ADR-0003 的停止语义处理。能拦住“通知弹窗刚好盖在目标上”这类静默点错 |
| 3 | **窗口级截图**：前台无遮挡用裁剪并截后复核；否则 PrintWindow（`PW_RENDERFULLCONTENT`）加黑帧检查；再不行 WGC；最后屏幕区域并标“可能被遮挡”。返回 `bounds` 和 `imageScale` 做坐标换算 | `windows-uia.ps1:2135-2333`；`dev-notes.md:73-75,104-106,160-164` | 中高。PrintWindow 对 DirectComposition 会黑屏、对 CEF 偶尔只画一半；WGC 要 Win10 1903+ | 中（`screenshot_capture.py`、`layered_observation.py` 的坐标映射、相关测试） | 被遮挡的目标窗口也能看；像素少了，OCR 也会快（dsh 的 PNG 从 3.8 MB 到 91 KB；gui-agent 上的 OCR 收益**未确认**）。`test_s044_data_boundary.py:90` 断言脚本里有 BitBlt，改时一起看 |
| 4 | **进 UIA 前探活、卡死换 worker**：`SendMessageTimeout(WM_NULL, SMTO_ABORTIFHUNG, 200 ms)`；超时后杀掉 UIA 进程、懒重建，补一张截图但结果仍标失败 | `windows-uia.ps1:270-275`；`server.mjs:890-909,999-1016` | 中。探活是只读的 Win32 调用，ADR-0007 允许；换 worker 要把 UIA 放到子进程或可放弃的线程里 | 探活小；换 worker 中 | 先只加探活：目标无响应时直接退到截图 + OCR，不进 COM |
| 5 | **Chromium / Edge 无障碍树预热**：对每个可见的 `Chrome_RenderWidgetHostHWND` 读一次页面根节点的 `ControlType`，每 50 ms 查，最多 1.5 s；窗口内无名的 Chrome Pane 不计深度 | `windows-uia.ps1:2386-2437`；`dev-notes.md:186-203` | 高，纯读取 | 小 | 如果 gui-agent 在 Edge 或 Electron 窗口里经常读到稀疏的树（**未确认**），这条能省掉一大批退到 OCR/VLM 的情况 |
| 6 | **后台控件动作**：`BM_CLICK`、`EM_REPLACESEL`、`WM_VSCROLL`、UIA Invoke/Value，外面包“还前台和鼠标”的守卫；需要真实输入时显式升级 | `windows-uia.ps1:849-945,1954-1994`；`server.mjs:860-869` | 中。只对 Win32/WinForms 和部分 UIA 控件有效；托管代理和 Chromium 仍会自己切前台 | 中到大；**要改 ADR-0007 第 8 条只读不变量，项目所有者决定** | 这是桌面应用上唯一“不动鼠标、不切前台”的路。先只开 `BM_CLICK` 和 `EM_REPLACESEL` 两种，范围最窄 |
| 7 | **浏览器任务走 CDP 输入**：DSH 自己的浏览器（临时配置、隔离上下文），`Input.dispatchMouseEvent` / `insertText`，后台标签页反复请求截图，动作后按加载、网络、DOM 静默分段等待 | `browser.mjs:483-488,782-786,1214-1233,1356-1363,1440,1766-1784` | 中。要管一个浏览器进程和它的配置目录 | 大；**要改 ADR-0018 的只读不变量，并过安全评审** | 这是 dsh 里唯一真正的“两个光标”：网页任务完全不占用户的鼠标和前台。gui-agent 现在已经有回环 CDP 只读通道，传输层可以复用 |
| 8 | **接管提示和 Esc 停止**：分层、鼠标穿透、不激活的提示窗口；低级键盘钩子只认物理按键；后端每次发输入前检查一个命名事件 | `windows-takeover.cs:145-166,305-316,365-372`；`windows-uia.ps1:104-122` | 高 | 中 | gui-agent 自己整屏截图，提示层要设 `WDA_EXCLUDEFROMCAPTURE`，否则会被 BitBlt 截进去污染 OCR（dsh 默认反而让它可见，见 4.3） |
| 9 | **SendInput 绝对坐标在多显示器上的坑**：作者测到 ABSOLUTE 把 X 减半，改用 `SetCursorPos` | `dev-notes.md:66-68` | — | 只需验证 | gui-agent 用了 `ABSOLUTE \| VIRTUALDESK`（`win32_backend.py:658-661`），作者当时有没有加 `VIRTUALDESK` 没写；在多屏、不同缩放下测一次落点 |

不建议照搬：

- **桌面 `wait_for` 的 250 ms 轮询、每轮整跑 OCR**：gui-agent 的 `settle` 本来就是轮询（1000 ms），换成 dsh 的做法只是更频繁。真要提速应该换成事件（UIA 结构变化事件或 `SetWinEventHook`），但 dsh 没做过，**没有证据**，要单独实测（工程判断）。
- **每张图存 PNG 再读回、每次新建 OCR 引擎**：这是 PowerShell 和 MCP 传图的限制带来的，gui-agent 进程内直接传内存缓冲更快。
- **常驻 PowerShell 后端、DLL 编译缓存**：解决的是 PowerShell 启动慢，gui-agent 是 Python 进程内调用，不需要。
- **真正后台**（模型在用户看不到的地方操作原生应用）：dsh 没有给出方案。可选的路线是第二个会话（RDP 回环、另一个用户账户）、虚拟机或 Windows Sandbox、`CreateDesktop` 隔离桌面，各自对截图、输入和 UIA 的影响都要单独调研，本报告不下结论。

### 5.2 给手机端 phone-agent（本仓库）

现状：动作先 `performAction`，不行退回手势（`docs/ARCHITECTURE.md` §5 第 2 步；`AgentService.kt:265-277`）；截图用无障碍 `takeScreenshot(displayId)`，主屏（`AgentService.kt:171`）；等稳定是事件加 300 ms 静默（§5 第 3 步）；`Device.capture` 和 `Frame` 已带 `displayId`，虚拟屏留到以后（`docs/ARCHITECTURE.md:251`）。

先说边界：dsh 能在桌面上“少打扰”，靠的是 Windows 允许对不在前台、甚至被盖住的窗口发消息和截图。安卓上 `performAction` 相当于 dsh 的 UIA 模式调用，不需要触摸；但目标 App 必须正显示在某块屏幕上：退到后台的 App 不在 `getWindows()` 里，界面也不再更新；`takeScreenshot` 截整块屏，手势注入整块屏。安卓 14 新增的 `takeScreenshotOfWindow` 能截单个窗口，但同样只对屏幕上的窗口，Neo5 是安卓 13 用不了。以上是工程判断，没有在 dsh 里找到对应证据。所以 dsh 的经验在手机上换不来“主屏后台运行”，下面只列能直接用上的。

| # | 做法 | dsh 证据 | 可行性 | 改动 | 怎么落到 phone-agent |
|---|---|---|---|---|---|
| 1 | **落点被别的窗口挡住就停**：手势前查点上的最顶层窗口属不属于目标 App | `windows-uia.ps1:806-819`（`POINT_COVERED`） | 高。`getWindows()` 按层级返回窗口和边界，需要 `flagRetrieveInteractiveWindows` | 小（`AgentService` 手势前一段检查；core 里加一种停止原因和测试） | 系统弹窗、悬浮球、分身选择框盖在目标上时，不再盲点。和“只操作 `requires` 里的 App”的不变量一致 |
| 2 | **自己的叠加层不进截图**：截图前隐藏或让开 | `browser.mjs:1756-1762`（截图前隐藏页面里的箭头）；`windows-takeover.cs:370`（可选排除截图） | 高 | 小 | 计划中的悬浮面板（无障碍叠加窗口）会不会被 `takeScreenshot` 截进去，**未确认，要真机测**。截进去的话，截图前把面板设为 `INVISIBLE`，或固定放在 OCR 区域以外；微信的整屏 OCR 尤其要注意 |
| 3 | **安静动作优先，需要真实触摸时再升级，并记下用了哪条** | `server.mjs:860-869`；`browser.mjs:213-224`；`windows-uia.ps1:2107-2110` | 高，设计里已经是“先 `performAction` 再手势” | 小 | 轨迹里每步记 `performAction` 还是手势；只有手势步骤才需要“用户别碰屏幕”的提示。微信几乎全是手势，提示在这类任务里一直亮 |
| 4 | **结果分“已发送”和“已观察到”** | `server.mjs:1046-1052`；`architecture.md:21` | 高 | 很小 | 已经由 `expect` 实现；运行清单里把“动作已发出、预期未满足”和“动作失败”分开显示，方便用户判断要不要补教 |
| 5 | **静默等待加一个硬上限** | `browser.mjs:487`（DOM 静默 250 ms，但最多等 1.5 s，之后交给验证） | 中 | 小 | `awaitSettle` 现在的上限是这一步的 `timeoutMs`（默认 5 s）。一直在动的界面（相机预览、动画、直播）可以在 1–1.5 s 后停止等静默，直接去查预期。数值要真机定 |
| 6 | **用户接管就停** | `windows-takeover.cs:305-316`（只认物理按键的 Esc） | 中 | 中 | 悬浮面板上的“停止”已在计划里。检测用户自己触摸屏幕需要什么无障碍能力、会不会和手势冲突，**未确认** |
| 7 | **真后台只能靠第二块屏** | dsh 自己也没有隔离桌面（4.4） | 中，需要 Shizuku 或无线调试 | 大，项目所有者决定 | 维持 `docs/ARCHITECTURE.md:251` 的计划。安卓 11 起 `takeScreenshot` 和手势可以指定 `displayId`，节点可以跨屏取，这是安卓上和 dsh 浏览器通道最接近的形态：App 在副屏上跑，用户的主屏和手指不受影响。微信界面树为空，只有这条路能后台；副屏上 App 是否保持 RESUMED、是否正常绘制，要真机验证（工程判断） |

不建议照搬：

- **250 ms 轮询 + 每轮整屏 OCR 的 `wait_for`**：手机截图最小间隔约 320–410 ms（`docs/ARCHITECTURE.md:204`），整屏 OCR 在后台约 4.5 s（`:82`），这样轮询既慢又费电。保持现在的事件驱动等待。
- **动作内部写死的 30–120 ms sleep**：违反本仓库“不写固定 sleep”的约定；手势完成本来就有回调（`AgentService.kt:269-277`）。
- **剪贴板粘贴、`EM_REPLACESEL` 这类文字输入**：动作集不含文字输入和发送，新增要先过安全评审（`CLAUDE.md` 不变量）。

---

## 6. 本报告没覆盖或无法确认的

- 决策模型在本地还是远程、每步模型耗时：在 DSH 宿主里，不在这个仓库。
- CPU、GPU、内存、功耗：仓库里没有任何数字。最值得测的是接管助手常驻后的空闲 CPU（35 ms 定时器 + 低级键盘钩子），以及一次 `snapshot`（整屏 + 1600 宽 + PNG）和一次 `ocr`（1920 宽）的耗时。都需要启动它的后端并截屏，按交接要求先问用户。
- WinRT OCR 的推理设备：未确认。耗时只在合成图片上测过（2.2），真实界面未测。
- 提示层显示时屏幕裁剪路径是否总被跳过、整屏截图是否带上提示层：从代码推断（4.3），没有测试覆盖，未确认。
- OpenAI 的 computer use 怎么实现“两个光标、后台运行”：dsh 只引用了 Codex 的公开文档，说明公开资料里没写私有实现（`docs/wiki/mac-codex-computer-use.md:21-27`）；dsh 的浏览器提示是“as Codex's Browser Use does”（`architecture.md:27`）。具体实现未确认。
