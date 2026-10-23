package com.servermaintance.panel.pages.db

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.core.arr
import com.servermaintance.panel.core.str
import com.servermaintance.panel.ui.Chip
import com.servermaintance.panel.ui.DataTable
import com.servermaintance.panel.ui.Level
import com.servermaintance.panel.ui.LevelBadge
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PanelCard
import com.servermaintance.panel.ui.TableCol
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/* 数据库页共享可视化：分节卡、动态列结果表、kv 行、pre。 */

@Composable
fun PreBox(text: String, modifier: Modifier = Modifier, maxHeight: Int = 360) {
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight.dp)
            .background(Palette.inputElevated, RoundedCornerShape(3.dp))
            .border(1.dp, Palette.border, RoundedCornerShape(3.dp))
            .padding(horizontal = 11.dp, vertical = 10.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(text.ifEmpty { "(无输出)" }, style = dbMono, fontSize = 12.sp)
    }
}

@Composable
fun KvEntries(obj: JsonObject?, take: Int = 80) {
    val entries = obj?.entries?.map { it.key to it.value.asText() } ?: emptyList()
    if (entries.isEmpty()) {
        Text("暂无信息", style = dbMuted)
        return
    }
    Column {
        entries.take(take).forEach { (k, v) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(k, style = dbMuted, modifier = Modifier.width(160.dp), maxLines = 1)
                Text(v.take(80), style = dbMono, modifier = Modifier.weight(1f), maxLines = 2)
            }
        }
    }
}

@Composable
fun DynamicTable(
    columns: List<String>,
    rows: List<JsonObject>,
    maxHeight: Int = 380,
    extra: TableCol<JsonObject>? = null,
    empty: String = "暂无数据",
) {
    val cols = buildList {
        columns.forEach { c ->
            add(
                TableCol<JsonObject>(c, weight = 1f, minWidth = 72.dp, mono = true) { row ->
                    val t = row.cell(c)
                    Text(
                        if (t == "NULL") "NULL" else t.take(80),
                        style = if (t == "NULL") dbMuted else dbMono,
                        fontSize = 11.5.sp,
                        maxLines = 2,
                    )
                },
            )
        }
        if (extra != null) add(extra)
    }
    if (cols.isEmpty()) {
        Text(empty, style = dbMuted)
        return
    }
    Box(Modifier.fillMaxWidth().heightIn(max = maxHeight.dp)) {
        DataTable(
            columns = cols,
            rows = rows,
            rowKey = { it.hashCode() },
            emptyText = empty,
        )
    }
}

@Composable
fun SectionsCards(sections: List<JsonElement>, cardPrefix: String = "sec") {
    if (sections.isEmpty()) {
        PanelCard(title = "分节", cardKey = "$cardPrefix-empty", collapsible = false) {
            Text("无数据", style = dbMuted)
        }
        return
    }
    sections.filterIsInstance<JsonObject>().forEachIndexed { i, s ->
        val title = s.str("title").ifEmpty { "分节 ${i + 1}" }
        val rows = s.arr("rows").filterIsInstance<JsonObject>()
        val cols = s.arr("columns").map { (it as? JsonPrimitive)?.content.orEmpty() }.filter { it.isNotEmpty() }
        val err = s.str("error")
        val note = s.str("note")
        PanelCard(
            title = title,
            cardKey = "$cardPrefix-$i-$title",
            right = {
                if (rows.isNotEmpty()) Chip("${rows.size} 行")
                if (note.isNotEmpty()) Text(note, style = dbMuted, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp))
            },
        ) {
            when {
                err.isNotEmpty() -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LevelBadge("不可用", Level.Muted)
                    Text(err, style = dbMuted, fontSize = 12.sp)
                }
                rows.isEmpty() -> Text("无数据", style = dbMuted)
                else -> DynamicTable(cols.ifEmpty { rows.first().keys.toList() }, rows, maxHeight = 280)
            }
        }
        Spacer(Modifier.size(4.dp))
    }
}

@Composable
fun ConsoleResult(r: JsonObject?) {
    if (r == null) return
    val cols = r.arr("columns").map { (it as? JsonPrimitive)?.content.orEmpty() }.filter { it.isNotEmpty() }
    val rows = r.arr("rows").filterIsInstance<JsonObject>()
    val out = r.str("output")
    val meta = buildList {
        val ms = r.str("durationMs")
        if (ms.isNotEmpty()) add("耗时 ${ms}ms")
        if (cols.isNotEmpty()) add("${rows.size} 行")
        if (r.str("dryRun").isNotEmpty() && r.str("dryRun") != "false") add("已回滚")
    }.joinToString(" · ")
    if (meta.isNotEmpty()) Text(meta, style = dbMuted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp))
    if (cols.isNotEmpty()) DynamicTable(cols, rows, maxHeight = 420)
    else PreBox(out)
}
