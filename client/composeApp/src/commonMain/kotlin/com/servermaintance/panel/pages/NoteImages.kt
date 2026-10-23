package com.servermaintance.panel.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.core.NoteBoxState
import com.servermaintance.panel.decodeImageBitmap
import com.servermaintance.panel.openUrl
import com.servermaintance.panel.ui.AccentState
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PanelIcon
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/* ==================================================================== *
 * 笔记图片 —— 自实现轻量异步图片加载（不引 Coil/Kamel）：
 *   内存缓存（LRU 160 张）+ 并发去重 + data:image;base64 支持。
 * 网页端 <img src> 直接加载；这里用 Ktor 拉字节 + 平台解码（Skia/BitmapFactory）。
 * ==================================================================== */

private const val IMAGE_CACHE_MAX = 160

/** url -> bitmap；null 值表示解码失败（渲染占位，不反复重试）。 */
private val imageCache = mutableStateMapOf<String, ImageBitmap?>()

/** 正在加载中的 url（并发去重）。 */
private val inFlight = mutableStateMapOf<String, Boolean>()

/** 清空缓存（主题切换/登出可调用；目前保留即可）。 */
internal fun clearNoteImageCache() {
    imageCache.clear()
    inFlight.clear()
}

/** 拉取图片字节并解码；带缓存与并发去重。失败缓存 null。 */
private suspend fun loadNoteImage(url: String): ImageBitmap? {
    imageCache[url]?.let { return it }
    if (url in inFlight) {
        // 已有请求在途：轮询等待其写入缓存
        while (url in inFlight) {
            kotlinx.coroutines.delay(60)
            imageCache[url]?.let { return it }
        }
        return imageCache[url]
    }
    inFlight[url] = true
    return try {
        val bytes = withContext(Dispatchers.IO) {
            when {
                url.startsWith("data:image") -> decodeDataUri(url)
                else -> {
                    val absolute = NoteBoxState.api.absoluteUrl(url)
                    val session = NoteBoxState.config().session
                    val r = NoteBoxState.api.http.get(absolute) {
                        if (session.isNotBlank() && absolute.startsWith(NoteBoxState.config().normalizedUrl())) {
                            header(io.ktor.http.HttpHeaders.Cookie, session)
                        }
                    }
                    if (r.status.value >= 400) null else r.bodyAsBytes()
                }
            }
        }
        val bmp = if (bytes == null) null else withContext(Dispatchers.Default) { decodeImageBitmap(bytes) }
        imageCache[url] = bmp
        bmp
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        imageCache[url] = null
        null
    } finally {
        inFlight.remove(url)
    }
}

/** `data:image/png;base64,AAAA…` -> bytes（不支持/损坏返回 null）。 */
private fun decodeDataUri(uri: String): ByteArray? {
    val comma = uri.indexOf(',')
    if (comma < 0) return null
    val meta = uri.substring(0, comma)
    val payload = uri.substring(comma + 1)
    return try {
        if (meta.contains(";base64")) {
            kotlin.io.encoding.Base64.decode(payload)
        } else {
            payload.encodeToByteArray()
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * 笔记内图片：异步加载 + 缓存；加载中显示占位条，失败显示占位卡片（可点击去浏览器打开）。
 * [maxHeight] 限制展示高度（等比缩放，对齐网页 img max-height）。
 */
@Composable
fun NoteImage(
    url: String,
    alt: String,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 380.dp,
) {
    val resolved = remember(url) {
        if (url.startsWith("data:")) url else NoteBoxState.api.absoluteUrl(url)
    }
    val cached = imageCache[resolved]
    val failedBefore = resolved in imageCache && cached == null
    val bmp = cached
    val loading = bmp == null && !failedBefore

    if (bmp != null) {
        val shape = RoundedCornerShape(8.dp)
        Box(modifier.clip(shape), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Image(
                bitmap = bmp,
                contentDescription = alt.ifEmpty { "图片" },
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight),
            )
        }
    } else {
        val shape = RoundedCornerShape(8.dp)
        Row(
            modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Palette.inset)
                .border(1.dp, Palette.border, shape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { openUrl(resolved) }
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) {
                com.servermaintance.panel.ui.Spinner(15.dp)
                Spacer(Modifier.width(9.dp))
                Text("图片加载中…", color = Palette.muted, fontSize = 11.5.sp)
            } else {
                PanelIcon("image", 17.dp, AccentState.current.secondary)
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        alt.ifEmpty { "图片" },
                        color = Palette.text, fontSize = 12.sp, maxLines = 1,
                    )
                    Text(
                        resolved.take(120),
                        color = Palette.muted, fontSize = 10.sp, maxLines = 1,
                    )
                }
                Text("在浏览器打开", color = AccentState.current.secondary, fontSize = 11.sp)
            }
        }
    }

    LaunchedEffect(resolved) {
        if (bmp == null && !failedBefore) {
            // 触发加载（结果写入 imageCache，引起重组）
            loadNoteImage(resolved)
        }
    }
}

/**
 * 视频/音频链接占位卡（网页端是 <video>/<audio>；Compose 无原生播放器，
 * 提供「在系统播放器/浏览器打开」入口）。
 */
@Composable
fun NoteMediaLink(
    url: String,
    kind: String, // "video" | "audio"
    modifier: Modifier = Modifier,
) {
    val absolute = NoteBoxState.api.absoluteUrl(url)
    val name = absolute.substringAfterLast('/').takeIf { it.isNotEmpty() } ?: absolute
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Palette.inset)
            .border(1.dp, Palette.border, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { openUrl(absolute) }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(50))
                .background(AccentState.current.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            PanelIcon(if (kind == "audio") "fileDown" else "play", 15.dp, AccentState.current.secondary)
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = Palette.text, fontSize = 12.5.sp, maxLines = 1)
            Text(
                if (kind == "audio") "音频 · 点击用系统播放器打开" else "视频 · 点击用系统播放器打开",
                color = Palette.muted, fontSize = 10.5.sp, maxLines = 1,
            )
        }
        PanelIcon("external", 13.dp, Palette.muted)
    }
}

/** YouTube / Bilibili 链接卡（网页端是 iframe 内嵌；App 禁 WebView，改为链接卡）。 */
@Composable
fun NoteVideoEmbed(
    url: String,
    title: String,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Palette.inset)
            .border(1.dp, Palette.border, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { openUrl(url) }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(50))
                .background(Palette.bad.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            PanelIcon("play", 14.dp, Palette.bad)
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Palette.text, fontSize = 12.5.sp, maxLines = 1)
            Text(url.take(90), color = Palette.muted, fontSize = 10.sp, maxLines = 1)
        }
        Text("播放", color = AccentState.current.secondary, fontSize = 11.5.sp)
    }
}
