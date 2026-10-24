package com.servermaintance.panel.core
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.tr

import com.servermaintance.panel.ui.Modal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 复刻 `window.runAction`（core.js）：
 *  - POST [path]，成功时 toast [successToast]（非空才弹）
 *  - [title] 非空且响应带 `output` → 弹 [Modal.output] 展示
 *  - 失败：toast 错误（6s），若异常带 `output` → 弹 [Modal.output]（失败徽章）
 *  - 失败默认返回 null 不抛；[throwOnError] 为 true 时重新抛出
 *  - [reload] 成功后延迟 700ms 执行（对应 web 的 setTimeout(reload, 700)）
 */
object Actions {

    suspend fun run(
        path: String,
        body: JsonObject = jsonObjectOf(),
        successToast: String? = null,
        title: String? = null,
        throwOnError: Boolean = false,
        reload: (suspend () -> Unit)? = null,
    ): JsonElement? = try {
        val r = AppState.api.post(path, body)
        if (successToast != null) AppState.toast(successToast, ToastKind.Ok)
        val out = r.str("output")
        if (title != null && out.isNotEmpty()) Modal.output(title, out)
        if (reload != null) {
            delay(700)
            reload()
        }
        r
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        AppState.toast(e.message ?: tr("操作失败_09e424b5e8"), ToastKind.Err, ms = 6000)
        if (!e.output.isNullOrBlank()) {
            Modal.output(title ?: tr("执行失败_9746cfc7d2"), e.output, isError = true)
        }
        if (throwOnError) throw e
        null
    } catch (e: Exception) {
        AppState.toast(
            trf("0_1_4d3c418a83", tr("操作失败_09e424b5e8"), e.message ?: tr("未知错误_5f76edc5de")),
            ToastKind.Err,
            ms = 6000,
        )
        if (throwOnError) throw e
        null
    }
}
