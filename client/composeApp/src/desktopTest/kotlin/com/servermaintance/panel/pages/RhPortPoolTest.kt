package com.servermaintance.panel.pages

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 端口映射 / 端口池的纯函数对照测试。
 *
 * 期望值**逐条取自 2026-10-27 的真实服务器响应**（`GET /relayhub/port-pool`、
 * `GET /relayhub/mappings`，从服务器侧抓取，见 docs/guide/features.md E+ 节）：
 * 端口池的两个写接口（preview/apply）会真的改写生产 frps.toml 并重启 frps，
 * 所以「提交出去的 payload 长什么样」这条必须钉住，不能等真机去发现。
 */
class RhPortPoolTest {

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    /** 真机响应里的 effective / frps_allowports。 */
    private val realStatus = obj(
        """
        {"pool":{"ranges":[{"label":"段1","start":36000,"end":36999},
                           {"label":"段2","start":12838,"end":12838}],
                 "exclude":[],"alloc_policy":"sequential","max_per_client":16,
                 "sync_ufw":true,"protected":[22,80,443,7000,7500,8100,13345]},
         "effective":[[12838,12838],[36000,36999]],
         "ranges_text":"12838, 36000-36999","port_count":1001,
         "frps_allowports":"allowPorts = [{ start = 12838, end = 12838 }, { start = 36000, end = 36999 }]",
         "busy":false}
        """.trimIndent(),
    )

    @Test
    fun badgeMatchesRealFrpsToml() {
        assertTrue(rhFrpsMatch(realStatus), "真机上 frps.toml 与配置是一致的，徽章必须显示「已生效」")
    }

    @Test
    fun badgeFlagsDriftedFrpsToml() {
        // 只改了 config.json、还没 apply 时就是这个形状（frps 少一段）
        val drifted = obj(
            """{"effective":[[12838,12838],[36000,36999]],
                "frps_allowports":"allowPorts = [{ start = 12838, end = 12838 }]"}""",
        )
        assertFalse(rhFrpsMatch(drifted))
        assertFalse(rhFrpsMatch(obj("""{"effective":[],"frps_allowports":""}""")), "空配置不能误判为一致")
    }

    @Test
    fun payloadKeepsServerNumericShapes() {
        val draft = RhPoolDraft(
            ranges = listOf(RhRangeRow(1, "默认段", "36000", "36999")),
            exclude = "36000, 36005；36010",
            policy = "least_used",
            maxPerClient = "24",
            syncUfw = false,
            protectedPorts = realStatus.jsonObject("pool")!!.let { it["protected"]!!.jsonArray },
        )
        val p = rhPoolPayload(draft)
        assertEquals("least_used", p["alloc_policy"]!!.jsonPrimitive.content)
        // 中文逗号/分号也要切开，且必须落成 JSON 数字而不是字符串
        assertEquals(listOf("36000", "36005", "36010"), p["exclude"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(24, p["max_per_client"]!!.jsonPrimitive.int)
        assertFalse(p["sync_ufw"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(7, p["protected"]!!.jsonArray.size, "保护端口必须原样带回，服务端据此拒绝覆盖")
        assertEquals(36000, p["ranges"]!!.jsonArray[0].jsonObject["start"]!!.jsonPrimitive.int)
    }

    @Test
    fun payloadClampsLikeThePanel() {
        fun of(policy: String, mpc: String) = RhPoolDraft(
            ranges = listOf(RhRangeRow(1, "", "1", "2")), exclude = "", policy = policy,
            maxPerClient = mpc, syncUfw = true, protectedPorts = kotlinx.serialization.json.JsonArray(emptyList()),
        )
        assertEquals("sequential", rhPoolPayload(of("rm -rf /", "16"))["alloc_policy"]!!.jsonPrimitive.content, "策略只认白名单")
        assertEquals(256, rhPoolPayload(of("random", "9999"))["max_per_client"]!!.jsonPrimitive.int)
        assertEquals(1, rhPoolPayload(of("random", "0"))["max_per_client"]!!.jsonPrimitive.int)
        assertEquals(16, rhPoolPayload(of("random", ""))["max_per_client"]!!.jsonPrimitive.int)
        val long = rhPoolPayload(
            RhPoolDraft(
                ranges = listOf(RhRangeRow(1, "标".repeat(40), "1", "2")), exclude = "", policy = "random",
                maxPerClient = "8", syncUfw = true, protectedPorts = kotlinx.serialization.json.JsonArray(emptyList()),
            ),
        )
        assertEquals(32, long["ranges"]!!.jsonArray[0].jsonObject["label"]!!.jsonPrimitive.content.length, "标签截 32")
    }

    @Test
    fun draftErrorsBeforeNetworking() {
        fun d(vararg rows: RhRangeRow) = RhPoolDraft(
            rows.toList(), "", "sequential", "16", true,
            kotlinx.serialization.json.JsonArray(emptyList()),
        )
        assertEquals(1, rhPoolDraftErrs(d()).size, "没有端口段只报这一条")
        assertTrue(rhPoolDraftErrs(d()).any { it.contains("至少需要一个端口段") })
        assertEquals(1, rhPoolDraftErrs(d(RhRangeRow(1, "A", "360", "36"))).size, "起止颠倒必须报错")
        assertEquals(1, rhPoolDraftErrs(d(RhRangeRow(1, "A", "36a", "40"))).size, "非数字必须报错")
        assertTrue(rhPoolDraftErrs(d(RhRangeRow(1, "A", "36000", "36999"))).isEmpty())
    }

    @Test
    fun dirtyDetectionMatchesPanelSig() {
        val base = RhPoolDraft(
            listOf(RhRangeRow(1, "段1", "36000", "36999")), "36005", "sequential", "16", true,
            kotlinx.serialization.json.JsonArray(emptyList()),
        )
        val sig = rhPoolSig(base)
        assertEquals(sig, rhPoolSig(base.copy(ranges = listOf(RhRangeRow(99, "段1", "36000", "36999")))), "行 id 不参与签名")
        assertEquals(sig, rhPoolSig(base.copy(exclude = "36005, ")), "分隔符写法不同但值相同不算改动")
        assertEquals(sig, rhPoolSig(base.copy(maxPerClient = "016")), "补零不算改动")
        assertFalse(sig == rhPoolSig(base.copy(syncUfw = false)))
        assertFalse(sig == rhPoolSig(base.copy(ranges = base.ranges.reversed() + RhRangeRow(2, "x", "1", "2"))))
    }

    /* ---------------- 端口映射 ---------------- */

    private val realMappings = obj(
        """{"allowed":[[12838,12838],[36000,36999]],"ranges_text":"12838, 36000-36999",
           "mappings":{"raspberrypi":[{"name":"ssh","type":"tcp","localIP":"127.0.0.1",
                                       "localPort":22,"remotePort":12838,"note":"SSH"}]},
           "live":{"raspberrypi":[{"name":"ssh","remotePort":12838,"localPort":22,
                                   "localIP":"127.0.0.1","type":"tcp","status":"online"}]},
           "max_per_device":16}""",
    )

    @Test
    fun readsRealMappingEntry() {
        val m = realMappings["mappings"]!!.jsonObject["raspberrypi"]!!.jsonArray[0].jsonObject
        assertEquals("22", rhPortOf(m, "localPort"))
        assertEquals("12838", rhPortOf(m, "remotePort"))
        assertEquals("", rhPortOf(m, "note2").ifEmpty { "" }, "缺字段读空串而不是 \"null\"")
    }

    @Test
    fun payloadDropsBlanksAndRecomputesAutoName() {
        val rows = listOf(
            RhMapRow(1, name = "port-22", localPort = "8080", remotePort = "36001"),
            RhMapRow(2, name = "myssh", localPort = "22", remotePort = "12838"),
            RhMapRow(3, name = "", localPort = "", remotePort = ""),
            RhMapRow(4, name = "", localPort = "36a0", remotePort = "3x7002", type = "udp"),
        )
        val out = rhMapPayload(rows)
        assertEquals(3, out.size, "两个端口都没填的行要丢掉")
        assertEquals("port-8080", out[0]["name"]!!.jsonPrimitive.content, "自动名必须跟随新本地端口重算")
        assertEquals("myssh", out[1]["name"]!!.jsonPrimitive.content, "用户自己起的名字保持不动")
        assertEquals(8080, out[0]["localPort"]!!.jsonPrimitive.int)
        assertEquals("tcp", out[0]["type"]!!.jsonPrimitive.content)
        assertEquals("udp", out[2]["type"]!!.jsonPrimitive.content, "只有 udp 走 UDP，其它一律回落 tcp")
        assertEquals(360, out[2]["localPort"]!!.jsonPrimitive.int, "剔掉非数字后剩下的就是面板送出去的值（36a0 -> 360）")
        assertEquals(37002, out[2]["remotePort"]!!.jsonPrimitive.int)
        assertEquals("127.0.0.1", out[0]["localIP"]!!.jsonPrimitive.content)
    }

    @Test
    fun mapSigIgnoresIdOnlyChanges() {
        val a = listOf(RhMapRow(1, "ssh", "22", "12838"), RhMapRow(2, "web", "80", "36001"))
        val b = listOf(RhMapRow(7, "ssh", "22", "12838"), RhMapRow(8, "web", "80", "36001"))
        assertEquals(rhMapSig(a), rhMapSig(b))
        assertFalse(rhMapSig(a) == rhMapSig(a.map { it.copy(note = "x") }))
    }
}

private fun JsonObject.jsonObject(key: String): JsonObject? = this[key] as? JsonObject
