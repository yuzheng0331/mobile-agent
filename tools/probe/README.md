# tools/probe

电脑端工具。本 README 下面是 `probe.py`（抓界面树和截图）的用法，最早用于第 1 关可行性探测，现在用于任务包定稿前 probe 所有起点和分支（架构文档第 8 节）。其他工具：

| 工具 | 用途 | 用法见 |
|---|---|---|
| `ocr_labels.py`、`ocr_reference.py` | OCR 标准答案和对照参考 | `docs/ocr-experiments.md` 第 6 节 |
| `ocr_tflite.py`、`ocr_litert_assets.sh` | 把官方 ONNX 转成识别用的固定尺寸 `.tflite` | `app/CLAUDE.md`“OCR 模型” |
| `vd/` | 虚拟屏实验：shell 身份建屏、主屏流畅度 | `docs/virtual-display-experiments.md` 重跑步骤 |
| `shell_start.sh` | 开发时启动后台模式的 shell 服务 | `app/CLAUDE.md`“后台模式” |

`out/` 里是真实界面内容，不进 git。

# 第 1 关：界面树可行性探测

目的：确认 微信 / QQ / 夸克 的关键界面能不能不靠坐标定位目标。只读界面，不做自动点击（`launch` 只启动 App）。

## 准备

1. 手机：开发者选项 → 打开 USB 调试，用数据线连电脑，手机上允许调试授权。
2. 电脑（任意目录建虚拟环境即可，ADB 已随 uiautomator2 自带）：

   ```bash
   python -m venv .venv
   .venv/Scripts/python -m pip install uiautomator2
   ```

3. 第一次运行时 uiautomator2 会往手机装一个辅助组件，vivo/iQOO 可能弹安装确认，要手动允许。

`out/` 里保存的是真实界面内容（聊天记录、截图），只留在本机，不要提交或发给别人。建议用小号测。

## 要采集的界面

手机上手动停到每个界面，再在电脑运行 `snap`：

| 任务 | 界面 | 命令 |
|---|---|---|
| 微信 | 聊天列表 | `python probe.py snap wechat_list` |
| | 最上面会话的聊天页 | `python probe.py snap wechat_chat` |
| | 长按最后一条文字消息后的菜单 | `python probe.py snap wechat_menu` |
| | 最后一条是图片/语音时长按的菜单 | `python probe.py snap wechat_menu_media` |
| | 点"复制"能否看到提示 | 先运行 `python probe.py toast`，再在手机上点复制 |
| QQ | 同上四个界面 + toast | `qq_list` / `qq_chat` / `qq_menu` / `qq_menu_media` |
| 夸克 | 首页、书签页、打开第一个书签后 | `quark_home` / `quark_bookmarks` / `quark_site` |
| 启动 | 按包名启动小红书、抖音 | `python probe.py launch com.xingin.xhs`、`python probe.py launch com.ss.android.ugc.aweme` |

包名不确定时，把 App 切到前台后运行 `snap`，第一行会显示包名。

最后运行 `python probe.py report` 看汇总。

## 怎么判断

- 每个界面里"要点的那个目标"（第一个会话、最后一条消息、复制、第一个书签）在明细里不是 `!! none` 就算找得到。总体可定位比例看个参考，关键是这几个目标。
- 疑似混淆 ID 多不要紧，只要目标有 text / desc / 子节点文字。
- 通过：关键目标都能定位，或截图正常可以退回 OCR。
- 否决：关键界面"读不到：树几乎为空且截图被禁止"。
- toast 捕获不到不算否决，说明复制成功需要另找确认方式。

结果贴回来（`report` 的输出加上关键界面的明细即可，可以把聊天内容打码）。
