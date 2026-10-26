# core

纯 Kotlin，不依赖 Android。只跑 `./gradlew :core:test`（秒级），不用构建 `app`。

## 子包

- `pack/`：任务包格式和加载时校验（`Pack.kt`、`Validator.kt`）。
- `run/`：一轮感知（`Frame`）、设备接口（`Device`）、解释器（`Runner`）。
- `route/`：一句话 → 任务包。
- `ext/`：扩展点注册表（`Registry`）和 v1 内置实现（`Builtins`）。
- `ocr/`：PP-OCR 引擎（`PpOcr`，实现 `run.TextRecognizer`），onnxruntime 推理加检测框提取和 CTC 解码。

依赖方向：`route` 只依赖 `pack`；`ocr` 只依赖 `run`；`run.Frame`、`run.Device` 不依赖其他子包。`Runner`、`Validator` 和 `ext` 之间互相引用是有意的（注册表要知道 `Step`、`Device`，解释器和校验要用注册表），不要再加新的环。

## 加东西

- 新找法 / 预期：实现 `Finder` / `Expectation`，在 `Builtins.registry` 里按名字注册，同一次改动里加测试。参数格式错要能在 `validate` 的空帧试跑里报出来。
- 新动作：同上，但要先过安全评审；不加发送。
- 任务包新字段：`Packs` 是严格解析，加字段要改 `Pack` / `Step`，并更新 `packs/` 和架构文档第 4 节。

## 测试

- OCR：编译只依赖 onnxruntime 桌面版（`compileOnly`），`app` 打包安卓版，两者版本必须一致。模型由 `./gradlew :app:fetchOcrModels` 下载（检测在 `app/src/main/assets/ocr/`，识别在 `app/src/debug/assets/ocr/`），没下载时模型测试自动跳过。改了 OCR 先跑对照测试：`tools/probe/ocr_reference.py` 生成参考（含真实文字，放仓库外），再 `OCR_REFERENCE=<json> ./gradlew :core:test`。调 `OcrConfig` 的准确率旋钮用标注网格：`OCR_LABELS=<标注文件;...>`（可加 `OCR_GRID`）跑 `--tests '*grid*' --rerun`，Gradle 不跟踪环境变量，所以要 `--rerun`。数据和命令见 `docs/ocr-experiments.md`，新结果也记在那里。

- `CoreTest.kt` 里的 `FakeDevice` 按“动作 → 下一帧”的脚本返回画面，`awaitSettle` 每次推进 300 ms 假时钟。
- 会进入等待的步骤要有多帧用例；每个失败分支（`Outcome`）都要有用例。
- 真实界面树做夹具前先脱敏（昵称、消息文字），放 `fixtures/<app>/<screen>/<state>`。
