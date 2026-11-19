# Phone Agent

免费、侧载安装的安卓自动化 App：用户点一下任务（不做一句话路由，见架构文档决定记录），App 通过无障碍服务按**任务包**操作指定的几个 App。任务包只是数据，能做什么由 App 代码里注册好的找法、预期和动作决定。

当前状态（2026-10-30）：三个任务包（QQ → 抖音、夸克书签、微信 → 小红书）都能在真机上跑；微信的界面树是空的，靠截图 + OCR（识别在 GPU 上）。可选后台运行：任务在虚拟屏上跑，你照常用手机。各里程碑的状态见架构文档第 9 节，设计见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 结构

```
core/    纯 Kotlin，可在电脑上测试：pack（格式与校验）、run（解释器）、ext（扩展点）、ocr、route、adb
app/     Android：AgentService、MainActivity、LiteRtModels、AgentIme、Background 与 shell/
packs/   官方任务包 JSON，构建时打进 assets
tools/   probe（抓帧 / OCR 标注 / 虚拟屏实验）、site-replica（网站接口本机副本）
docs/    设计、状态、决定记录、实验记录、调研——每份是干嘛的见 [docs/INDEX.md](docs/INDEX.md)
```

每个文件、每个包的职责见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) 第 3 节。

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
3. 电池设置里允许“后台高耗电”（vivo/iQOO）。最好不要在最近任务里划掉 Phone Agent：vivo 会连无障碍服务一起关掉，后台任务就不跑了；打开一次 Phone Agent 就自动恢复。
4. 第一次跑任务、要打开另一个 App（如抖音）时，vivo 会弹“Phone Agent 想要打开 …”。这个弹窗 App 点不了（vivo 不让无障碍服务操作它），会在屏幕顶部提示你，并等最多 30 秒：请自己点“始终打开”，任务接着跑，之后同一个 App 不再弹。想撤销：设置 → 应用与权限 → 权限管理里找 Phone Agent 的“关联启动 / 应用跳转”（各版本叫法不同）。
5. 打开 App，点任务按钮。完成或停下时显示原因，以及每步“找 / 动 / 等 / 查”的耗时。
6. 可选，加速：设置 → 输入法 → 打开“Phone Agent 加速”。它不是键盘、不读也不打字，只在需要 OCR 的任务运行时被临时选中，OCR 快约 30%。
7. 可选，后台运行：勾选“后台运行”，任务在虚拟屏上跑，你照常用手机。不用电脑，只做一次：
   - 设置 → 关于手机 → 连点“版本号”7 次，打开开发者选项；
   - 在 Phone Agent 里点“配对无线调试”，允许通知；到开发者选项打开“无线调试”，点“使用配对码配对设备”；
   - 下拉通知栏，把 6 位配对码填进 Phone Agent 的通知。通知显示“已配对，后台运行已可用”就好了。

   之后平时什么都不用做：重启后授权还在，开发者选项、无线调试都可以关着，Phone Agent 要用时自己打开无线调试、用完关回。

   授权会在 Phone Agent 改了权限的更新、或者你开关开发者选项时丢掉，这时无障碍服务也会断开：打开一次 Phone Agent 就恢复，连着 Wi-Fi（不需要能上网）时顺带拿回授权。每个 Wi-Fi 第一次会弹“允许在此网络上使用无线调试”，请同意，勾上“始终允许”以后就不再问。没有授权时任务改在前台跑，并说明原因。

只用小号测试。

## 扩展

- **加一个任务**：先按架构文档第 8 节在真机上 probe 所有起点和分支，再写任务包；在 `packs/` 放 JSON，`core` 的测试会自动校验它（`shipped packs validate`）。字段见 `Pack.kt` 和 `packs/qq-latest-to-douyin.json`。
- **加一种找法或预期**：实现 `Finder` / `Expectation`，在 `Builtins.registry` 里按名字注册，并加测试。
- **加一种动作**（滚动、滑动、输入等）：同上，在 `Builtins.registry` 里注册并加测试。
- **OCR**：引擎在 `core/ocr/PpOcr.kt`，模型的下载和生成见 `app/CLAUDE.md`，实验数据和重跑方法见 [docs/ocr-experiments.md](docs/ocr-experiments.md)。

## 不变量

见 [CLAUDE.md](CLAUDE.md) §不变量。那份是唯一权威，改动只在那里做。

## 下一步

见 [docs/STATE.md](docs/STATE.md)。

