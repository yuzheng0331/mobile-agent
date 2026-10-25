# Phone Agent

免费、侧载安装的安卓自动化 App：用户选一个任务（以后可以说一句话），App 通过无障碍服务按**任务包**操作指定的几个 App。任务包只是数据，能做什么由 App 代码里注册好的找法、预期和动作决定。

当前状态：**骨架（v0.1）**。`core` 的任务包解析、校验、执行器和一句话路由已实现并有测试；`app` 能列出内置任务包、在真机上运行并显示每步耗时。QQ 任务包是草稿，列表规则还没在真实 QQ 界面上校准（M1 的工作）。设计见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

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
  ext/Builtins.kt    v1 内置：text / desc / id / listItem / lastMessage；anyText / gone / toast；launch / tap / long / back
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
4. 第一次跑任务、要打开另一个 App（如抖音）时，vivo 会弹“Phone Agent 想要打开 …”。这个弹窗 App 点不了（vivo 不让无障碍服务操作它），会在屏幕顶部提示你，并等最多 30 秒：请自己点“始终打开”，任务接着跑，之后同一个 App 不再弹。想撤销：设置 → 应用与权限 → 权限管理里找 Phone Agent 的“关联启动 / 应用跳转”（各版本叫法不同）。
5. 打开 App，点任务按钮。完成或停下时显示原因，以及每步“找 / 动 / 等 / 查”的耗时。

只用小号测试。

## 扩展

- **加一个任务**：先按架构文档第 8 节在真机上 probe 所有起点和分支，再写任务包；在 `packs/` 放 JSON，`core` 的测试会自动校验它（`shipped packs validate`）。字段见 `Pack.kt` 和 `packs/qq-latest-to-douyin.json`。
- **加一种找法或预期**：实现 `Finder` / `Expectation`，在 `Builtins.registry` 里按名字注册，并加测试。
- **加一种动作**（滚动、左右滑等）：同上，但要先过安全评审——动作集里不放输入和发送。
- **OCR（M3）**：实现 `TextRecognizer` 并交给 `Frame`；探针里的 `OcrBench.kt`（`D:\gui-agent\spikes\a11y_probe`）是起点。

## 不变量

- 不联网（没有 INTERNET 权限）。以后的“检查更新”只在用户点击时连固定地址，任务包要验签。
- 只操作任务包 `requires` 里的 App；前台换成别的 App 就停下（`Outcome.WRONG_APP`）。
- 界面树、截图、OCR 文字只在内存里，一轮结束就丢；轨迹只有结构和耗时。
- 动作集不含输入与发送。
- 任务包只是数据：只能引用 `Builtins.registry` 里按名字注册的找法、预期和动作，未知名字由 `validate` 拒收；不做运行时代码插件和脚本引擎。

## 下一步

M1（QQ 任务）按用户决定例外放行：最后一轮 24 次里 21 次结果正确（87.5%，标准 ≥ 95%），遗留项见架构文档第 9 节“M1 状态”。`Perception` 接口和 `Frame.id` 推到 M3（微信 OCR）。

M2（夸克任务）：任务包已写好、各起点单次通过，弹窗提示和多层返回已真机验证，20 次连续验收暂时搁置；用户要先把网站添加到夸克首页并命名为“书签”（见架构文档第 9 节“M2 状态”）。
