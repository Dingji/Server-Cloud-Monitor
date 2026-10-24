package com.servermaintance.panel.i18n

/** Read a UTF-8 text file from `commonMain/resources` (classpath / Android assets). */
internal expect fun readBundledText(path: String): String?
