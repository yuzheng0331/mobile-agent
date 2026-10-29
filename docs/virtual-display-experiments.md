# 后台虚拟屏实验

目的：先弄清在 Neo5（Android 14）上能不能做“任务在虚拟屏上跑、用户照常用主屏”，再决定后台模式做不做、怎么做。设计背景见 `docs/research/next-phase-design-review.md` 第 6 节。

按概念组织：每一项是一个要回答的问题，测完把结果覆盖写进“结果”一栏，不按时间追加。

## 1. 条件

- 手机：vivo Neo5，Android 14。被操作的 App 只用小号。
- 建屏：`tools/probe/vd/VirtualDisplayTool.java`，用 `app_process` 以 shell 身份运行。以后换成 Shizuku 时也是同一个身份，所以这里测出来的结论能直接用。虚拟屏的输出接到它自己的 `ImageReader`，只打印帧数、帧率和平均亮度，不保存画面。
- 默认标志沿用 scrcpy `--new-display` 在 Android 14 上用的那一组：`PUBLIC, OWN_CONTENT_ONLY, SUPPORTS_TOUCH, ROTATES_WITH_CONTENT, DESTROY_CONTENT_ON_REMOVAL, TRUSTED, OWN_DISPLAY_GROUP, ALWAYS_UNLOCKED, TOUCH_FEEDBACK_DISABLED, OWN_FOCUS, DEVICE_DISPLAY_GROUP`。第五个参数可以改成别的组合，用来对比单个标志的作用。
- 无障碍探针：debug 版里的 `DisplayProbeReceiver`，按 `displayId` 读窗口、截图、手势、节点点击。只记数量、尺寸、结果，不记文字。

## 2. 问题和结果

| # | 问题 | 怎么测 | 结果 |
|---|---|---|---|
| 1 | shell 能不能建出虚拟屏，有没有画面 | 运行建屏工具，看打印的 display id；启动 App 后帧率大于 0、亮度不为 0 | 待测 |
| 2 | 微信、QQ、小红书、抖音能不能启动到虚拟屏，并正常显示 | `am start --display <id>`；看帧率、亮度、`windows` 探针里的包名 | 待测 |
| 3 | 无障碍能不能读到虚拟屏上的窗口和界面树 | `windows` 探针：虚拟屏这一行有没有目标 App，节点数（QQ 应有几百个，微信本来就几乎为空） | 待测 |
| 4 | 无障碍能不能截虚拟屏 | `shot` 探针：尺寸是否等于虚拟屏尺寸、亮度是否不为 0、耗时多少 | 待测 |
| 5 | 手势能不能落到虚拟屏 | `tap` 探针点一个无害位置（比如底部标签），再用 `windows` / `shot` 看画面变了没有 | 待测 |
| 6 | 节点动作（`performAction`）能不能作用到虚拟屏 | QQ 上 `click` 探针点底部“联系人”标签 | 待测 |
| 7 | 会不会抢主屏的焦点 | 启动前后各看一次 `dumpsys window` 里的 `mTopFocusedDisplayId` 和 `mCurrentFocus`；你在主屏的输入框里打字，键盘是否照常出现、字是否打进主屏 | 待测 |
| 8 | 去掉 `OWN_FOCUS` 以后是否开始抢焦点 | 同第 7 项，建屏时不带这个标志 | 待测 |
| 9 | 同一个 App 能不能同时在两块屏上 | 微信在虚拟屏上时，你在主屏点开微信，看它最后出现在哪块屏（`dumpsys activity activities`） | 待测 |
| 10 | 锁屏后虚拟屏还能不能跑 | 按电源键锁屏：建屏工具的帧率是否还在、`shot` / `tap` 是否还能用；再测不带 `ALWAYS_UNLOCKED` 的情况 | 待测 |
| 11 | 对前台的性能影响 | 你在主屏滑动一个列表，用 `dumpsys gfxinfo <主屏 App>` 统计掉帧；对比有没有虚拟屏、虚拟屏上有没有在跑 OCR；同时看 `top` 和温度 | 待测 |
| 12 | 降分辨率、降帧率有没有用 | 建屏用 540x1200、帧率 30，重复第 11 项 | 待测 |
| 13 | 被操作的 App 是否表现异常（提示、闪退、功能不可用） | 第 2 项启动后在虚拟屏上翻几页；有没有异常提示 | 待测 |

## 3. 重跑步骤

```bash
# 1. 构建建屏工具并推到手机；装 debug 版（含探针）
bash tools/probe/vd/build.sh --push
export JAVA_HOME="$(cygpath -w ~/.jdks/jbr-21.0.11)" && ./gradlew :app:installDebug

# 2. 建屏（一直开着；Ctrl-C 结束，虚拟屏随之消失）。宽、高、dpi 先用主屏的：adb shell wm size; adb shell wm density
adb shell CLASSPATH=/data/local/tmp/vd.jar app_process / dev.phoneagent.vd.VirtualDisplayTool 1080 2400 440
#    可选第 4 个参数是帧率，第 5 个是标志组合（逗号分隔，名字去掉 VIRTUAL_DISPLAY_FLAG_ 前缀）

# 3. 在另一个终端把 App 启动到虚拟屏（<id> 是上一步打印的 display id）
adb shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER com.tencent.mm | tail -1
adb shell am start --display <id> -n <上一行的组件名>

# 4. 无障碍探针，结果在 logcat
adb shell am broadcast -n dev.phoneagent.app/.DisplayProbeReceiver --es do windows
adb shell am broadcast -n dev.phoneagent.app/.DisplayProbeReceiver --ei display <id> --es do shot
adb shell am broadcast -n dev.phoneagent.app/.DisplayProbeReceiver --ei display <id> --es do tap --ei x 540 --ei y 2330
adb shell am broadcast -n dev.phoneagent.app/.DisplayProbeReceiver --ei display <id> --es do click --es label 联系人
adb logcat -d -s PhoneAgent

# 5. 焦点和任务所在的屏
adb shell dumpsys window | grep -E "mTopFocusedDisplayId|mCurrentFocus"
adb shell dumpsys activity activities | grep -E "Display #|topResumedActivity"
```

## 4. 结论

待测完再写：后台模式做不做、用哪组标志、分辨率和帧率怎么定、哪些 App 能用。
