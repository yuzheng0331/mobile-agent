# OneDragon 调研：它怎么做到快、怎么做到可插拔，以及对手机端 v1 的启示

状态：调研报告（2026-10-24）。只读源码得出，未运行 OneDragon。

- **源码**：`C:\Users\R_Crusoe\AppData\Local\Temp\zz`，commit `2943860`（#2880）。下文路径都相对这个目录。
- **对照**：`D:\ZZZ-OneDragon`（用户实际安装的版本）在 commit `012a1ee`（#2534），比克隆旧。差异只在一处看到：安装版的 `src/onnxocr/inference_engine.py` 里**还没有**关闭线程池自旋的两行配置，检测缩放 960 和预热两处与克隆一致。
- **证据**：格式为 `文件:行号`。“未确认”表示源码里没找到依据，不是推测。

---

## 0. 一句话结论

OneDragon 的“快”并不来自某个更快的感知算法，主要来自三点：

1. **固定分辨率 + 预先标好的小区域**。所有识别都先裁剪到 YAML 里写好的矩形，再做模板匹配或 OCR。
2. **每轮只做一件便宜的事**。框架对同一帧缓存 OCR 结果；画面识别从上一个已知画面出发做 BFS，并按应用缩小候选范围。
3. **真正高频的战斗部分另走一套**：20 ms 截图间隔、2–3 ms 的模板门控、16 线程并行检测、按状态分别限频。

日常 UI 导航那部分其实偏慢：大量节点在动作后**固定 sleep 1 s**，然后重新截图轮询。

它的 OCR 检测长边缩到 960 在游戏里能用，是因为 OCR 输入几乎从来不是整屏，而是裁剪后的小区域。337 个文字区域的长边中位数是 212 px，只有 7 个超过 960，见 §1.4。我们在冻结帧上召回下降，是因为送进去的是整屏手机截图。

---

## 问题 1：低延迟是怎么来的（逐层）

### 1.1 截图

| 要点 | 证据 | 说明 |
|---|---|---|
| 三种截图方式，按优先级回退 | `src/one_dragon/base/controller/pc_screenshot/pc_screenshot_controller.py:31-35`、`:134-149` | PrintWindow → BitBlt → PIL；默认 `auto`（`src/one_dragon/envs/env_config.py:403-407`） |
| 失败自动换下一种，并记住成功的方式 | `pc_screenshot_controller.py:63-79` | 记住后，下次从成功的那种开始试 |
| GDI 资源复用 | `gdi_screencapper_base.py:42-66`（兼容 DC 只建一次）、`:136-149`（尺寸不变就不重建位图） | 每帧只做 `GetDC` + 拷贝，省掉创建 DC 和位图的开销 |
| 位图内存直接转 numpy，无额外拷贝 | `gdi_screencapper_base.py:293-299` | `np.frombuffer` 后再做一次 `cvtColor(BGRA2RGB)` |
| 失败时重建一次再试 | `gdi_screencapper_base.py:155-180` | |
| 统一缩放到标准分辨率 | `pc_screenshot_controller.py:76-77`；1920×1080 写在 `src/zzz_od/controller/zzz_pc_controller.py:19-20`、`config/project.yml` 的 `screen_standard_width/height` | **这一条是后面所有固定坐标区域能成立的前提** |
| 截图带时间戳，可选保留历史 | `src/one_dragon/base/controller/controller_base.py:65-85` | 时间戳被战斗状态记录用来限频（§1.6） |
| 截图帧率 | 战斗循环默认 `screenshot_interval = 0.02` s（`src/zzz_od/application/battle_assistant/battle_assistant_config.py:19-20`），用法见 `src/zzz_od/application/battle_assistant/auto_battle/auto_battle_app.py:81-88`；普通节点每轮截一次（`src/one_dragon/base/operation/operation.py:531-532`） | 单次截图耗时：**未确认**，源码和文档里都没有数字 |

**后台模式和速度无关。** `docs/develop/one_dragon/background_mode_design.md` 讲的是窗口不在前台时怎样**输入**：

- UI 点击用 `WM_ACTIVATE + PostMessage`（`:7-31`）；
- 锁鼠标场景和战斗用 ViGEm 虚拟手柄（`:38-111`）；
- 键盘没有后台方案（`:116-127`）。

截图那一行写的是“不受影响（已有后台截图）”（`:254`），因为 PrintWindow 本来就能截被遮挡的窗口。这份文档对我们的价值，只在于它演示了“同一个区域（`ScreenArea.gamepad_key`）在不同输入后端下分别怎么执行”（`:43-87`）。

### 1.2 画面识别：画面、区域与几种识别手段

**数据模型。** 一个画面（screen）是一组区域（area）。每个区域的字段见 `src/one_dragon/base/screen/screen_area.py:20-47`：

- 固定矩形 `pc_rect`；
- 类型是 `text`（OCR）、`template`（模板匹配）或 `none`（只点击），见 `:10-15`、`:50-69`；
- `id_mark`：这个区域是否用来认出画面；
- `goto_list`：点了之后可能跳去哪些画面；
- `color_range`：OCR 前的颜色过滤；
- `lcs_percent`：文字模糊匹配阈值，默认 0.5。

配置样例见 `assets/game_data/screen_info/battle_menu.yml:1-60`。

**判定画面 = 所有 `id_mark` 区域都命中**（`src/one_dragon/base/screen/screen_utils.py:442-480`）。每个区域在判定时都只看自己的矩形（`screen_utils.py:140-182`）：

- 文字区域：`ocr_service.get_ocr_result_list(rect=area.rect, crop_first=True)` 之后做 LCS 模糊匹配；
- 模板区域：`tm.crop_and_match_template(screen, rect, …)`。

**统计数字。** 我用 PyYAML 读了 `assets/game_data/screen_info/_od_merged.yml`（数字由我统计，源码里没有）：

- 80 个画面、708 个区域；
- 文字区域 337 个，模板区域 97 个，`id_mark` 区域 84 个，带 `goto_list` 的区域 70 个；
- 设了 `app_id` 的局部画面 47 个，带 `color_range` 的区域 12 个。

**模板匹配**

- 用 `cv2.matchTemplate(TM_CCOEFF_NORMED, mask=…)`（`src/one_dragon/utils/cv2_utils.py:188-223`）；
- 先裁剪，再在小图上匹配（`src/one_dragon/base/matcher/template_matcher.py:139-165`）；
- 模板按 `sub_dir:template_id` 懒加载并常驻内存（`src/one_dragon/base/screen/template_loader.py:55-72`）；
- 每个模板目录里有 `raw.png`、`mask.png`、`config.yml`（例如 `assets/template/enter_game/*/`）。
- 自带耗时数字：战斗门控“识别普通攻击按钮是否存在……2~3ms”（`src/zzz_od/auto_battle/auto_battle_context.py:1030-1039`），做法是裁剪后做模板匹配，阈值 0.9。

**颜色**

- 区域可选 `color_range`。OCR 前用 `cv2.inRange` 生成黑白图（`src/one_dragon/base/matcher/ocr/ocr_service.py:80-97`）。
- 另有二值化版本的区域判定（`screen_utils.py:82-137`）。
- 更复杂的 CV 写成 YAML 流水线：`assets/image_analysis_pipelines/*.yml`，执行步骤注册在 `src/one_dragon/base/cv_process/cv_service.py:7-29`，含 HSV/RGB 过滤、轮廓、形状匹配、OCR 等步骤。

**YOLO / 分类器**

- 闪避的闪光识别用 `yolov8n-640` 分类模型（`src/zzz_od/yolo/flash_classifier.py:10-11`）。
- 会话创建：GPU 时依次选 CUDA → DML → CPU；DML 下改为顺序执行并关闭内存模式；同样关闭线程池自旋（`src/one_dragon/yolo/onnx_model_loader.py:129-162`）。
- 分段计时代码存在，但打印那行被注释掉了（`src/one_dragon/yolo/yolov8_onnx_cls.py:92-105`），**没有留下数字**。

### 1.3 OCR 与 ONNX 推理

| 项 | 现状 | 证据 |
|---|---|---|
| 运行时 | onnxruntime；自带 `onnxocr` 包（PaddleOCR 的 ONNX 移植），类注释写“使用onnx的ocr模型 速度更快” | `src/one_dragon/base/matcher/ocr/onnx_ocr_matcher.py:150-153` |
| 模型 | 默认 `ppocrv6`，固定 `small` 档；文件是 det/rec/cls.onnx + 字典，首次运行时从 GitHub/CNB/Gitee 下载 | `onnx_ocr_matcher.py:26-35`、`:62-78`、`:125-127`；`src/onnxocr/onnx_paddleocr.py:15-61` |
| 检测缩放 | `det_limit_side_len=960`、`limit_type='max'`：**只在长边超过 960 时才缩小**，小图不放大 | `onnx_ocr_matcher.py:97`；`onnx_paddleocr.py:54-55`；实现在 `src/onnxocr/operators.py:107-118` |
| 检测阈值 | `det_db_thresh 0.3`、v6 的 `box_thresh 0.45`、`unclip 1.5` | `onnx_paddleocr.py:15-17`、`:56-59` |
| 方向分类 | 默认关（`use_angle_cls=False`） | `onnx_ocr_matcher.py:96` |
| 识别批量 | `rec_batch_num=6` | `src/onnxocr/utils.py:317`；`src/onnxocr/predict_rec.py:298-317` |
| 预热 | 检测模型加载后立即用 960×960 的零张量跑一次，注释写“避免因为算子懒加载导致前几次推理耗时增加 2-10 倍”。**只预热了检测**，识别没有预热 | `src/onnxocr/predict_det.py:57-59` |
| 会话选项 | `ORT_ENABLE_ALL` + `enable_mem_pattern`，注释写“10-30% CPU inference speedup”；关闭 intra/inter 线程池自旋（安装版没有这两行） | `src/onnxocr/inference_engine.py:139-150` |
| 线程数 | argparse 里有 `cpu_threads=10`，但**全仓没有地方用它**（没有设 `intra_op_num_threads`），实际是 ORT 默认值 | `src/onnxocr/utils.py:359`；对 `intra_op_num_threads` 的 grep 无结果 |
| GPU 选择 | 用户开关 `ocr_use_gpu`，默认关；开了以后依次选 CUDA → DML → CANN → CPU | `src/one_dragon/base/config/basic_model_config.py:47-52`；`inference_engine.py:50-68` |
| GPU 串行化 | 所有 DML 会话的创建和推理都排进一个单线程执行器，注释写“避免多 session 并发崩溃” | `src/one_dragon/utils/gpu_executor.py:19-24`、`:43-68`；调用点 `src/onnxocr/predict_base.py:49-50` |
| 只识别不检测 | `run_ocr_single_line(strict_one_line=True)` 跳过检测，直接对裁好的单行区域做识别 | `onnx_ocr_matcher.py:281-295`、`:359-385` |
| 结果缓存 | 以 `id(image)` 加 (color_range, rect, crop_first) 为键，最多 5 条；缓存条目同时持有图片引用，防止 id 被复用 | `ocr_service.py:17-26`、`:99-132`、`:157-209` |
| 先裁剪再识别 | `crop_first=True` 时只对矩形内的子图做检测和识别，再把坐标加回偏移；不裁剪时整图识别，再过滤出和区域重叠超过 70% 的结果 | `ocr_service.py:175-193`、`:211-220` |
| 耗时数字 | 只有 debug 日志“OCR结果 … 耗时”和 trace 里的 `ocr_ms`（`onnx_ocr_matcher.py:351-356`、`:544-563`）。**仓库里没有基准数字，未确认** | |

### 1.4 为什么 960 在游戏里够用，在我们的冻结帧上不够

1. **送进去的几乎都是小图。** 画面判定和点击都走区域，而区域默认先裁剪再识别（`screen_utils.py:164-170`、`:259-265`；`ocr_service.py:175-191`）。
   - 我统计的 337 个文字区域：长边中位数 212 px，P90 是 773 px，最大 1528 px，**超过 960 的只有 7 个**。
   - 业务代码里 36 处 `get_ocr_result_list/map` 调用，31 处带 `rect`（我对 `src/zzz_od` 的脚本统计）。
   - 所以 `limit_type='max'` 的缩放对绝大多数输入不起作用，检测看到的就是原始像素。
2. **分辨率固定。** 截图先统一缩放到 1920×1080（§1.1），矩形坐标和字号都是可预期的。即使整屏识别，也只缩到 0.5 倍；游戏 UI 的字本来就大。
3. **它要找的是已知的词。** 匹配用 LCS 模糊比对（`lcs_percent` 默认 0.5，`screen_area.py:39`），不需要完整召回整屏的文字。
4. **我们的情况相反。** 手机整屏截图大约 1080×2400，长边缩到 960，倍率约 0.4。聊天列表里的小字（时间、预览）这时只剩几个像素高，召回自然掉。

结论：在手机上要么让检测的长边限制 ≥ 截图长边，要么学它**先按区域裁剪再识别**。后者同时省时间。见“启示”第 1 条。

### 1.5 操作节点框架：重试、超时、轮次、等待

- **节点声明**
  - `@operation_node(name, is_start_node, timeout_seconds, node_max_retry_times=3, screenshot_before_round=True, …)`（`src/one_dragon/base/operation/operation_node.py:72-100`）；
  - 边用 `@node_from(from_name, success, status, ignore_status)` 声明（`src/one_dragon/base/operation/operation_edge.py`）；
  - 框架用反射收集节点和边，并校验唯一起点、找不到节点就报错（`operation.py:240-292`）。
  - 典型写法见 `src/zzz_od/application/email_app/email_app.py:33-82`。
- **一轮的结果有四种**：SUCCESS、FAIL、RETRY、WAIT（`src/one_dragon/base/operation/operation_round_result.py:5-9`）。
  - RETRY 消耗重试次数，超过 `node_max_retry_times` 就转为 FAIL（`operation.py:474-483`）；
  - **WAIT 不计次数**，下一轮重跑当前节点（`:485-486`）。这就是它“等画面变化”的原语。
  - 下一个节点按“成功/失败 + status 精确匹配”找边，`ignore_status` 的边作兜底（`:550-588`）。
- **超时**：整个操作有 `timeout_seconds`（`:426-429`），每个节点单独计时（`:523-526`、`:596`）。
- **主循环**：每轮开头检查停止和暂停，按需截图，调用节点，记录本轮耗时到 trace（`operation.py:412-512`、`:514-548`）。异常算一次 RETRY，并保存当时的截图（`:458-470`）。
- **等待是固定 sleep，再加上重新截图的轮询。** 不是画面事件驱动。
  - 每个 `round_*` 都带 `wait` 参数（固定秒数）或 `wait_round_time`（把本轮补齐到固定时长），实现就是 `time.sleep`（`operation.py:853-865`）；
  - 点击类辅助函数在点击前还有一个 `pre_delay` sleep（如 `:968`）；
  - `round_by_goto_screen` 的 `retry_wait` 默认是 1 s（`:1402-1405`）。
- **sleep 的分布**（我对 `src/zzz_od` 的脚本统计）：

  | 写法 | 次数 |
  |---|---|
  | `wait=1` | 393 |
  | `retry_wait=1` | 166 |
  | `success_wait=1` | 149 |
  | `wait=0.5` | 106 |
  | `wait=2` | 28 |
  | 显式 `time.sleep` | 121 |

  `@operation_node` 共 670 个，`@node_from` 共 830 个。
  也就是说，**它的 UI 流程每步大约 0.5–1 s 起步**，和我们 v1 实测的 0.7–2.8 s 同一量级。它在这方面并不比我们更快。
- **“等到某区域出现或消失”的点击**：`round_by_find_and_click_area(until_find_all=[…], until_not_find_all=[…])`（`operation.py:930-966`）。思路是“点了以后不断看，看到预期就算成功”，靠多轮 WAIT/RETRY 实现。
- **切换画面防抖**：`screen_switch_debounce` 在点击后一段时间内，如果还停在原画面就返回 WAIT，避免误判“没点上”而重复点击（`operation.py:1423-1468`）。

### 1.6 战斗：真正低延迟的部分

- 循环只有一个节点：“画面识别” → `check_battle_state` → `round_wait(wait_round_time=screenshot_interval)`，默认 20 ms（`auto_battle_app.py:81-88`；`battle_assistant_config.py:19-20`）。
- **每帧先做 2–3 ms 的门控**：普攻按钮的模板匹配，判定是否在战斗中（`auto_battle_context.py:548-557`、`:1030-1039`）。
- **门控通过后才并行提交多项检测**（`auto_battle_context.py:559-610`）：闪避（音频 + 闪光分类）、角色、目标、支援、距离等，用 16 线程池（`:36`）；用了 GPU 的检测进单线程的 GPU 执行器。主循环**不等结果**，只在 `sync=True` 时等。
- **每项检测单独限频**，间隔可以写成随机区间：
  - 例如 `check_chain_interval: [0.9, 1.1]`、`check_agent_interval: [0.4, 0.6]`、`check_dodge_interval: 0.02`（`config/auto_battle/专属配队-艾莲.sample.yml:14-20`）；
  - 判断方式是 `screenshot_time - last < random_in_range(interval)` 时直接跳过（`auto_battle_context.py:622`、`:769`、`:800`；`auto_battle_dodge_context.py:238`）；
  - 每项检测还带一个非阻塞锁，上一次没跑完就跳过（`auto_battle_dodge_context.py:234-235`）。
- **决策层**：检测结果写进带时间戳的状态记录（`StateRecord`），再由 YAML 描述的条件树和处理器决定动作（§2.4）。感知和决策解耦，用异步线程衔接。

### 1.7 画面路由与 scope

- **路由表**：按 `goto_list` 建边，`reload()` 时用 Floyd 算出任意两画面之间的最短点击路径（`src/one_dragon/base/screen/screen_loader.py:341-409`）。
  - `round_by_goto_screen` 的做法是：先识别当前画面，再查路由，点第一跳区域，返回 WAIT 让下一轮重新识别（`operation.py:1446-1485`）。
- **识别当前画面时先查“最可能”的画面**：从当前画面和上一个画面出发，沿 `goto_list` 做 BFS 逐个判定；都不中，才扫其余画面（`screen_utils.py:342-440`）。
- **Scope**：没有 `app_id` 的是全局画面，有 `app_id` 的只在对应应用运行时参与匹配。
  - 应用启动时 `enter_scope(app_id)`，结束时 `exit_scope()`（`src/one_dragon/base/operation/application_base.py:86-90`；`screen_loader.py:436-480`）；
  - BFS 里非活跃画面不做匹配，但仍展开邻居，保持图连通（`screen_utils.py:408-418`）。
  - 自己给的估算：迷失之地运行时最坏匹配次数“~70 次 → ~30 次”（`docs/develop/screen_scope_design.md:129-135`）。这是估算，**不是实测数字**。

### 1.8 并发与调度小结

| 线程 / 执行器 | 用途 | 证据 |
|---|---|---|
| 主流程线程 | 节点主循环：截图 → 识别 → 动作 → sleep，单线程 | `operation.py:412-512` |
| `od_battle_state_check`（16 线程） | 战斗检测 | `auto_battle_context.py:36` |
| 闪避检测（16 线程） | | `auto_battle_dodge_context.py:28` |
| 角色检测（16 线程） | | `auto_battle_agent_context.py:21` |
| 周期动作 | 锁定、转向 | `auto_battle_operator.py:140-160` |
| `od_gpu`（1 线程） | 所有 DML 推理串行 | `gpu_executor.py:19-24` |
| 截图 | 带 `RLock` | `gdi_screencapper_base.py:40` |

`OcrService` 的类注释写着“提供并发识别 (未实现)”（`ocr_service.py:29-38`）：OCR 本身没有并行。

---

## 问题 2：可插拔是怎么组织的

### 2.1 分层

| 层 | 是什么 | 怎么扩展 / 注册 | 证据 |
|---|---|---|---|
| 应用（Application） | 一个可单独运行的功能，例如邮件、体力 | 目录里放 `*_const.py` 和 `*_factory.py`，按**文件名后缀**被自动扫描 | `src/one_dragon/base/operation/application/application_factory_manager.py:1-8`、`:142-149` |
| 操作（Operation） | 节点图，可嵌套：子操作作为节点 | `@operation_node` 和 `@node_from` 装饰器 | §1.5 |
| 画面配置（screen_info） | 画面 → 区域 → 矩形、文字或模板、跳转 | 每画面一个 YAML，再合并成 `_od_merged.yml`；插件自带的 `screen_info/` 会被自动加载 | `screen_loader.py:75-163`、`:165-214` |
| 模板 | `assets/template/<sub_dir>/<id>/{raw,mask}.png + config.yml` | 只从 `assets/template` 加载，**插件不能附带模板**（没找到插件模板目录的加载逻辑） | `src/one_dragon/base/screen/template_info.py:551-565` |
| 战斗策略 | 条件表达式 → 原子操作序列 | YAML（`config/auto_battle/*.yml`、`config/auto_battle_state_handler/*.yml`）；原子操作按名字由工厂里的 if/elif 映射 | `src/zzz_od/auto_battle/atomic_op/atomic_op_factory.py:39-81` |
| CV 流水线 | 步骤列表 | YAML（`assets/image_analysis_pipelines/`），步骤类在 `cv_service` 里注册 | `src/one_dragon/base/cv_process/cv_service.py:7-29` |
| 用户配置 | 每个账号实例、每个应用组、每个应用一个 YAML | `config/{idx:02d}/{group_id}/{app_id}.yml`；另有 `.sample.yml` 默认值机制 | `src/one_dragon/base/operation/application/application_config.py:11-23`；`src/one_dragon/base/config/yaml_config.py:14-46` |
| 设置界面 | 运行列表卡片上的齿轮 | `AppSettingProvider` 自动扫描 | `docs/develop/guides/application_setting_guide.md:8-16` |

### 2.2 应用插件的注册细节

**来源**

- 两种：`BUILTIN`（`src/zzz_od/application`）和 `THIRD_PARTY`（项目根下的 `plugins/`）（`application_factory_manager.py:5-7`）。
- 子类可以改写 `application_plugin_dirs` 增加目录（`docs/develop/guides/application_plugin_guide.md:159-177`）。

**约定**

- 同一目录里 `_factory.py` 和 `_const.py` 各最多一个，冲突时整个目录被跳过（`application_factory_manager.py:151-171`）。
- 第三方插件必须放在子目录里（`:231-235`）。
- 一个模块只取一个 `ApplicationFactory` 子类（`:326-341`）。
- `_const.py` 必须定义 `APP_ID / APP_NAME / DEFAULT_GROUP / NEED_NOTIFY`，可选 `PRIORITY` 和 `PLUGIN_AUTHOR/HOMEPAGE/VERSION/DESCRIPTION`（`application_factory.py:21-35`、`:51-60`）。
- `APP_ID` 重复时拒绝，先注册的保留（`application_factory_manager.py:400-406`）。

**热重载**

- 第三方插件卸载整个包、内置应用卸载所在目录的模块，然后重新导入（`application_factory_manager.py:241-251`、`:260-300`）；
- 入口是 `ctx.refresh_application_registration()`（`application_plugin_guide.md:138-146`）；
- 刷新时会重新加载插件画面（`src/one_dragon/base/operation/one_dragon_context.py:210-247`）。

**分发**：GUI 里“导入插件”选一个 zip，解压到 `plugins/`（`application_plugin_guide.md:118-134`）。`plugins/` 被 gitignore，需要用户自己备份（`plugins/README.md:131`）。

**文档漂移**：`plugins/README.md:91-98` 示例里的工厂用 `super().__init__(app_id=…, app_name=…)` 关键字参数，而当前基类签名是 `__init__(self, app_const: ModuleType)`（`application_factory.py:37`）。照 README 写会报错；`application_plugin_guide.md:100-103` 的写法才是对的。社区文档落后于代码，是这类插件体系的常见成本。

### 2.3 插件画面

- 插件目录下的 `screen_info/*.yml` 会在 `init()` 和刷新时加载。
- 没写 `app_id` 的画面自动套用插件的 `APP_ID`，从而成为局部画面。
- 和已有画面重名或重 id 时跳过并告警（`screen_loader.py:165-214`；`docs/develop/screen_scope_design.md:176-231`）。
- 迁移成本：给画面加 `app_id` 只是 YAML 里加一行，不用改代码，没加的行为完全不变（`screen_scope_design.md:142-170`）。

### 2.4 YAML 战斗策略（数据驱动扩展的例子）

- 配队文件里写元信息（作者、版本、适用队伍）、各项检测的间隔，以及用 YAML 锚点复用的动作片段（`config/auto_battle/专属配队-艾莲.sample.yml:1-48`）。
- 处理器文件按状态表达式（如 `"[后台-2-击破]"`）列出要执行的操作，支持 `operation_template` 引用（`config/auto_battle_state_handler/双反模板-击破.sample.yml:1-13`）。
- 表达式被解析成条件树（`src/one_dragon/base/conditional_operation/state_cal_tree.py:11-64`、`:134`）。
- `op_name` 字符串由原子操作工厂映射到实现（`atomic_op_factory.py:56-81`）。

**社区主要就是写这类 YAML，不写 Python。** 文件名带 `.sample` 的是仓库自带的，用户自己的副本不会被更新覆盖。

### 2.5 社区贡献与游戏更新后的维护

**流程**

- 有画面变化时，先“画面建档”：截图，用 `analyze_screen` 客观识别，写画面文档 `docs/game/screens/<screen>.md`，补模板或 CV 进 screen_info，把代表截图归档到测试仓（`docs/develop/development_workflow.md:15-24`）。
- 这一步有对应的 AI 技能，例如 `skills/zzz-od-dev-screen-onboarding`、`skills/zzz-od-dev-plugin`。
- 提 PR：测试仓和主仓同分支名、先提测试仓 PR，CI 按分支名拉测试仓（`development_workflow.md:38-47`）。贡献者记录在 `contributors.yaml`。

**工具**：GUI 开发者工具里有画面管理（截图或粘贴图片，在图上编辑区域，保存 YAML）、模板助手、图像分析流水线编辑（`src/one_dragon_qt/view/devtools/devtools_screen_manage_interface.py:128-782`，以及同目录的另外两个界面）。

**截图存档**

- 测试仓按 `screens/<screen>/<state>.webp` 存放，webp q90；1080p 原生，不缩放，这样才能和 `pc_rect` 对上（`docs/develop/zzz/screenshot_archive.md`）。
- 自己的实测：webp 比 PNG 省约 94%，“整屏识别无损（conf 损耗 <0.006）”；但裁模板要用原图 PNG（同文件“格式约定”一节）。

**测试**

- `test_context` 用真 OCR + 真 screen_info，再注入 `MockController`；`FixtureController` 是“会反应的假游戏”，用来跑多帧流程（`docs/develop/testing/README.md` §2）。
- 完整性判据：每个节点的每个分支都要有单测；返回 WAIT 或用了 `until_*` 的节点，额外要有流程测试；写测试时人工对照下游 `@node_from` 的 status（同文件 §3）。

**更新下发**：代码和 assets（画面、模板）都在主仓里，启动器用 git 拉最新代码（`src/one_dragon/devtools/python_launcher.py:284-295`，由 `auto_update_code` 控制）。游戏更新后，修复就是改 YAML 或模板再发版，用户下次启动自动拿到。OCR 模型单独从 OneDragon-Env 的 release 下载（`onnx_ocr_matcher.py:30-47`）。

---

## 对手机端 v1 的启示

对照 `D:\gui-agent\spikes\ARCHITECTURE-mobile-v1.md`：Kotlin、`core/` 加 `app/` 两模块、无障碍服务读树加截图 OCR、官方任务包 JSON、v1 先求稳定。

### A. 值得借鉴（按收益排序）

| # | 做法 | OneDragon 证据 | 安卓可行性 | 改动 | 怎么落到 v1 |
|---|---|---|---|---|---|
| 1 | **先按区域裁剪，再 OCR** | `ocr_service.py:175-193`；§1.4 | 可行：`takeScreenshot` 拿到 Bitmap 后裁剪，送 det/rec | 小到中 | `ocrText` 找法和 OCR 类预期加可选 `region`，用屏幕比例坐标，如 `[0,0.1,1,0.35]` 表示“列表上部”。没写 `region` 时整屏识别，此时检测长边限制取 ≥ 截图长边（不缩小），以免重复冻结帧上的召回下降。直接改善微信（M3）的速度和召回 |
| 2 | **同一帧只识别一次（帧级 OCR 缓存）** | `ocr_service.py:99-132`、`:157-209` | 可行 | 小 | 运行时每次截图生成 `Frame(id, bitmap, tree?)`。同一步里“找目标”和“查预期”共用一份 OCR 结果，缓存键为 (帧, 区域)。符合“只放内存、用完即弃”（§6）：步骤结束就丢掉 |
| 3 | **四种轮次结果 + 每步超时 / 重试上限，“等待不消耗重试次数”** | `operation_round_result.py:5-9`；`operation.py:474-486`、`:523-526` | 可行，纯 core 逻辑 | 小 | 解释器内部用 `Success / Retry / Wait / Fail`：“界面还没稳定、还在加载”算 Wait，只受时间上限约束；“找不到、点了没反应”算 Retry，计次数。任务包里每步可选 `timeoutMs`、`maxRetry`，有默认值。v1 的 `waitForSettle` 是**事件驱动**的，比 OneDragon 的固定 sleep 好，**保留**，不要学它 sleep 1 s |
| 4 | **“看到即成功”的点击语义（`until_find` / `until_not_find`）** | `operation.py:930-966` | 可行 | 小 | v1 的 `expect` 已经是这个意思。补一条重试规则：动作后没看到预期，先重新感知判断，**不要立即重复点击**；仍在原画面且超过防抖时间才重点，对应 `screen_switch_debounce`（`operation.py:1423-1468`）。可以避免长按菜单这类操作被重复触发 |
| 5 | **画面定义 = 若干个“必须全部命中”的标志（`id_mark`）** | `screen_utils.py:442-480` | 可行：标志可以是树节点（text/desc/id），也可以是 OCR 文字 | 中 | 任务包加可选的 `screens`，例如 `{"id":"wechat.chat_list","app":"com.tencent.mm","marks":[…find…]}`；预期里可以写 `{"screen":"wechat.chat_list"}`。v1 的“不在预期首屏就按返回，最多 3 次”就变成“按返回直到 screen 命中”，判定更明确。**先不做路由图**，但 schema 里预留可选的 `goto` 字段 |
| 6 | **按应用缩小候选画面（scope）** | `screen_loader.py:436-480`；`application_base.py:86-90` | 更容易：无障碍事件和根节点直接给出前台包名 | 小 | 画面识别只在 `screen.app == 前台包名` 的画面里找；不在 `requires` 里的包一律不匹配，与不变量“只操作 `requires` 列出的 App”一致 |
| 7 | **从上一个已知画面开始判定** | `screen_utils.py:376-440` | 可行 | 小 | 有了第 5 条之后再做：先判“上一步预期的画面”，再判其他 |
| 8 | **ONNX 会话：启动时预热、关自旋、DML/GPU 串行** | `predict_det.py:57-59`；`inference_engine.py:139-150`；`gpu_executor.py:19-24` | onnxruntime-android 支持 `addConfigEntry("session.intra_op.allow_spinning","0")` 和显式线程数；预热可行 | 小 | `Ocr.kt` 在服务连接后，用实际输入尺寸对 det **和 rec** 各跑一次（OneDragon 只预热了 det）；线程数显式设成大核数量，需在 Neo5 上测 2/4。若试 NNAPI 或 GPU EP，所有推理走单线程执行器。效果都要在 Neo5 上实测，**未验证** |
| 9 | **截图存档 + 会反应的假设备做流程测试** | `screenshot_archive.md`；`testing/README.md` §2–3 | 可行，正好对应 v1 第 8 节 | 中 | `tools/probe.py` 录下的树和截图脱敏后，按 `fixtures/<app>/<screen>/<state>.{json,webp}` 归档。core 里做 `FakeScreen`：按“动作 → 下一帧”的脚本返回帧，用来测等待、重试、停下分支。测试判据照搬：每步每个分支有用例；会 Wait 的步骤要有多帧用例 |
| 10 | **trace 只记耗时和结果码** | `DebugTraceBus` 的 perf/timeline（`onnx_ocr_matcher.py:544-563`；`operation.py:472`） | 可行 | 小 | 和 v1“日志只记步骤号、耗时、结果码”一致。每轮记 `感知 ms / OCR ms / 动作 ms / 等稳定 ms`，20 次回归时直接拿到瓶颈分布 |
| 11 | **区域在不同输入后端下的“替代动作”** | `ScreenArea.gamepad_key`（`background_mode_design.md:43-87`） | 可行 | 小 | v1 已有“优先 `performAction`，不可点时退回手势”。把它写成找法结果的属性（`clickable` 节点 / 坐标），由动作层选后端，任务包不需要知道 |
| 12 | **画面 / 区域编辑工具** | `devtools_screen_manage_interface.py` | 电脑端可行 | 中到大，**v1 之后** | `probe.py` 后续可加“在截图或树上框区域、生成 find JSON”的功能 |

### B. 不适用或暂不采用

| 做法 | 原因 |
|---|---|
| 后台模式（PostMessage、虚拟手柄） | 无障碍手势和 `performAction` 本来不依赖焦点；v1 明确不做虚拟屏和后台 |
| PrintWindow/BitBlt 回退链、GDI 资源复用 | 安卓只有无障碍 `takeScreenshot`（API 30），或 MediaProjection（要额外授权，v1 不用）。`takeScreenshot` 有系统强制的最小调用间隔，具体值需在 Neo5 上实测确认，所以 **20 ms 高频循环不可能**，战斗那套高频并行检测不适用 |
| 固定 1920×1080 + 绝对像素矩形 | 手机分辨率和长宽比各异，状态栏、刘海、导航栏也会变。区域要用比例坐标，或相对锚点（某节点或某 OCR 文字的相对位置）。OneDragon 能用绝对坐标，靠的是先缩放到统一分辨率（`pc_screenshot_controller.py:76-77`） |
| 代码插件（扫描 `_factory.py`、热重载、导入 zip） | 侧载 App 动态加载代码（DexClassLoader）技术上可行，但违背“只运行签名任务包”的安全不变量，也会让审查失效。**任务包只能是数据**；扩展点在 Kotlin 里编译期注册（见 C） |
| Floyd 路由图、`goto_list` 自动导航 | v1 三个任务都从“启动 App”开始，已决定不做路由。只在 schema 里预留字段 |
| 每步固定 sleep 0.5–1 s | 我们有界面变化事件，事件驱动的 settle 更快更稳；固定 sleep 是 PC 游戏拿不到 UI 事件的权宜之计 |
| 16 线程并行检测、状态记录 + 条件树 | 为实时战斗设计。v1 是顺序的几步任务，不需要 |
| 模板匹配 | 对图标按钮（如微信的“+”）有用，但要带 OpenCV for Android（体积大）和模板维护成本。v1 的三个任务用树或 OCR 文字就够，**先不做**；Finder 注册表里预留 `template` 类型 |
| git 拉代码自动更新 | v1 定的是“用户点检查更新才联网，固定地址，签名校验”，按原方案 |

### C. 骨架应该提供哪些扩展点（可直接写进架构文档第 3–5 节）

原则：OneDragon 的“可插拔”有两种。一种是**代码插件**（应用工厂）。另一种是**数据驱动**：画面 YAML、战斗 YAML 里的 `op_name` 映射到注册好的实现。社区贡献量主要来自后者。我们只做后者：**任务包是数据，扩展点是 core 里按字符串类型注册的实现**，未知类型在校验阶段拒绝。

1. **`Perception`（感知源）**：`interface Perception { fun capture(): Frame }`，`Frame` 带 `id`、`packageName`、可选 `tree`、可选 `bitmap`，并惰性提供 `ocr(region)`，结果按区域缓存（借鉴 A1、A2）。
   - v1 实现两个：`TreePerception`、`ScreenshotOcrPerception`；
   - OCR 后端再抽一层 `TextRecognizer`，以后可以换模型或加 NNAPI。
2. **`Finder` 注册表**：`Map<String, FinderFactory>`，键就是任务包里的找法名（`text`、`desc`、`id`、`listItem`、`lastMessage`、`ocrText`，以后加 `template`）。
   - 每个 Finder 声明它需要哪种感知（`needs = TREE | OCR`），运行时据此决定截不截图；
   - 统一支持可选的 `region`（比例坐标）和 `under` / `anchor`（相对锚点）。
3. **`Expectation` 注册表**：`anyText`、`gone`、`toast`、`screen`（借鉴 A5），机制同上。
4. **`Action` 集合固定，不对任务包开放**：launch、tap、long、back。这是安全不变量，不做扩展点；内部按“performAction → 手势”选后端（A11）。
5. **`ScreenDef`（可选）**：`id`、`app`、`marks`（若干 Finder，全部命中才算），预留 `goto`。用于预期判断、首屏校正和失败说明，例如“当前在‘聊天’而不是‘会话列表’”。
6. **步骤运行协议**：解释器内部的四种结果（A3）；任务包每步可选 `timeoutMs`、`maxRetry`；“动作后未达预期，先重新感知，过了防抖时间才重做动作”（A4）。
7. **`PackSource`（任务包来源）**：内置包（App assets）、签名下载包、本地补教补丁（只能覆盖坐标）。
   - 加载顺序和冲突规则学 OneDragon（`APP_ID` 重复时先注册者胜并告警，`application_factory_manager.py:400-406`）：官方包 id 唯一，补丁只能叠加在已有步骤上；
   - 包头加 `schemaVersion` 和 `needs`（如 `["ocr"]`），旧 App 遇到新包明确拒绝，而不是跑到一半失败。
8. **元数据，用于维护**：包头写 `author`、`version`、`testedAppVersions`（如 `{"com.tencent.mm":"8.0.x"}`）。运行失败时把实际版本号写进结果码，方便判断是不是 App 更新导致。OneDragon 靠画面建档 + 截图存档 + 测试仓来应对游戏更新（§2.5）；我们对应的是 `probe.py` 抓取、脱敏 fixture、core 回归测试。
9. **Trace**：每轮记录各阶段耗时和结果码（A10），不含界面内容。

建议在架构文档的“10. 未决与风险 / 骨架的可插拔程度”一节，把上面 1–9 作为 v1 骨架的扩展点清单。其中 1、2、3、6、7 是 M1 就要有的接口；5 和 `region` 在 M3（微信）前补齐；`template`、路由、编辑工具放到 v1 之后。

---

## 附：未确认项

- PrintWindow 和 BitBlt 的单次截图耗时、OCR 每次耗时：源码只有 debug 日志和 trace 字段，没有记录的数字。
- `screen_scope_design.md` 的“70 → 30 次”是估算，不是实测。
- 插件能否附带模板：没找到相应加载路径（模板根目录写死为 `assets/template`），判断为不支持。如果有别的机制，是我没找到。
- 安卓 `takeScreenshot` 的最小调用间隔：需在 Neo5 上实测。
