package dev.phoneagent.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.provider.Settings
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.phoneagent.app.shell.Shell
import dev.phoneagent.core.pack.Pack
import dev.phoneagent.core.pack.Packs
import dev.phoneagent.core.pack.validate
import dev.phoneagent.core.run.Outcome
import dev.phoneagent.core.run.RunObserver
import dev.phoneagent.core.run.Runner
import dev.phoneagent.core.run.StepTrace
import java.util.concurrent.Executors

/** Bundled packs with a run button each, plus a link to the accessibility settings. */
class MainActivity : Activity() {

    private lateinit var service: TextView
    private lateinit var status: TextView
    private lateinit var inBackground: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        service = TextView(this).apply { textSize = 16f }
        status = TextView(this).apply { textSize = 16f }
        // Runs on a virtual display while the user goes on using the phone; needs the shell server (shell.ShellServer).
        inBackground = CheckBox(this).apply { textSize = 16f }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            addView(service)
            addView(inBackground)
            addView(status)
            addView(Button(context).apply {
                text = "打开无障碍设置"
                setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            })
            // Optional: OCR tasks run ~30% faster with our (keyboard-less) input method enabled.
            addView(Button(context).apply {
                text = "启用加速（在输入法列表里打开 Phone Agent 加速）"
                setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
            })
        }
        loadPacks().forEach { (name, pack, problems) ->
            column.addView(Button(this).apply {
                text = pack?.aliases?.firstOrNull() ?: name
                isEnabled = pack != null && problems.isEmpty()
                setOnClickListener { run(pack!!) }
            })
            if (problems.isNotEmpty()) column.addView(TextView(this).apply { text = "$name 无法加载：\n${problems.joinToString("\n")}" })
        }
        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onResume() {
        super.onResume()
        service.text = if (AgentService.instance != null) "无障碍服务：已开启" else "无障碍服务：未开启"
        inBackground.text = "后台运行（在虚拟屏上跑，你可以照常用手机）" + if (Shell.server == null) "：shell 服务未启动" else ""
    }

    private fun run(pack: Pack) {
        val device = AgentService.instance ?: return run { status.text = "请先开启无障碍服务" }
        // The virtual display gets no status bar: make it that much shorter, so apps lay out as on the main display.
        val metrics = windowManager.maximumWindowMetrics
        val statusBar = metrics.windowInsets.getInsets(WindowInsets.Type.statusBars()).top
        val display = if (!inBackground.isChecked) 0 else {
            Shell.display(metrics.bounds.width(), metrics.bounds.height() - statusBar, resources.displayMetrics.densityDpi)
                ?: return run { status.text = "后台运行不可用：shell 服务未启动或建虚拟屏失败（见 tools/probe/shell_start.sh）" }
        }
        status.text = "运行中：${pack.id}"
        val ocr = "ocr" in pack.needs
        device.useOcr(ocr)
        worker.execute {
            val lines = mutableListOf<String>()
            // In the background the user may be typing: never take their keyboard away.
            val fast = ocr && display == 0 && device.useFastCores(true)
            if (ocr && !fast) lines += if (display != 0) "后台运行：OCR 只能用部分 CPU 核" else "未启用加速：OCR 只能用部分 CPU 核，会慢约 30%"
            device.display = display
            device.displayTop = statusBar
            val result = try {
                Runner(device, observer = object : RunObserver {
                    override fun onStep(trace: StepTrace) {
                        device.hideHint()
                        val line = timing(trace)
                        Log.i(TAG, "${pack.id} $line")
                        runOnUiThread { lines += line; status.text = "运行中：${pack.id}\n" + lines.joinToString("\n") }
                    }

                    // The service's overlay strip: vivo drops toasts while this activity is in the background.
                    // A dialog on the virtual display can't be seen, let alone closed, by the user.
                    override fun onStuck(reason: String) {
                        device.showHint(
                            if (display != 0) "Phone Agent 后台任务卡住：$reason"
                            else "Phone Agent：$reason\n如果有弹窗，请手动关掉，关掉后会自动重试（最多等 20 秒）",
                        )
                    }
                }).run(pack)
            } finally {
                device.display = 0
                // Back to the user's keyboard whatever happened (a crash is covered on the next service start).
                if (fast) device.useFastCores(false)
            }
            // A required app opened on the main display leaves the virtual one: say so instead of "wrong app".
            val pulled = device.foreground(0).takeIf { display != 0 && result.steps.lastOrNull()?.outcome == Outcome.WRONG_APP && it in pack.requires }
            val reason = result.reason?.let { if (pulled != null) "$it（$pulled 在主屏打开了，后台任务没法再操作它）" else it }
            Log.i(TAG, "${pack.id} 结果：${reason ?: "完成"}")
            device.hideHint()
            // The target app hides this screen, so say why it stopped over it, on the service's overlay
            // strip: vivo drops toasts from a background app. It goes away by itself.
            reason?.let {
                device.showHint("Phone Agent 已停止：$it")
                Handler(Looper.getMainLooper()).postDelayed({ device.hideHint() }, STOP_HINT_MS)
            }
            runOnUiThread { status.text = (reason ?: "完成") + "\n" + lines.joinToString("\n") }
        }
    }

    private companion object {
        // The process's, not this activity's: the system destroys a background activity when memory
        // runs low (it did mid-run on 2026-10-26), and the run must go on to its end and restore the keyboard.
        val worker = Executors.newSingleThreadExecutor()
        const val TAG = "PhoneAgent"
        const val STOP_HINT_MS = 6_000L
    }

    private fun timing(it: StepTrace) =
        "第 ${it.index + 1} 步 ${it.action}：找 ${it.findMs} / 动 ${it.actMs} / 等 ${it.settleMs} / 查 ${it.checkMs} ms，${it.outcome}"

    /** Every `*.json` asset, parsed and validated; a broken pack shows its problems instead of a button. */
    private fun loadPacks(): List<Triple<String, Pack?, List<String>>> =
        assets.list("").orEmpty().filter { it.endsWith(".json") }.map { name ->
            runCatching { Packs.parse(assets.open(name).bufferedReader().readText()) }.fold(
                onSuccess = { Triple(name, it, validate(it)) },
                onFailure = { Triple(name, null, listOf(it.message ?: "无法解析")) },
            )
        }
}
