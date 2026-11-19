# 文档地图

谁读、写什么、多久改一次。**新加 md 先在这里登记一行。**

| 文件 | 谁读 | 用途 | 更新规则 |
|---|---|---|---|
| [CLAUDE.md](../CLAUDE.md) | 所有 agent | 项目事实：不变量、构建命令、坑、协作方式 | 改了就生效；≤80 行 |
| [docs/ARCHITECTURE.md](ARCHITECTURE.md) | 所有 agent | 设计现状：结构、任务包格式、运行时、扩展点 | 只描述现状，不写进度和理由 |
| [docs/DECISIONS.md](DECISIONS.md) | 所有 agent（只读） | 为什么这么定：理由、依据、可逆性 | **只追加，不改旧条目** |
| [docs/STATE.md](STATE.md) | 所有 agent | 现在到哪了：里程碑现状、未决、下一步 | 每次收尾覆盖更新 |
| [docs/CONDUCTOR.md](CONDUCTOR.md) | **只主控** | 流程：分档、委派自检、判定、收尾、越界红线 | 主控自己改；不给子智能体 |
| [specs/CARD.md](../specs/CARD.md) | 主控 | T2 任务卡模板与 15 行自检 | 改了模板同步改 CONDUCTOR §2 |
| [core/CLAUDE.md](../core/CLAUDE.md) | 动 core 的 agent | core 子包、依赖方向、测试特例 | 随代码 |
| [app/CLAUDE.md](../app/CLAUDE.md) | 动 app 的 agent | 后台模式、虚拟屏、OCR 资产 | 随代码 |
| [README.md](../README.md) | 人 | 是什么、怎么装、怎么用 | 面向外部，不含内部流程 |
| [docs/ocr-experiments.md](ocr-experiments.md) | 做 OCR 的人 | 实验数据与重跑命令 | **冻结**：只追加“日期+新结果” |
| [docs/virtual-display-experiments.md](virtual-display-experiments.md) | 做后台模式的人 | 虚拟屏实验记录 | **冻结**：只追加 |
| [docs/research/next-phase-design-review.md](research/next-phase-design-review.md) | 主控 | 下一阶段路线图与优先级 | 阶段切换时更新 §9 |
| [docs/research/bettergi-speed-and-plugins.md](research/bettergi-speed-and-plugins.md) | 主控 | 外部项目调研 | **冻结** |
| [docs/research/onedragon-speed-and-plugins.md](research/onedragon-speed-and-plugins.md) | 主控 | 外部项目调研 | **冻结** |
| [docs/research/dsh-computer-use-win.md](research/dsh-computer-use-win.md) | 主控 | 外部项目调研 | **冻结** |
| [tools/probe/README.md](../tools/probe/README.md) | 用探针的人 | 抓树、OCR 标注、模型转换 | 换工具时改 |
| [tools/site-replica/README.md](../tools/site-replica/README.md) | 做 site 层的人 | 本机副本用法与线上摸底记录 | 副本改动时更新 |

## 冲突时按这个顺序

`CLAUDE.md` > 模块 `CLAUDE.md` > `ARCHITECTURE.md`（现状） > `README.md`（对外）

`CONDUCTOR.md` 只约束主控，与其他文件不冲突。

## 不在这张表里的 md

`tools/probe/.venv/` 下的是第三方包的 LICENSE/README，不参与维护；`tools/probe/out/` 是产物，gitignored。
