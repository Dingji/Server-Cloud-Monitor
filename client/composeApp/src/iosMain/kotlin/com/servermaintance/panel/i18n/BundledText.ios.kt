package com.servermaintance.panel.i18n

import com.servermaintance.panel.readFileBytes
import platform.Foundation.NSBundle

/**
 * iOS 没有 classloader，18 个语言包由 Xcode 壳以「文件夹引用」的形式
 * （蓝色图标，`lastKnownFileType = folder`）把 `composeApp/src/commonMain/resources/i18n`
 * 整体拷进 .app 包，落点即 `<App>.app/i18n/<tag>.json`。
 *
 * 因此这里只拼 `NSBundle.mainBundle.resourcePath`（= .app 根）+ 传入的相对路径，
 * 再按 UTF-8 解字节 —— 与 Android 侧 `readBytes().decodeToString()` 读同一份文件的方式一致。
 *
 * 若哪天改成逐文件拖入（黄色分组），路径会变成 `<App>.app/<tag>.json`，这里会读不到，
 * 表现是全语言回退成 key 本身（StringRepository 的兜底链），不会崩。
 */
internal actual fun readBundledText(path: String): String? {
    val root = NSBundle.mainBundle.resourcePath ?: return null
    return readFileBytes("$root/$path")?.decodeToString()
}
