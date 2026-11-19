# phone-agent

侧载安装的安卓 App，通过无障碍服务执行任务包。设计见 `docs/ARCHITECTURE.md`。难以回退的决定记到决定记录里。

## 协作

- 单人项目：不开分支和 PR。**提交等用户说**。

## 构建与测试

- Gradle 8.11 需要 JDK 17–21。Android Studio 自带的 JBR 是 25，跑不了；用 `~/.jdks/jbr-21.0.11`。
- `export JAVA_HOME="$(cygpath -w ~/.jdks/jbr-21.0.11)" && ./gradlew :core:test :app:assembleDebug`
- `core` 是纯 Kotlin：逻辑放在这里，在电脑上用脚本化的帧测试（`CoreTest.kt`）。
- 坑：本机 Git Bash 的 heredoc 会把 `\\` 折叠成 `\`。写含反斜杠的文件用编辑工具，别用 shell 重定向。
- 坑：`local.properties` 用正斜杠，`sdk.dir=C:/Users/<user>/AppData/Local/Android/Sdk`。

## 不变量（没有项目所有者的明确决定，不要打破）

- 不靠云端：感知和决策都在本机完成，不用大模型 API、云 OCR、云手机或云电脑，界面树、截图、OCR 文字不交给任何外部服务。Phone Agent 自己联网只做这些：App 更新、拉取任务包、调用任务包声明过的自有网站接口（界面上读到的内容不能填进这些请求），以及后台模式的启动器连这台手机自己的 adbd（127.0.0.1，不出手机）。任务包由项目所有者或经其签发证书的管理员分发，并验签。被操作的 App 或网站自己发出的数据（登录、上传图片等）是它们的事，不归这条管。Phone Agent 自己的联网代码集中在一处。`INTERNET` 权限目前只用于连本机 adbd（`shell/AdbLauncher`）。
- 只操作任务包 `requires` 里列出的 App；前台是别的 App 就停止运行（`Outcome.WRONG_APP`）。
- 界面树、截图、OCR 文字只在内存里保留一轮。轨迹只记结构和耗时。
- 任务包只是数据。找法、预期、动作都在 `Builtins.registry` 里按名字注册；未知名字由 `validate` 拒收。不做运行时代码插件，也不做脚本引擎：变量单次赋值，表达式只用非图灵完备的固定子集，界面上读到的值不能进网络请求，都在加载时校验。

## 约定

- 在信任边界（任务包加载、平台数据）做校验；边界以内依赖类型。
- 模块和文件按概念命名。代码里不写 spec 或工单编号。
- 新增找法、预期或动作：在 `Builtins` 里注册，并在同一次改动里加测试。
- 等待由事件驱动（`awaitSettle`），不写固定 sleep。

## 证据与断言

写「只能 / 必须 / 得靠 / 设计如此 / 没这条路」之前先过三查，缺一条就降到 `[推测]`：

1. schema：目标组件有哪些可调项（配置类型、`Config`、`z.object`）
2. 文档：README / `.d.ts` 里列出的开关与默认值
3. 第二条路：同功能能否换实例、换入口、换 provider

断言带标签：

- `[已证实]` 附出处，`文件:行号` 或命令输出
- `[观测一次]` 只有单次运行证据
- `[推测]` 没有证据，只是合理猜想
- 没有出处时禁用：只能、必须、得靠、设计如此、没这条路、永远、绝不会

单次失败只证明「这次失败」。说结论前分三层：观测（这次返回什么）→ 配置（默认值、开关）→ 机制（能力是否存在）。

下结论前写一句反证：「如果这个结论错了，最可能错在哪，用哪条命令能查出来」，然后去查。

深挖有界：三查 + 一条能推翻结论的最小实验，最多 5 次工具调用；到上限就输出「已知 / 未知 / 下一步怎么验」，不许无限延长。

## 反例库

**#1 把单次观测当成机制**（2026-11-19）

- 证据：一次 `Edit` 被拒，报错 *"the provider does not request human approval"*
- 错：「这个通道只能只读侦察，写文件得主控来。」—— 一句话把配置层问题判成架构层
- 对：「当前 provider 实例默认 `dontAsk`，写入被拒 `[已证实]`；包内有 `permissionMode` 开关，改值可用，未实测 `[观测一次]`」
- 代价：主控据此换了分工，白跑一轮，用户追问才复查

## 委派与文档

- 三档：T1 微改零文档；T2 单模块用任务卡（`specs/CARD.md`，≤15 行）；T3 跨模块或改不变量写 `specs/T-xxx.md`（≤40 行，必须含验收命令）。
- 分工：**Claude Code 落地**（改码、跑测试），**Codex 只审**（读 diff 给阻断级/建议级意见，不写文件），主控写卡与判定。所以只有 Claude Code 需要读隔离。
- 委派子智能体只给三样：目标文件、任务卡、该模块 `CLAUDE.md`。不给会话历史，不给 `docs/research/` 和 `docs/DECISIONS.md`。
- 任务卡只写「什么叫通过」，不写动机、优先级、取舍——判断留在主控（规程见 `docs/CONDUCTOR.md`，那份只主控读）。
- 读隔离由 `.claude/settings.json` 的 `permissions.deny` 强制（2026-11-19 实测：Read 报 “File is in a directory that is denied by your permission settings”，PowerShell 读写同路径也被拦，列目录同样被拦；`.claudeignore` 不生效，已删）。
- 文档地图见 `docs/INDEX.md`；现在到哪见 `docs/STATE.md`；为什么这么定见 `docs/DECISIONS.md`。

## 隐私

- 不要提交 `tools/probe/out/` 或任何未脱敏的界面树、截图：里面有真实聊天内容。加入测试夹具前先替换昵称和消息文字。
- 只用小号测试。
