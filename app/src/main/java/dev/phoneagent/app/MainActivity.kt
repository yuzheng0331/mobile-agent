package dev.phoneagent.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
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
import dev.phoneagent.app.shell.AdbLauncher
import dev.phoneagent.app.shell.Pairing
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
        // Runs on a virtual display while the user goes on using the phone (Background: role or shell mode).
        inBackground = CheckBox(this).apply {
            textSize = 16f
            isChecked = Background.wanted(context)
            setOnCheckedChangeListener { _, on -> Background.want(context, on) }
        }
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
            // Once, for background runs without a computer: the code goes in through a notification (Pairing).
            addView(Button(context).apply {
                text = "配对无线调试（后台运行用，只需一次）"
                setOnClickListener {
                    if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
                    }
                    Pairing.show(context)
                    startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                }
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
        inBackground.text = "后台运行（在虚拟屏上跑，你可以照常用手机）" + when {
            Background.hasRole(this) -> ""
            Shell.server != null -> "：shell 模式"
            Background.lostRole(this) -> "：授权没了，连着 Wi-Fi 跑一次后台任务就会恢复"
            else -> "：未授权，先配对无线调试"
        }
    }

    private fun run(pack: Pack) {
        val device = AgentService.instance ?: return run { status.text = "请先开启无障碍服务" }
        // The virtual display gets no status bar: make it that much shorter, so apps lay out as on the main display.
        val metrics = windowManager.maximumWindowMetrics
        val statusBar = metrics.windowInsets.getInsets(WindowInsets.Type.statusBars()).top
        val background = inBackground.isChecked
        val app = applicationContext
        val dpi = resources.displayMetrics.densityDpi
        status.text = "运行中：${pack.id}"
        val ocr = "ocr" in pack.needs
        device.useOcr(ocr)
        worker.execute {
            // No role and no shell server: start one over wireless debugging (it also gives the role back).
            if (background && !Background.hasRole(app) && Shell.server == null) AdbLauncher.start(app)
            // Neither role nor shell server: run on the main display rather than not at all.
            val display = if (!background) 0 else Background.display(app, metrics.bounds.width(), metrics.bounds.height() - statusBar, dpi) ?: 0
            val lines = mutableListOf<String>()
            if (background && display == 0) lines += "后台运行不可用（${AdbLauncher.problem ?: "没有授权，也没有 shell 服务"}），改在前台跑"
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
                if (Background.owns(display)) Background.release()
                // Back to the user's keyboard whatever happened (a crash is covered on the next service start).
                if (fast) device.useFastCores(false)
            }
            // A required app the user has open on the main display: it left the virtual one (wrong app), or the run
            // would not start it there (launch failed), as that would take it from the user.
            val last = result.steps.lastOrNull()
            val inUse = device.foreground(0).takeIf { display != 0 && it in pack.requires }
                ?.let { runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)) }.getOrDefault(it) }
            val reason = result.reason?.let {
                when {
                    inUse != null && last?.outcome == Outcome.WRONG_APP -> "$it（$inUse 在主屏打开了，后台任务没法再操作它）"
                    inUse != null && last?.outcome == Outcome.ACTION_FAILED && last.action == "launch" -> "$it（$inUse 正在主屏使用，后台任务不会把它拉走；离开它后再试）"
                    else -> it
                }
            }
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
