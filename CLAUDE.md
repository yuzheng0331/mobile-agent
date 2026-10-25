# phone-agent

侧载安装的安卓 App，通过无障碍服务执行任务包。设计见 `docs/ARCHITECTURE.md`（末尾有决定记录）。单人项目：不写 spec；难以回退的决定记到决定记录里。

## 构建与测试

- Gradle 8.11 需要 JDK 17–21。Android Studio 自带的 JBR 是 25，跑不了；用 `~/.jdks/jbr-21.0.11`。
- `export JAVA_HOME="$(cygpath -w ~/.jdks/jbr-21.0.11)" && ./gradlew :core:test :app:assembleDebug`
- `core` 是纯 Kotlin：逻辑放在这里，在电脑上用脚本化的帧测试（`CoreTest.kt`）。

## 不变量（没有项目所有者的明确决定，不要打破）

- 不联网：manifest 里没有 `INTERNET` 权限。以后的“检查更新”只在用户点击时连一个固定地址，并校验任务包签名。
- 只操作任务包 `requires` 里列出的 App；前台是别的 App 就停止运行（`Outcome.WRONG_APP`）。
- 界面树、截图、OCR 文字只在内存里保留一轮。轨迹只记结构和耗时。
- 动作集不含文字输入和发送。新增动作要先过安全评审。
- 任务包只是数据。找法、预期、动作都在 `Builtins.registry` 里按名字注册；未知名字由 `validate` 拒收。不做运行时代码插件，也不做脚本引擎。

## 约定

- 在信任边界（任务包加载、平台数据）做校验；边界以内依赖类型。
- 模块和文件按概念命名。代码里不写 spec 或工单编号。
- 新增找法、预期或动作：在 `Builtins` 里注册，并在同一次改动里加测试。
- 等待由事件驱动（`awaitSettle`），不写固定 sleep。

## 隐私

- 不要提交 `tools/probe/out/` 或任何未脱敏的界面树、截图：里面有真实聊天内容。加入测试夹具前先替换昵称和消息文字。
- 只用小号测试。
