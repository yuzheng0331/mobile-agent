package dev.phoneagent.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.phoneagent.core.pack.Pack
import dev.phoneagent.core.pack.Packs
import dev.phoneagent.core.pack.validate
import dev.phoneagent.core.run.RunObserver
import dev.phoneagent.core.run.Runner
import dev.phoneagent.core.run.StepTrace
import java.util.concurrent.Executors

/** Bundled packs with a run button each, plus a link to the accessibility settings. */
class MainActivity : Activity() {

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var service: TextView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        service = TextView(this).apply { textSize = 16f }
        status = TextView(this).apply { textSize = 16f }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            addView(service)
            addView(status)
            addView(Button(context).apply {
                text = "打开无障碍设置"
                setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
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
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun run(pack: Pack) {
        val device = AgentService.instance ?: return run { status.text = "请先开启无障碍服务" }
        status.text = "运行中：${pack.id}"
        device.useOcr("ocr" in pack.needs)
        worker.execute {
            val lines = mutableListOf<String>()
            val result = Runner(device, observer = object : RunObserver {
                override fun onStep(trace: StepTrace) {
                    device.hideHint()
                    val line = timing(trace)
                    Log.i(TAG, "${pack.id} $line")
                    runOnUiThread { lines += line; status.text = "运行中：${pack.id}\n" + lines.joinToString("\n") }
                }

                // The service's overlay strip: vivo drops toasts while this activity is in the background.
                override fun onStuck(reason: String) {
                    device.showHint("Phone Agent：$reason\n如果有弹窗，请手动关掉，关掉后会自动重试（最多等 20 秒）")
                }
            }).run(pack)
            Log.i(TAG, "${pack.id} 结果：${result.reason ?: "完成"}")
            device.hideHint()
            // The target app hides this screen, so say why it stopped over it, on the service's overlay
            // strip: vivo drops toasts from a background app. It goes away by itself.
            result.reason?.let { reason ->
                device.showHint("Phone Agent 已停止：$reason")
                Handler(Looper.getMainLooper()).postDelayed({ device.hideHint() }, STOP_HINT_MS)
            }
            runOnUiThread { status.text = (result.reason ?: "完成") + "\n" + lines.joinToString("\n") }
        }
    }

    private companion object {
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
