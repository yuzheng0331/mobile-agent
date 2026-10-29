# Phone Agent

免费、侧载安装的安卓自动化 App：用户点一下任务（不做一句话路由，见架构文档决定记录），App 通过无障碍服务按**任务包**操作指定的几个 App。任务包只是数据，能做什么由 App 代码里注册好的找法、预期和动作决定。

当前状态（2026-10-30）：三个任务包（QQ → 抖音、夸克书签、微信 → 小红书）都能在真机上跑；微信的界面树是空的，靠截图 + OCR（识别在 GPU 上）。可选后台运行：任务在虚拟屏上跑，你照常用手机。各里程碑的状态见架构文档第 9 节，设计见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 结构

```
core/    纯 Kotlin，可在电脑上测试
  pack/Pack.kt       任务包格式（严格解析，不认识的字段直接报错）
  pack/Validator.kt  加载时校验：未知名字、参数格式、launch 了 requires 以外的 App
  run/Frame.kt       一轮感知的结果：界面树 + 按需、按区域缓存的 OCR；各层往同一帧里追加
  run/Device.kt      执行器眼中的手机（截取、启动、点/长按、返回、等稳定）；displayId 留给以后的虚拟屏
  run/Runner.kt      逐步执行：找 → 动 → 等稳定 → 查预期；输出不含界面内容的轨迹
  route/Router.kt    一句话 → 任务包（按别名匹配，匹配不唯一就不猜）
  ext/Registry.kt    扩展点：找法、预期、动作，都按名字注册
  ext/Builtins.kt    v1 内置：text / desc / id / listItem / lastMessage / ocrText / lastBubble / chatRow；anyText / gone / toast；launch / tap / long / back
  ocr/PpOcr.kt       PP-OCR 引擎（检测框、解码；推理后端可换），ocr/OcrScore.kt 判分
app/     Android：AgentService（无障碍服务，实现 Device）、MainActivity（任务列表）、LiteRtModels（GPU 识别）、
         AgentIme（加速输入法）、Background 和 shell/（后台模式）
packs/   官方任务包 JSON，构建时打进 App 的 assets
tools/probe/  电脑端工具：抓界面树和截图、OCR 标注与模型转换、虚拟屏实验、启动 shell 服务，见其 README
docs/    架构文档、OCR 实验记录、虚拟屏实验记录、调研（BetterGI / OneDragon / 下一阶段设计评审）
```

## 构建

需要 Android SDK 和 **JDK 17–21**。注意：新版 Android Studio 自带的 JBR 是 25，Gradle 8.11 跑不了，要在 Settings → Build Tools → Gradle → Gradle JDK 里选 21（本机是 `~/.jdks/jbr-21.0.11`）。

- Android Studio：File → Open 选本目录，构建 `app`。
- 命令行（Git Bash）：

  ```bash
  export JAVA_HOME="$(cygpath -w ~/.jdks/jbr-21.0.11)"
  ./gradlew :core:test :app:assembleDebug
  ```

  APK 在 `app/build/outputs/apk/debug/app-debug.apk`。`local.properties` 写 `sdk.dir`，不进仓库。

## 在手机上跑

1. 用文件管理器安装 APK（不要用 `adb install`，要走用户的路）。
2. 安卓 13 起：应用信息 → 右上角菜单 → 允许受限制的设置；然后在无障碍设置里开启 Phone Agent。
3. 电池设置里允许“后台高耗电”（vivo/iQOO）。不要在最近任务里划掉 Phone Agent：vivo 会连无障碍服务一起关掉，要重新打开。
4. 第一次跑任务、要打开另一个 App（如抖音）时，vivo 会弹“Phone Agent 想要打开 …”。这个弹窗 App 点不了（vivo 不让无障碍服务操作它），会在屏幕顶部提示你，并等最多 30 秒：请自己点“始终打开”，任务接着跑，之后同一个 App 不再弹。想撤销：设置 → 应用与权限 → 权限管理里找 Phone Agent 的“关联启动 / 应用跳转”（各版本叫法不同）。
5. 打开 App，点任务按钮。完成或停下时显示原因，以及每步“找 / 动 / 等 / 查”的耗时。
6. 可选，加速：设置 → 输入法 → 打开“Phone Agent 加速”。它不是键盘、不读也不打字，只在需要 OCR 的任务运行时被临时选中，OCR 快约 30%。
7. 可选，后台运行：勾选“后台运行”，任务在虚拟屏上跑，你照常用手机。不用电脑，只做一次：
   - 设置 → 关于手机 → 连点“版本号”7 次，打开开发者选项；
   - 在 Phone Agent 里点“配对无线调试”，允许通知；到开发者选项打开“无线调试”，点“使用配对码配对设备”；
   - 下拉通知栏，把 6 位配对码填进 Phone Agent 的通知。通知显示“已配对，后台运行已可用”就好了。

   之后：开发者选项要一直开着；配对时和授权丢了要拿回时要连着 Wi-Fi（不需要能上网），无线调试 App 会自己开关；重启后不用再做什么。没有授权时任务改在前台跑，并说明原因。

只用小号测试。

## 扩展

- **加一个任务**：先按架构文档第 8 节在真机上 probe 所有起点和分支，再写任务包；在 `packs/` 放 JSON，`core` 的测试会自动校验它（`shipped packs validate`）。字段见 `Pack.kt` 和 `packs/qq-latest-to-douyin.json`。
- **加一种找法或预期**：实现 `Finder` / `Expectation`，在 `Builtins.registry` 里按名字注册，并加测试。
- **加一种动作**（滚动、左右滑等）：同上，但要先过安全评审——动作集里不放输入和发送。
- **OCR**：引擎在 `core/ocr/PpOcr.kt`，模型的下载和生成见 `app/CLAUDE.md`，实验数据和重跑方法见 [docs/ocr-experiments.md](docs/ocr-experiments.md)。

## 不变量

- 不靠云端：感知和决策都在本机完成，不用大模型 API、云 OCR、云手机或云电脑，界面树、截图、OCR 文字不交给任何外部服务。Phone Agent 自己联网只做这些：App 更新、拉取任务包、调用任务包声明过的自有网站接口（界面上读到的内容不能填进这些请求），以及后台模式的启动器连这台手机自己的 adbd（127.0.0.1，不出手机）。任务包由项目所有者或经其签发证书的管理员分发，并验签。被操作的 App 或网站自己发出的数据（登录、上传图片等）是它们的事，不归这条管。
- 只操作任务包 `requires` 里的 App；前台换成别的 App 就停下（`Outcome.WRONG_APP`）。
- 界面树、截图、OCR 文字只在内存里，一轮结束就丢；轨迹只有结构和耗时。
- 动作集不含发送；新增动作（包括文字输入）要先过安全评审。
- 任务包只是数据：只能引用 `Builtins.registry` 里按名字注册的找法、预期和动作，未知名字由 `validate` 拒收；不做运行时代码插件和脚本引擎：变量单次赋值，表达式只用非图灵完备的固定子集，界面上读到的值不能进网络请求。

## 下一步

M1（QQ 任务）按用户决定例外放行：最后一轮 24 次里 21 次结果正确（87.5%，标准 ≥ 95%），遗留项见架构文档第 9 节“M1 状态”。`Perception` 接口和 `Frame.id` 推到 M3（微信 OCR）。

M2（夸克任务）：任务包已写好、各起点单次通过，弹窗提示和多层返回已真机验证，20 次连续验收暂时搁置；用户要先把网站添加到夸克首页并命名为“书签”（见架构文档第 9 节“M2 状态”）。

M3（微信任务）：功能都已实现，前台、后台都跑通过，20 次连续验收没做（架构文档第 9 节“M3 状态”）。

之后：App 被拉回主屏时的暂停和续跑，以及下一阶段的其他需求，优先级见 [docs/research/next-phase-design-review.md](docs/research/next-phase-design-review.md) 第 9 节。
