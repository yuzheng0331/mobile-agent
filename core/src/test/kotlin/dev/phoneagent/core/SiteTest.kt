package dev.phoneagent.core

import dev.phoneagent.core.pack.BOOL
import dev.phoneagent.core.pack.Expr
import dev.phoneagent.core.pack.NUMBER
import dev.phoneagent.core.pack.Packs
import dev.phoneagent.core.pack.STRING
import dev.phoneagent.core.pack.SiteOp
import dev.phoneagent.core.pack.Type
import dev.phoneagent.core.pack.eval
import dev.phoneagent.core.pack.type
import dev.phoneagent.core.pack.validate
import dev.phoneagent.core.run.Http
import dev.phoneagent.core.run.HttpRequest
import dev.phoneagent.core.run.HttpResponse
import dev.phoneagent.core.run.Outcome
import dev.phoneagent.core.run.Runner
import dev.phoneagent.core.run.answer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.net.http.HttpRequest as JavaRequest

private const val BASE = "https://backend.example/projects/p1"

/** Scripted site: [answer] replies to each request; everything sent is kept. */
private class FakeHttp(private val answer: (HttpRequest) -> HttpResponse) : Http {
    override val userId = "u1"
    val sent = mutableListOf<HttpRequest>()
    override fun send(request: HttpRequest) = answer(request).also { sent += request }
}

/**
 * The replica in tools/site-replica, logged in the way the app's login module will do it (GoTrue
 * password grant), sending with the user's token and never following a redirect.
 */
private class ReplicaHttp(private val key: String, account: String) : Http {
    private val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build()
    private val token: String
    override val userId: String
    val sent = mutableListOf<HttpRequest>()

    init {
        // The seed's test password (tools/site-replica/supabase/seed.sql), local only.
        val login = Json.parseToJsonElement(exchange(HttpRequest("POST", "$REPLICA/auth/v1/token?grant_type=password",
            mapOf("Content-Type" to "application/json"), """{"email":"acc-$account@replica.test","password":"replica-test"}""")).body).jsonObject
        token = login.getValue("access_token").jsonPrimitive.content
        userId = login.getValue("user").jsonObject.getValue("id").jsonPrimitive.content
    }

    override fun send(request: HttpRequest): HttpResponse {
        sent += request
        return exchange(request.copy(headers = request.headers + ("Authorization" to "Bearer $token")))
    }

    private fun exchange(request: HttpRequest): HttpResponse {
        val builder = JavaRequest.newBuilder(URI(request.url)).timeout(Duration.ofSeconds(10))
            .method(request.method, request.body?.let(BodyPublishers::ofString) ?: BodyPublishers.noBody())
        (request.headers + ("apikey" to key)).forEach { (name, value) -> builder.header(name, value) }
        return runCatching { client.send(builder.build(), BodyHandlers.ofString()) }
            .fold({ HttpResponse(it.statusCode(), it.body()) }, { HttpResponse(0, "") })
    }

    companion object {
        const val REPLICA = "http://127.0.0.1:54321"
    }
}

// The real site's tables and allowed values (tools/probe/out/site, 2026-11-02).
private const val SITE = """
  "site": {"base": "BASE", "ops": {
    "activeTasks": {"select": "tasks", "where": {"status": "active"}, "order": "created_at.asc",
      "returns": {"id": "string", "name": "string", "platform": ["小红书", "抖音", "微信", "B站", "微博", "微店", "美团", "其他"],
                  "points": "number", "deadline": "string?"}},
    "mine": {"select": "submissions", "where": {"task_id": "{task}", "user_id": "{user}"},
      "params": {"task": "string", "user": "string"}, "returns": {"id": "string"}},
    "submit": {"insert": "submissions",
      "params": {"task_id": "string", "user_id": "string", "image_path": "string", "source": ["manual"]},
      "returns": {"id": "string", "points_awarded": "number"}}
  }}"""

/** Submits every active task this user has not submitted yet, except those on 其他. */
private const val SUBMIT_NEW = """[
  {"id": "fetch", "do": "http", "call": "activeTasks", "save": "tasks"},
  {"id": "each", "forEach": "tasks", "as": "t", "key": "t.id", "max": 20, "onStuck": "skip", "body": [
    {"id": "check", "do": "http", "call": "mine", "with": {"task": "{t.id}", "user": "{me.id}"}, "save": "done"},
    {"id": "new", "when": "size(done) == 0 && t.platform != '其他'", "then": [
      {"id": "submit", "do": "http", "call": "submit",
       "with": {"task_id": "{t.id}", "user_id": "{me.id}", "image_path": "{me.id}/tasks/{t.id}/proof.png", "source": "manual"}}
    ]}
  ]}
]"""

private fun sitePack(steps: String, base: String = BASE) =
    Packs.parse("""{"schemaVersion":1,"id":"site","requires":["$QQ"],${SITE.replace("BASE", base)},"steps":$steps}""")

private fun task(id: String, platform: String, extra: String = "") =
    """{"id":"$id","name":"任务$id","platform":"$platform","points":1,"deadline":null$extra}"""

private fun ok(body: String) = HttpResponse(200, body)

class SiteTest {
    @Test
    fun `a site pack fetches, loops, branches and submits only what is new`() {
        val http = FakeHttp { request ->
            when {
                "/tasks?" in request.url -> ok("[${task("t1", "微信", ""","description":"多出来的字段"""")},${task("t2", "其他")},${task("t3", "小红书")},${task("t1", "微信")}]")
                request.method == "GET" -> ok(if ("task_id=eq.t3" in request.url) """[{"id":"s3"}]""" else "[]")
                else -> HttpResponse(201, """[{"id":"s1","points_awarded":1}]""")
            }
        }
        val pack = sitePack(SUBMIT_NEW)
        assertEquals(emptyList(), validate(pack))
        val result = Runner(FakeDevice(qqList), http = http).run(pack)
        assertTrue(result.ok, result.reason)
        assertEquals(emptyList(), result.skipped)
        assertEquals("$BASE/rest/v1/tasks?select=id,name,platform,points,deadline&status=eq.active&order=created_at.asc", http.sent[0].url)
        // The repeated t1 is dropped by key; only t1 is new and not on 其他.
        val checks = http.sent.filter { it.method == "GET" && "/submissions?" in it.url }
        assertEquals("$BASE/rest/v1/submissions?select=id&task_id=eq.t1&user_id=eq.u1", checks[0].url)
        assertEquals(listOf("t1", "t2", "t3"), checks.map { it.url.substringAfter("task_id=eq.").substringBefore("&") })
        val post = http.sent.single { it.method == "POST" }
        assertEquals("$BASE/rest/v1/submissions?select=id,points_awarded", post.url)
        assertEquals("return=representation", post.headers["Prefer"])
        assertEquals(Json.parseToJsonElement("""{"task_id":"t1","user_id":"u1","image_path":"u1/tasks/t1/proof.png","source":"manual"}"""),
            Json.parseToJsonElement(post.body!!))
    }

    @Test
    fun `each site failure has its own outcome, and only the declared fields get through`() {
        val ops = sitePack(SUBMIT_NEW).site!!.ops
        val read = ops.getValue("activeTasks")
        val write = ops.getValue("submit")
        fun outcome(op: SiteOp, status: Int, body: String = "") = answer(op, HttpResponse(status, body)).outcome
        assertEquals(Outcome.SITE_AUTH, outcome(read, 401))
        assertEquals(Outcome.SITE_DENIED, outcome(write, 403, """{"code":"42501","message":"new row violates row-level security policy"}"""))
        assertEquals(Outcome.SITE_BUSY, outcome(read, 429))
        assertEquals(Outcome.SITE_DOWN, outcome(read, 503))
        assertEquals(Outcome.SITE_DOWN, outcome(read, 0))
        // A write may have been done before its answer was lost.
        assertEquals(Outcome.SITE_UNKNOWN, outcome(write, 0))
        assertEquals(Outcome.SITE_UNKNOWN, outcome(write, 502))
        assertEquals(Outcome.SITE_CHANGED, outcome(read, 400, """{"code":"42703","message":"column tasks.name does not exist"}"""))
        assertEquals(Outcome.SITE_CHANGED, outcome(read, 404))
        assertEquals(Outcome.SITE_CHANGED, outcome(read, 302))
        val refused = answer(write, HttpResponse(400, """{"code":"P0001","message":"该任务已被标记为已完成，无法提交"}"""))
        assertEquals(Outcome.SITE_REFUSED to "该任务已被标记为已完成，无法提交", refused.outcome to refused.detail)

        assertEquals(JsonArray(emptyList()), answer(read, ok("[]")).value)
        val missing = answer(read, ok("""[{"id":"t1","platform":"微信","points":1,"deadline":null}]"""))
        assertEquals(Outcome.SITE_CHANGED to "没有字段 name", missing.outcome to missing.detail)
        // The admin had the site's AI rename a value.
        val renamed = answer(read, ok("[${task("t1", "小红书App")}]"))
        assertTrue(renamed.outcome == Outcome.SITE_CHANGED && "可选值" in renamed.detail!!)
        assertEquals(Outcome.SITE_CHANGED, outcome(read, 200, """[{"id":1,"name":"x","platform":"微信","points":1,"deadline":null}]"""))
        assertEquals(Outcome.SITE_CHANGED, outcome(read, 200, "<html>项目已停用</html>"))
        assertEquals(Outcome.SITE_CHANGED, outcome(read, 200, "[" + List(20_000) { task("t$it", "微信") }.joinToString(",") + "]"))
        assertEquals(Outcome.SITE_CHANGED, outcome(write, 201, "[]"))
        val extra = answer(read, ok("[${task("t1", "微信", ""","account":"别人的账号"""")}]"))
        assertEquals(setOf("id", "name", "platform", "points", "deadline"), extra.value!!.jsonArray.single().jsonObject.keys)
    }

    @Test
    fun `a write without an answer is never resent, and a skipped round lets the next go on`() {
        val http = FakeHttp { request ->
            when {
                "/tasks?" in request.url -> ok("[${task("t1", "微信")},${task("t2", "抖音")}]")
                request.method == "GET" -> ok("[]")
                else -> HttpResponse(0, "")
            }
        }
        val result = Runner(FakeDevice(qqList), http = http).run(sitePack(SUBMIT_NEW))
        assertTrue(result.ok, result.reason)
        assertEquals(2, result.skipped.size)
        assertTrue(result.skipped[0].startsWith("第 2 步第 1 轮 › 第 2 步 › 第 1 步（submit）：发给网站后没收到回复"), result.skipped[0])
        assertEquals(listOf("t1", "t2"), http.sent.filter { it.method == "POST" }
            .map { Json.parseToJsonElement(it.body!!).jsonObject.getValue("task_id").jsonPrimitive.content })
    }

    @Test
    fun `more items than max does nothing, and a site pack needs a login`() {
        val http = FakeHttp { request -> if ("/tasks?" in request.url) ok("[" + (1..21).joinToString(",") { task("t$it", "微信") } + "]") else ok("[]") }
        val result = Runner(FakeDevice(qqList), http = http).run(sitePack(SUBMIT_NEW))
        assertTrue("有 21 项，超过上限 20" in result.skipped.single(), result.skipped.toString())
        assertEquals(1, http.sent.size)

        assertTrue("还没有登录" in Runner(FakeDevice(qqList)).run(sitePack(SUBMIT_NEW)).reason!!)
    }

    @Test
    fun `values from the site fill a finder's text`() {
        val http = FakeHttp { ok("[${task("x", "微信").replace("任务x", "会话A")}]") }
        val device = FakeDevice(qqList)
        val result = Runner(device, http = http).run(sitePack(
            """[{"do":"http","call":"activeTasks","save":"tasks"},
               {"forEach":"tasks","as":"t","max":5,"body":[{"do":"tap","target":{"intent":"任务的会话","find":[{"text":"{t.name}","exact":true}]},"timeoutMs":600}]}]""",
        ))
        assertTrue(result.ok, result.reason)
        assertEquals(listOf("tap"), device.actions)
    }

    @Test
    fun `the validator refuses bad variables, types and site calls before anything runs`() {
        val fetch = """{"do":"http","call":"activeTasks","save":"tasks"}"""
        val back = """{"do":"back"}"""
        fun loop(body: String, max: Int = 5) = """{"forEach":"tasks","as":"t","max":$max,"body":[$body]}"""
        fun submit(taskId: String, source: String) =
            """{"do":"http","call":"submit","with":{"task_id":"$taskId","user_id":"{me.id}","image_path":"x","source":"$source"}}"""
        val cases = listOf(
            """[{"when":"x > 1","then":[$back]}]""" to "step 1: x is not defined",
            """[$fetch, ${loop("""{"let":{"tasks":"1"}}""")}]""" to "step 2.1: tasks is already defined",
            """[$fetch, ${loop("""{"when":"t.titel == 'a'","then":[$back]}""")}]""" to "step 2.1: t.titel: no field titel",
            """[$fetch, ${loop("""{"let":{"x":"t.points + 'a'"}}""")}]""" to "+ takes two numbers or two strings",
            """[$fetch, ${loop("""{"when":"t.platform == '小红薯'","then":[$back]}""")}]""" to "t.platform and \"小红薯\" can never be equal",
            """[$fetch, ${loop("""{"when":"t.deadline > '2026'","then":[$back]}""")}]""" to "> takes two numbers or two strings",
            """[$fetch, ${loop("""{"when":"t.points","then":[$back]}""")}]""" to "when needs a bool",
            """[$fetch, ${loop(submit("{t.id}", "auto"))}]""" to "source of submit takes manual",
            """[$fetch, ${loop(submit("{t.points}", "manual"))}]""" to "task_id of submit takes string",
            """[$fetch, ${loop("""{"do":"http","call":"mine","with":{"task":"{t.id}"}}""")}]""" to "mine needs user",
            """[$fetch, ${loop("""{"do":"http","call":"mine","with":{"task":"{t.id}","user":"{me.id}","x":"1"}}""")}]""" to "mine has no param x",
            """[{"do":"http","call":"deleteAll"}]""" to "unknown site operation deleteAll",
            """[$fetch, {"forEach":"tasks","as":"t","body":[$back]}]""" to "forEach needs a max above 0",
            """[$fetch, ${loop(loop(back, max = 100).replace("\"as\":\"t\"", "\"as\":\"u\""), max = 200)}]""" to "more than 10000 steps",
            // Variables live in their block only.
            """[$fetch, {"when":"size(tasks) > 0","then":[{"let":{"n":"1"}}]}, {"when":"n == 1","then":[$back]}]""" to "step 3: n is not defined",
            """[{"do":"tap","target":{"intent":"x","find":[{"text":"{t.name}"}]}}]""" to "step 1: t is not defined",
            """[{"do":"back","then":[$back]}]""" to "then does not belong in a do step",
            """[{"do":"back","let":{"a":"1"}}]""" to "exactly one of do, let, when, forEach",
            """[{"id":"a","do":"back"},{"id":"a","do":"back"}]""" to "step id a is used more than once",
            """[{"do":"back","onStuck":"retry"}]""" to "onStuck is stop or skip",
        )
        cases.forEach { (steps, want) ->
            val problems = validate(sitePack(steps))
            assertTrue(problems.any { want in it }, "$want not in $problems")
        }

        fun site(base: String, op: String) = validate(Packs.parse(
            """{"schemaVersion":1,"id":"s","site":{"base":"$base","ops":{"op":$op}},"steps":[{"do":"back"}]}""",
        ))
        val read = """{"select":"tasks","returns":{"id":"string"}}"""
        listOf(
            site("http://example.com/p", read) to "site base",
            site("$BASE?x=1", read) to "site base",
            site("$BASE/", read) to "site base",
            site(BASE, """{"select":"tasks","where":{"id":"{nope}"},"returns":{"id":"string"}}""") to "where uses {nope}, which is not in params",
            site(BASE, """{"select":"tasks","where":{"id":"eq.1&or=(a)"},"returns":{"id":"string"}}""") to "",
            site(BASE, """{"select":"tasks?x","returns":{"id":"string"}}""") to "table tasks?x is not a name",
            site(BASE, """{"select":"tasks","insert":"tasks","returns":{"id":"string"}}""") to "exactly one of select, insert, function",
            site(BASE, """{"select":"tasks","returns":{"id":"text"}}""") to "unknown type text",
            site(BASE, """{"select":"tasks","order":"id desc","returns":{"id":"string"}}""") to "order is column.asc or column.desc",
            site(BASE, """{"insert":"tasks","params":{"a":"string"}}""") to "returns names the fields to read",
        ).forEach { (problems, want) ->
            // A literal where value is sent as data behind eq., so it cannot add a filter: allowed.
            if (want.isEmpty()) assertEquals(emptyList(), problems) else assertTrue(problems.any { want in it }, "$want not in $problems")
        }
        assertTrue(validate(Packs.parse("""{"schemaVersion":1,"id":"s","steps":[{"do":"http","call":"x"}]}""")).any { "http needs the pack's site" in it })
    }

    @Test
    fun `expressions evaluate the fixed subset`() {
        val vars = mapOf(
            "t" to Json.parseToJsonElement("""{"points":2,"name":"微信投票","deadline":null}"""),
            "xs" to Json.parseToJsonElement("[1,2,3]"),
        )
        val types = mapOf(
            "t" to Type.Obj(mapOf("points" to NUMBER, "name" to STRING, "deadline" to Type.Scalar("string", nullable = true))),
            "xs" to Type.ListOf(NUMBER),
        )
        listOf(
            "1 + 2 == 3", "0.1 + 0.2 == 0.3", "'a' + 'b' == 'ab'", "size('abc') == 3 && size(xs) == 3", "'投票' in t.name",
            "2 in xs", "!(t.points > 2) && t.points >= 2", "string(t.points) == '2'", "t.deadline == null",
            "'2026-11-02' < '2026-12-01'", "t.points - 3 + 1 == 0", "false || !false", "'it\\'s' == 'it' + '\\'s'",
        ).forEach { text ->
            val expr = Expr.parse(text)
            assertEquals(BOOL, expr.type(types), text)
            assertEquals(JsonPrimitive(true), expr.eval(vars), text)
        }
        listOf("1 +", "a ==", "(1", "1 2", "f(1)", "'open", "t.", "1 == 2 == 3").forEach { text ->
            assertTrue(runCatching { Expr.parse(text) }.isFailure, text)
        }
    }

    @Test
    fun `against the site replica, new tasks are submitted once and the site's refusals map to outcomes`() {
        val key = System.getenv("SITE_REPLICA_KEY")
        assumeTrue(key != null, "start tools/site-replica and set SITE_REPLICA_KEY to its anon key")
        val http = ReplicaHttp(key!!, "tester1")
        val pack = sitePack(SUBMIT_NEW, base = ReplicaHttp.REPLICA)
        assertEquals(emptyList(), validate(pack))
        val first = Runner(FakeDevice(qqList), http = http).run(pack)
        assertTrue(first.ok && first.skipped.isEmpty(), first.reason ?: first.skipped.toString())
        // A second run finds nothing new: the done task is not listed, and every active one is checked once.
        http.sent.clear()
        val second = Runner(FakeDevice(qqList), http = http).run(pack)
        assertTrue(second.ok && second.skipped.isEmpty(), second.reason ?: second.skipped.toString())
        assertEquals(0, http.sent.count { it.method == "POST" })
        assertEquals(4, http.sent.count { it.method == "GET" && "/submissions?" in it.url })

        fun submit(task: String, user: String) = Runner(FakeDevice(qqList), http = http).run(sitePack(
            """[{"do":"http","call":"submit","with":{"task_id":"$task","user_id":"$user","image_path":"e2e.png","source":"manual"}}]""",
            base = ReplicaHttp.REPLICA,
        ))
        val done = submit("aaaaaaaa-0000-0000-0000-000000000003", "{me.id}")
        assertEquals(Outcome.SITE_REFUSED, done.steps.single().outcome)
        assertTrue("已完成" in done.reason!!, done.reason)
        assertEquals(Outcome.SITE_DENIED, submit("aaaaaaaa-0000-0000-0000-000000000001", "22222222-2222-2222-2222-222222222222").steps.single().outcome)
        val stale = Packs.parse("""{"schemaVersion":1,"id":"stale","site":{"base":"${ReplicaHttp.REPLICA}","ops":
            {"tasks":{"select":"tasks","returns":{"id":"string","titel":"string"}}}},"steps":[{"do":"http","call":"tasks"}]}""")
        assertEquals(Outcome.SITE_CHANGED, Runner(FakeDevice(qqList), http = http).run(stale).steps.single().outcome)
    }
}
