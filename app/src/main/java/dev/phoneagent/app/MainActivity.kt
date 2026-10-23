package dev.phoneagent.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.phoneagent.core.Pack
import dev.phoneagent.core.Packs
import dev.phoneagent.core.Runner
import dev.phoneagent.core.validate
import java.util.concurrent.Executors

/** Bundled packs with a run button each, plus a link to the accessibility settings. */
class MainActivity : Activity() {

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { textSize = 16f }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
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
        status.text = if (AgentService.instance != null) "无障碍服务：已开启" else "无障碍服务：未开启"
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun run(pack: Pack) {
        val device = AgentService.instance ?: return run { status.text = "请先开启无障碍服务" }
        status.text = "运行中：${pack.id}"
        worker.execute {
            val result = Runner(device).run(pack)
            val timing = result.steps.joinToString("\n") {
                "第 ${it.index + 1} 步 ${it.action}：找 ${it.findMs} / 动 ${it.actMs} / 等 ${it.settleMs} / 查 ${it.checkMs} ms，${it.outcome}"
            }
            runOnUiThread { status.text = (result.reason ?: "完成") + "\n" + timing }
        }
    }

    /** Every `*.json` asset, parsed and validated; a broken pack shows its problems instead of a button. */
    private fun loadPacks(): List<Triple<String, Pack?, List<String>>> =
        assets.list("").orEmpty().filter { it.endsWith(".json") }.map { name ->
            runCatching { Packs.parse(assets.open(name).bufferedReader().readText()) }.fold(
                onSuccess = { Triple(name, it, validate(it)) },
                onFailure = { Triple(name, null, listOf(it.message ?: "无法解析")) },
            )
        }
}
