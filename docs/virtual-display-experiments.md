# 后台虚拟屏实验

目的：弄清在 Neo5（Android 14）上能不能做到“任务在虚拟屏上跑、用户照常用主屏”，再决定后台模式做不做、怎么做。设计背景见 `docs/research/next-phase-design-review.md` 第 6 节。

按概念组织：每一项是一个要回答的问题，测完把结果覆盖写进“结果”一栏，不按时间追加。

## 1. 条件

- 手机：vivo Neo5，Android 14，屏幕 1080x2400、480 dpi。被操作的 App 都登录小号。adb 走无线调试。
- 建屏：`tools/probe/vd/VirtualDisplayTool.java`，用 `app_process` 以 shell 身份运行。以后换成 Shizuku 也是同一个身份，所以这里的结论能直接沿用。虚拟屏的输出接到工具自己的 `ImageReader`，只打印帧数、帧率和平均亮度，不保存画面。
- 默认标志：scrcpy `--new-display` 在 Android 14 上用的那一组，再加 `STEAL_TOP_FOCUS_DISABLED`（原因见第 7、8 项）：`PUBLIC, OWN_CONTENT_ONLY, SUPPORTS_TOUCH, ROTATES_WITH_CONTENT, DESTROY_CONTENT_ON_REMOVAL, TRUSTED, OWN_DISPLAY_GROUP, ALWAYS_UNLOCKED, TOUCH_FEEDBACK_DISABLED, OWN_FOCUS, DEVICE_DISPLAY_GROUP, STEAL_TOP_FOCUS_DISABLED`。
- 无障碍探针：debug 版里的 `DisplayProbeReceiver`，按 `displayId` 读窗口、截图、整屏 OCR、找节点、做手势、点节点。日志只记数量、尺寸、位置和结果，不记文字。
- 测量时间：2026-10-29。

## 2. 问题和结果

| # | 问题 | 结果 |
|---|---|---|
| 1 | shell 能不能建出虚拟屏，有没有画面 | **能**。上面的标志全部被系统接受（含 `TRUSTED`、`ALWAYS_UNLOCKED`、`OWN_FOCUS`、`STEAL_TOP_FOCUS_DISABLED`）。显示模式 60 Hz；画面有变化时才出帧，视频约 29–30 fps，静止时为 0 |
| 2 | 微信、QQ、小红书、抖音能不能启动到虚拟屏并正常显示 | **四个都能**（`am start --display <id>`），画面正常。抖音在虚拟屏上照常播放视频，**声音从手机扬声器放出来** |
| 3 | 无障碍能不能读到虚拟屏的窗口和界面树 | **能**。`getWindowsOnAllDisplays` 里虚拟屏一行：QQ 290 个节点，小红书 171，抖音 701，微信 1（和主屏上一样几乎为空） |
| 4 | 无障碍能不能截虚拟屏 | **能**。`takeScreenshot(displayId)` 返回 1080x2400；平时 124–208 ms，抖音放视频时 577 ms，锁屏时 1016 ms。微信靠截图加 OCR 也读得到：整屏 34 个框，底部“通讯录”位置正确 |
| 5 | 手势能不能落到虚拟屏 | **能**。`setDisplayId` 的点击：QQ 从“联系人”点回“消息”；微信点“通讯录”后，OCR 读到“新的朋友” |
| 6 | 节点动作能不能作用到虚拟屏 | **能**。QQ 对“联系人”标签 `performAction(CLICK)`，页面切换了 |
| 7 | 会不会抢主屏的焦点（只用 scrcpy 那组标志） | **会**。每个屏各有自己的焦点窗口（`OWN_FOCUS` 生效），但 `am start` 和虚拟屏上的任何输入（包括我们的点击）都会把“顶层焦点屏”（`mTopFocusedDisplayId`）切到虚拟屏。用户在主屏打字，字能打进去，**回车却失效**：字通过输入法连接送进输入框，回车是按键事件，按键事件发给了顶层焦点屏上的 App |
| 8 | 加上 `STEAL_TOP_FOCUS_DISABLED` 以后 | **解决**。`am start` 和点击都不再改变顶层焦点屏（一直是 0）；用户在主屏打字、回车都正常。所以这个标志必须加 |
| 9 | 同一个 App 能不能同时在两块屏上 | **不能**。QQ 在虚拟屏上时，用户在主屏点开 QQ，QQ 的任务（同一个任务号）整个被拉回主屏，虚拟屏上就没有它了。再用 `am start --display` 时，会在虚拟屏上新建一个任务 |
| 10 | 锁屏后虚拟屏还能不能跑 | **能，靠 `ALWAYS_UNLOCKED`**。锁屏（有密码）、屏幕熄灭（`Dozing`，`isKeyguardShowing=true`）时，虚拟屏照常出帧，截图、读树、`am start`、节点点击、手势全部可用，手机始终保持锁定。去掉这个标志，锁屏界面（systemui 窗口）会盖在虚拟屏上，App 被挡住 |
| 11 | 对前台的性能影响 | **几乎没有**。主屏在“设置”里滑动 20 秒（约 1100 帧），见第 3 节表格 |
| 12 | 降分辨率、降帧率的效果 | 对主屏流畅度影响不大。540x1200 的虚拟屏上，整屏检测推理 510–555 ms，全分辨率时（微信，同样没有输入法加速）是 1839 ms：像素少 4 倍，检测快约 3.4 倍。识别耗时看框的多少，这次两边是不同的 App，不能直接比。**准确率很可能会掉**：这等于把“检测最长边”降到 1200，前台实验里降到 1600 就掉准确率了（`docs/ocr-experiments.md` 3.2）。唯一的区别是虚拟屏按低 dpi 原生渲染，而不是把大图缩小，哪种读得准还没测 |
| 13 | 被操作的 App 是否表现异常 | 本次没看到：主屏没有弹窗、卡顿、画面跳走，也没有这几个 App 的异常提示；虚拟屏上的 App 没有报错提示。没测：长时间运行、风控（只能小号长期观察）、支付等 `FLAG_SECURE` 页面 |
| — | 虚拟屏撤掉时，上面的 App 去哪 | 随之关闭（`DESTROY_CONTENT_ON_REMOVAL`），不会跳到主屏 |

## 3. 主屏流畅度（第 11、12 项）

用户在主屏“设置”里只上下滑动 20 秒，`dumpsys gfxinfo com.android.settings` 统计：

| 虚拟屏负载 | 帧数 | 掉帧 | 90% / 95% 帧耗时 | 同时跑的整屏 OCR |
|---|---|---|---|---|
| 没有虚拟屏（对照） | 1102 | 0.09% | 8 / 9 ms | — |
| 1080x2400，放抖音视频，连续跑 OCR | 1097 | 0.46% | 8 / 9 ms | 3 次（全分辨率，每次数秒） |
| 540x1200、30 fps，放抖音视频，连续跑 OCR | 1176 | 0.68% | 7 / 11 ms | 4 次（每次约 1.0 s） |

掉帧都不到 1%，用户在主屏上感觉不到。OCR 次数少，是因为测试循环每轮还要发广播、等 2 秒，不代表 OCR 的上限。

## 4. 在虚拟屏上跑任务包

后台模式的第一版（`app/.../shell/`、`AgentService.display`）在 Neo5 上用同样的任务包跑：

| 任务包 | 结果 | 总耗时 |
|---|---|---|
| QQ 最新消息 → 抖音（界面树） | 6 步全部成功，剪贴板里是 QQ 最新消息 | 约 12 s |
| 微信最新消息 → 小红书（截图 + OCR） | 6 步全部成功 | 约 19 s（第 1 步含首次加载 OCR 约 7 s） |

跑通之前遇到的问题：
- **虚拟屏没有状态栏，界面整体高 84 像素**：微信标题在主屏是 y 120–174，在虚拟屏是 37–91，列表也上移 83 像素，底部标签不变。任务包里按屏幕比例写的区域因此对不上（第 2 步找不到标题“微信”）。加 `SHOULD_SHOW_SYSTEM_DECORATIONS` 也没用：vivo 只给虚拟屏加了导航栏，没有状态栏，底部标签反而上移。**做法**：虚拟屏的高度减去主屏状态栏的高度（2400 − 84），截图上面补 84 像素黑边，界面树坐标下移 84，点击时再上移 84。这样任务包看到的坐标和主屏完全一样，不用改包。
- **toast 不带虚拟屏的 id**：微信的“已复制”提示，事件里的 displayId 不是虚拟屏，被“只看任务所在的屏”的过滤丢掉了，第 5 步因此以为复制没生效。现在只有“界面变化”事件按屏过滤，toast 不过滤。**这个提示会显示在用户的主屏上**（系统统一在主屏画 toast），用户能看到“已复制”弹出来。
- **截图内存泄漏**（前台也有，后台截图更频繁所以暴露了）：系统在截图回调执行完以后，还长时间留着回调对象，回调里存着的 10 MB 截图数组就释放不掉。一次后台运行里 Java 堆从 11 MB 涨到 256 MB，然后崩溃（OutOfMemoryError）。改成回调只经过一个“槽”转交截图、取走后清空以后，整个运行期间堆稳定在约 5.5 MB。

## 5. 结论

**后台模式可行**，做法定为：
- **身份**：Shizuku（或 adb）的 shell 身份建屏、启动 App；读树、截图、手势、点击走我们现有的无障碍服务，按 `displayId` 区分。
- **标志**：第 1 节那组，**`STEAL_TOP_FOCUS_DISABLED` 和 `ALWAYS_UNLOCKED` 都要**。前者保证用户在主屏打字不受影响，后者让任务在锁屏时也能跑。
- **锁屏能跑**改变了定时触发的前提：主屏有锁屏密码时什么都做不了，虚拟屏可以。设计文档 5.3 节已相应更新。

**要处理的问题**：
- **同一个 App 抢屏**：用户在主屏打开任务正在用的 App，任务就失去目标。运行时要盯住目标 App 在哪块屏，被拉走就暂停、发通知，等用户用完再从“按状态继续”接上。
- **声音**：虚拟屏上 App 的声音从扬声器放出来。shell 身份可以用 `appops set <包名> PLAY_AUDIO ignore` 临时静音某个 App，任务结束再恢复（未测）。
- **授权**：Shizuku 需要无线调试启动，手机重启后要再启动一次。
- **角色模式没有返回键**：只能点界面上的返回节点。任务包中途写了 `back`、而那一页的界面树是空的（如微信聊天页），这一步会失败；要退到按坐标点左上角的返回图标（用户 2026-10-30 定）。每次任务结束销毁虚拟屏、下次新建，App 在新屏上从首页开始，所以“启动后退回首页”不受影响。试过启动时清空任务栈（`FLAG_ACTIVITY_CLEAR_TASK`）：微信窗口一直全黑（截图亮度 0），无障碍看不到这块屏上的任何窗口，不采用。

**接下来要测的**：
- 低分辨率虚拟屏上的 OCR 准确率：要在虚拟屏上按低 dpi 重新采一批带标注的截图（标注来自界面树，和现有的标准答案一样），再和“全分辨率截图缩小”比。没有比全分辨率加 2240 更准之前，虚拟屏用和主屏相同的分辨率。
- 小红书、抖音、夸克的任务包在虚拟屏上跑（QQ、微信已跑通，见第 4 节）。
- 让 shell 服务跟着 App 启动：现在要从电脑上启动，App 重装或崩溃后还要重启。
- 长时间运行和风控：小号上连续跑几天。
- `appops` 静音。

## 6. 重跑步骤

后台模式跑任务包：

```bash
# 装好 debug 版后，启动 shell 服务（App 重装、崩溃后要重新启动一次）
bash tools/probe/shell_start.sh
# 然后在手机上打开 Phone Agent，勾选“后台运行”，点任务
```

单项实验：

```bash
# 1. 构建建屏工具并推到手机；装 debug 版（含探针）
bash tools/probe/vd/build.sh --push
export JAVA_HOME="$(cygpath -w ~/.jdks/jbr-21.0.11)" && ./gradlew :app:installDebug

# 2. 建屏（一直开着；结束：adb shell pkill -f VirtualDisplayTool，虚拟屏随之消失）
#    参数：宽 高 dpi [帧率，0 为默认] [标志，逗号分隔，名字去掉 VIRTUAL_DISPLAY_FLAG_ 前缀]
adb shell CLASSPATH=/data/local/tmp/vd.jar app_process / dev.phoneagent.vd.VirtualDisplayTool 1080 2400 480

# 3. 在另一个终端把 App 启动到虚拟屏（<id> 是上一步打印的 display id）
adb shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER com.tencent.mm | tail -1
adb shell am start --display <id> -n com.tencent.mm/.ui.LauncherUI

# 4. 无障碍探针，结果在 logcat
adb shell am broadcast -n dev.phoneagent.app/.DisplayProbeReceiver --es do windows
adb shell am broadcast -n dev.phoneagent.app/.DisplayProbeReceiver --ei display <id> --es do shot
adb shell am broadcast -n dev.phoneagent.app/.DisplayProbeReceiver --ei display <id> --es do ocr --es label 通讯录
adb shell am broadcast -n dev.phoneagent.app/.DisplayProbeReceiver --ei display <id> --es do find --es label 消息
adb shell am broadcast -n dev.phoneagent.app/.DisplayProbeReceiver --ei display <id> --es do tap --ei x 135 --ei y 2366
adb shell am broadcast -n dev.phoneagent.app/.DisplayProbeReceiver --ei display <id> --es do click --es label 联系人
adb logcat -d -s PhoneAgent

# 5. 焦点和任务所在的屏
adb shell dumpsys window | grep -E "mTopFocusedDisplayId|mCurrentFocus"
adb shell dumpsys activity activities | grep -E "Display #|topResumedActivity"

# 6. 主屏流畅度一轮：用户在主屏滑动，可选在虚拟屏 <id> 上连续跑 OCR
bash tools/probe/vd/load_round.sh com.android.settings 20 [<id>]
```

Git Bash 里运行时，命令前加 `MSYS_NO_PATHCONV=1`，否则 `/data/local/tmp` 这类手机路径会被改写成 Windows 路径。
