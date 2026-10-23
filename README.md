# Phone Agent

免费、侧载安装的安卓自动化 App：用户选一个任务（以后可以说一句话），App 通过无障碍服务按**任务包**操作指定的几个 App。任务包只是数据，能做什么由 App 代码里注册好的找法、预期和动作决定。

当前状态：**骨架（v0.1）**。`core` 的任务包解析、校验、执行器和一句话路由已实现并有测试；`app` 能列出内置任务包、在真机上运行并显示每步耗时。QQ 任务包是草稿，列表规则还没在真实 QQ 界面上校准（M1 的工作）。设计见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 结构

```
core/    纯 Kotlin，可在电脑上测试
  Frame.kt      一轮感知的结果：界面树 + 按需、按区域缓存的 OCR；各层往同一帧里追加
  Device.kt     执行器眼中的手机（截取、启动、点/长按、返回、等稳定）；displayId 留给以后的虚拟屏
  Pack.kt       任务包格式（严格解析，不认识的字段直接报错）
  Registry.kt   扩展点：找法、预期、动作，都按名字注册
  Builtins.kt   v1 内置：text / desc / id / listItem；anyText / gone / toast；launch / tap / long / back
  Validator.kt  加载时校验：未知名字、参数格式、launch 了 requires 以外的 App
  Runner.kt     逐步执行：找 → 动 → 等稳定 → 查预期；输出不含界面内容的轨迹
  Router.kt     一句话 → 任务包（按别名匹配，匹配不唯一就不猜）
app/     Android：AgentService（无障碍服务，实现 Device）、MainActivity（任务列表）
packs/   官方任务包 JSON，构建时打进 App 的 assets
tools/probe/  电脑端抓界面树、截图、对比（uiautomator2），用法见其 README
docs/    架构文档、BetterGI / OneDragon 调研（以后压低延迟时要用）
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
3. 电池设置里允许“后台高耗电”（vivo/iQOO）。
4. 打开 App，点任务按钮。完成或停下时显示原因，以及每步“找 / 动 / 等 / 查”的耗时。

只用小号测试。

## 扩展

- **加一个任务**：在 `packs/` 放 JSON，`core` 的测试会自动校验它（`shipped packs validate`）。字段见 `Pack.kt` 和 `packs/qq-latest-to-douyin.json`。
- **加一种找法或预期**：实现 `Finder` / `Expectation`，在 `Builtins.registry` 里按名字注册，并加测试。
- **加一种动作**（滚动、左右滑等）：同上，但要先过安全评审——动作集里不放输入和发送。
- **OCR（M3）**：实现 `TextRecognizer` 并交给 `Frame`；探针里的 `OcrBench.kt`（`D:\gui-agent\spikes\a11y_probe`）是起点。

## 不变量

- 不联网（没有 INTERNET 权限）。以后的“检查更新”只在用户点击时连固定地址，任务包要验签。
- 只操作任务包 `requires` 里的 App；前台换成别的 App 就停下（`Outcome.WRONG_APP`）。
- 界面树、截图、OCR 文字只在内存里，一轮结束就丢；轨迹只有结构和耗时。
- 动作集不含输入与发送。

## 下一步（M1）

用 `tools/probe` 在 QQ 上抓会话列表和聊天界面，校准 `listItem` 的规则（列表容器、哪些行算条目、怎么跳过时间分隔条），把脱敏后的界面树放进 `core` 测试夹具，然后在真机上连续跑 20 次。
