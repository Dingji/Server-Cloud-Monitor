package com.servermaintance.panel.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.core.int
import com.servermaintance.panel.core.str
import com.servermaintance.panel.ui.AccentState
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PanelIcon
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** 一棵多级归类树（笔记归类 / 待办清单共用）。 */
internal data class NoteFolder(
    val id: Int,
    val name: String,
    val parentId: Int?,
    val color: String,
    val count: Int,
    val totalCount: Int,
    val position: Int,
)

internal fun List<JsonElement>.toFolders(): List<NoteFolder> =
    filterIsInstance<JsonObject>().map { o ->
        NoteFolder(
            id = o.int("id"),
            name = o.str("name"),
            parentId = o.int("parent_id").takeIf { it > 0 },
            color = o.str("color"),
            count = o.int("count"),
            totalCount = o.int("total_count"),
            position = o.int("position"),
        )
    }

internal val NB_NOTE_COLORS = listOf("", "#4f46e5", "#8b5cf6", "#0ea5e9", "#22c55e", "#f59e0b", "#ef4444")
internal val folderColorNames = listOf("默认", "靛蓝", "紫罗兰", "天蓝", "翠绿", "琥珀", "红色")

internal fun colorNameOf(hex: String): String =
    folderColorNames[NB_NOTE_COLORS.indexOf(hex).coerceAtLeast(0)]

internal fun noteColorOf(hex: String): Color = runCatching {
    if (hex.length == 7 && hex.startsWith("#")) Color(("#FF" + hex.substring(1)).toLong(16))
    else Color.Transparent
}.getOrDefault(Color.Transparent)

/** 深度优先的「上级归类」选项，value 为 id，null=顶层。 */
internal fun folderParentOptions(folders: List<NoteFolder>): List<Pair<Int?, String>> {
    val kids = folders.groupBy { it.parentId }
    val out = mutableListOf<Pair<Int?, String>>(null to "（顶层）")
    fun walk(parent: Int?, depth: Int) {
        kids[parent].orEmpty().sortedWith(compareBy({ it.position }, { it.name })).forEach { f ->
            out += f.id to ("　".repeat(depth) + f.name)
            walk(f.id, depth + 1)
        }
    }
    walk(null, 0)
    return out
}

internal fun folderPathName(folders: List<NoteFolder>, id: Int?): String {
    if (id == null) return "未归类"
    val byId = folders.associateBy { it.id }
    val chain = mutableListOf<String>()
    var cur = byId[id]
    var guard = 0
    while (cur != null && guard++ < 16) {
        chain += cur.name
        cur = cur.parentId?.let { byId[it] }
    }
    return if (chain.isEmpty()) "未归类" else chain.asReversed().joinToString(" / ")
}

internal fun folderChildren(folders: List<NoteFolder>, parent: Int?): List<NoteFolder> =
    folders.filter { it.parentId == parent }.sortedWith(compareBy({ it.position }, { it.name }))

/** 深度优先的扁平顺序（下拉选择、移动目标用）。 */
internal fun folderFlat(folders: List<NoteFolder>): List<NoteFolder> {
    val out = mutableListOf<NoteFolder>()
    fun walk(parent: Int?) {
        folderChildren(folders, parent).forEach { f ->
            out += f
            walk(f.id)
        }
    }
    walk(null)
    return out
}

internal fun folderSubtreeIds(folders: List<NoteFolder>, id: Int): Set<Int> {
    val out = mutableSetOf<Int>()
    fun walk(cur: Int) {
        if (!out.add(cur)) return
        folderChildren(folders, cur).forEach { walk(it.id) }
    }
    walk(id)
    return out
}

@Composable
internal fun NoteFolderRail(
    folders: List<NoteFolder>,
    selected: String,
    onSelect: (String) -> Unit,
    onCreate: (Int?) -> Unit,
    onEdit: (NoteFolder) -> Unit,
    onDelete: (NoteFolder) -> Unit,
    modifier: Modifier = Modifier,
) {
    val expanded = remember { mutableStateMapOf<Int, Boolean>() }
    LaunchedEffect(folders.map { it.id }) {
        folders.forEach { expanded.putIfAbsent(it.id, true) }
    }
    LaunchedEffect(selected) {
        var cur = selected.removePrefix("f:").toIntOrNull()
        val byId = folders.associateBy { it.id }
        var guard = 0
        while (cur != null && guard++ < 16) {
            expanded[cur] = true
            cur = byId[cur]?.parentId
        }
    }

    Column(
        modifier.fillMaxHeight()
            .clip(RoundedCornerShape(nbCardRadius(12.dp)))
            .background(Palette.tableSurface)
            .border(1.dp, Palette.border, RoundedCornerShape(nbCardRadius(12.dp))),
    ) {
        Row(
            Modifier.fillMaxWidth().background(Palette.tableHeader).padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "归类",
                color = Palette.muted, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold, letterSpacing = 0.7.sp,
            )
            Spacer(Modifier.weight(1f))
            AddSquareButton(hint = "新建顶层归类") { onCreate(null) }
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(6.dp)) {
            RailRow(
                icon = "note", label = "全部笔记", selected = selected == "all", depth = 0,
                count = null,
            ) { onSelect("all") }
            RailRow(
                icon = "folder", label = "未归类", selected = selected == "unfiled", depth = 0,
                count = null,
            ) { onSelect("unfiled") }
            RailRow(
                icon = "users", label = "共享给我", selected = selected == "shared", depth = 0,
                count = null,
            ) { onSelect("shared") }
            RailRow(
                icon = "trash", label = "回收站", selected = selected == "trash", depth = 0,
                count = null, danger = true,
            ) { onSelect("trash") }

            if (folders.isNotEmpty()) {
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp)
                        .height(1.dp).background(Palette.border),
                )
            }
            folderChildren(folders, null).forEach { f ->
                FolderNode(
                    folder = f, folders = folders, depth = 0, selected = selected,
                    expanded = expanded, onSelect = onSelect, onCreate = onCreate,
                    onEdit = onEdit, onDelete = onDelete,
                )
            }
            if (folders.isEmpty()) {
                Text(
                    "还没有归类\n点右上角 + 创建",
                    color = Palette.muted, fontSize = 11.sp, lineHeight = 17.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun FolderNode(
    folder: NoteFolder,
    folders: List<NoteFolder>,
    depth: Int,
    selected: String,
    expanded: MutableMap<Int, Boolean>,
    onSelect: (String) -> Unit,
    onCreate: (Int?) -> Unit,
    onEdit: (NoteFolder) -> Unit,
    onDelete: (NoteFolder) -> Unit,
) {
    val kids = folderChildren(folders, folder.id)
    val open = expanded[folder.id] != false
    val accent = noteColorOf(folder.color).takeIf { it != Color.Transparent } ?: AccentState.current.primary
    Box(Modifier.fillMaxWidth().padding(start = (depth * 10).dp)) {
        RailRow(
            icon = "folder", iconTint = accent, label = folder.name,
            selected = selected == "f:${folder.id}", depth = 0,
            count = folder.totalCount.takeIf { it > 0 },
            expandable = kids.isNotEmpty(), expanded = open,
            onToggleExpand = { expanded[folder.id] = !open },
            onAddChild = { onCreate(folder.id) },
            onEdit = { onEdit(folder) },
            onDelete = { onDelete(folder) },
        ) { onSelect("f:${folder.id}") }
    }
    AnimatedVisibility(
        visible = open && kids.isNotEmpty(),
        enter = expandVertically(nbSpringSoft(stiffness = 300f)) + fadeIn(androidx.compose.animation.core.tween(160)),
        exit = shrinkVertically(androidx.compose.animation.core.tween(170)) + fadeOut(androidx.compose.animation.core.tween(120)),
    ) {
        Column {
            kids.forEach { child ->
                FolderNode(
                    folder = child, folders = folders, depth = depth + 1, selected = selected,
                    expanded = expanded, onSelect = onSelect, onCreate = onCreate,
                    onEdit = onEdit, onDelete = onDelete,
                )
            }
        }
    }
}

@Composable
private fun RailRow(
    icon: String,
    label: String,
    selected: Boolean,
    depth: Int,
    count: Int?,
    iconTint: Color = Palette.muted,
    danger: Boolean = false,
    expandable: Boolean = false,
    expanded: Boolean = true,
    onToggleExpand: (() -> Unit)? = null,
    onAddChild: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(
        when {
            selected -> AccentState.current.primary.copy(alpha = 0.13f)
            hovered -> Palette.panel2
            else -> Color.Transparent
        },
        androidx.compose.animation.core.tween(150), label = "railBg",
    )
    val tint by animateColorAsState(
        when {
            danger -> Palette.bad
            selected -> AccentState.current.secondary
            else -> Palette.muted
        },
        androidx.compose.animation.core.tween(150), label = "railTint",
    )
    val chevron by animateFloatAsState(if (expanded) 0f else -90f, nbSpringSoft(500f), label = "chevron")
    Row(
        Modifier.fillMaxWidth().height(29.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(bg)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (expandable) {
            Box(
                Modifier.size(15.dp).clip(RoundedCornerShape(4.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onToggleExpand?.invoke() },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.graphicsLayer { rotationZ = chevron }) {
                    PanelIcon("chevronDown", 12.dp, Palette.muted)
                }
            }
        } else {
            Spacer(Modifier.size(15.dp))
        }
        PanelIcon(icon, 13.dp, iconTint)
        Spacer(Modifier.size(6.dp))
        Text(
            label,
            color = if (selected) AccentState.current.secondary else if (danger) Palette.bad else Palette.text,
            fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (hovered && (onAddChild != null || onEdit != null || onDelete != null)) {
            Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                if (onAddChild != null) TinyIcon("plus", "新建子归类", onAddChild)
                if (onEdit != null) TinyIcon("edit", "重命名归类", onEdit)
                if (onDelete != null) TinyIcon("trash", "删除归类", onDelete, danger = true)
            }
        } else if (count != null) {
            Text(
                count.toString(),
                color = Palette.muted, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .widthIn(min = 18.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Palette.inset)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun TinyIcon(icon: String, hint: String, onClick: () -> Unit, danger: Boolean = false) {
    Box(
        Modifier.size(17.dp).clip(RoundedCornerShape(4.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        PanelIcon(icon, 11.dp, if (danger) Palette.bad else Palette.muted)
    }
}

@Composable
private fun AddSquareButton(hint: String, onClick: () -> Unit) {
    Box(
        Modifier.size(19.dp).clip(RoundedCornerShape(4.dp))
            .border(1.dp, Palette.border2, RoundedCornerShape(4.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        PanelIcon("plus", 11.dp, Palette.muted)
    }
}
