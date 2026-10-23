package com.servermaintance.panel.window

import androidx.compose.ui.awt.ComposeWindow
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.BaseTSD.LONG_PTR
import com.sun.jna.platform.win32.WinDef.HMENU
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.LRESULT
import com.sun.jna.platform.win32.WinDef.RECT
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.platform.win32.WinUser.WindowProc
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.awt.Frame
import java.awt.Window

internal val isWindowsDesktop: Boolean =
    System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

private const val WM_NCCALCSIZE = 0x0083
private const val WM_SYSCOMMAND = 0x0112
private const val WM_NCLBUTTONDOWN = 0x00A1
private const val GWLP_WNDPROC = -4

private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
private const val DWMWA_WINDOW_CORNER_PREFERENCE = 33
private const val DWMWA_BORDER_COLOR = 34
private const val DWMWCP_DONOTROUND = 1
private const val DWMWCP_ROUND = 2

private const val MF_ENABLED = 0x0000
private const val MF_GRAYED = 0x0001
private const val MF_BYCOMMAND = 0x0000
private const val TPM_RETURNCMD = 0x0100
private const val TPM_RIGHTBUTTON = 0x0002
private const val SC_RESTORE = 0xF120
private const val SC_MOVE = 0xF010
private const val SC_SIZE = 0xF000
private const val SC_MINIMIZE = 0xF020
private const val SC_MAXIMIZE = 0xF030
private const val SC_CLOSE = 0xF060

internal const val HT_LEFT = 10
internal const val HT_RIGHT = 11
internal const val HT_TOP = 12
internal const val HT_TOPLEFT = 13
internal const val HT_TOPRIGHT = 14
internal const val HT_BOTTOM = 15
internal const val HT_BOTTOMLEFT = 16
internal const val HT_BOTTOMRIGHT = 17

/** Palette.border as COLORREF (0x00BBGGRR). */
private const val BORDER_DARK_COLORREF = 0x0033281F
private const val BORDER_LIGHT_COLORREF = 0x00F0E8E2

@Suppress("FunctionName")
private interface User32Ex : StdCallLibrary {
    fun SetWindowLongPtr(hWnd: HWND, nIndex: Int, wndProc: WindowProc): LONG_PTR
    fun SetWindowLongPtr(hWnd: HWND, nIndex: Int, wndProc: LONG_PTR): LONG_PTR
    fun SetWindowLong(hWnd: HWND, nIndex: Int, dwNewLong: Int): Int
    fun GetWindowLong(hWnd: HWND, nIndex: Int): Int
    fun CallWindowProc(lpPrevWndFunc: LONG_PTR, hWnd: HWND, uMsg: Int, wParam: WPARAM, lParam: LPARAM): LRESULT
    fun SendMessage(hWnd: HWND, msg: Int, wParam: WPARAM, lParam: LPARAM): LRESULT
    fun PostMessage(hWnd: HWND, msg: Int, wParam: WPARAM, lParam: LPARAM): Boolean
    fun ReleaseCapture(): Boolean
    fun IsZoomed(hWnd: HWND): Boolean
    fun CloseWindow(hWnd: HWND): Boolean
    fun ShowWindow(hWnd: HWND, nCmdShow: Int): Boolean
    fun GetSystemMenu(hWnd: HWND, bRevert: Boolean): HMENU?
    fun EnableMenuItem(hMenu: HMENU, uIDEnableItem: Int, uEnable: Int): Boolean
    fun TrackPopupMenu(hMenu: HMENU, uFlags: Int, x: Int, y: Int, nReserved: Int, hWnd: HWND, prcRect: RECT?): Int
    fun SetWindowPos(hWnd: HWND, hWndInsertAfter: HWND?, x: Int, y: Int, cx: Int, cy: Int, uFlags: Int): Boolean
    fun MonitorFromWindow(hWnd: HWND, dwFlags: Int): HWND?
    fun GetMonitorInfoA(hMonitor: HWND, lpmi: WinUser.MONITORINFO): Boolean

    companion object {
        val INSTANCE: User32Ex? =
            runCatching { Native.load("user32", User32Ex::class.java, W32APIOptions.DEFAULT_OPTIONS) }.getOrNull()
    }
}

@Suppress("FunctionName")
private interface DwmApi : StdCallLibrary {
    fun DwmSetWindowAttribute(hwnd: HWND, attr: Int, value: Pointer, size: Int): Int
    fun DwmExtendFrameIntoClientArea(hwnd: HWND, margins: DwmMargins): Int

    companion object {
        val INSTANCE: DwmApi? =
            runCatching { Native.load("dwmapi", DwmApi::class.java, W32APIOptions.DEFAULT_OPTIONS) }.getOrNull()
    }
}

@Structure.FieldOrder("cxLeftWidth", "cxRightWidth", "cyTopHeight", "cyBottomHeight")
internal class DwmMargins(
    @JvmField var cxLeftWidth: Int = 0,
    @JvmField var cxRightWidth: Int = 0,
    @JvmField var cyTopHeight: Int = 0,
    @JvmField var cyBottomHeight: Int = 0,
) : Structure(), Structure.ByReference

@Structure.FieldOrder("rgrc0", "rgrc1", "rgrc2", "lppos")
private class NcCalcSizeParams(ptr: Pointer) : Structure(ptr) {
    @JvmField var rgrc0: RECT = RECT()
    @JvmField var rgrc1: RECT = RECT()
    @JvmField var rgrc2: RECT = RECT()
    @JvmField var lppos: Pointer? = null

    init {
        read()
    }
}

internal class Win32Chrome private constructor(
    private val hwnd: HWND,
    private val user32: User32Ex,
) : WindowProc {
    private val previousProc: LONG_PTR = user32.SetWindowLongPtr(hwnd, GWLP_WNDPROC, this)
    private var attached = true

    init {
        // WS_CAPTION paints the native title bar on top of our own. Clear it and keep a
        // thick frame so DWM can still shadow and snap the undecorated window.
        val style = user32.GetWindowLong(hwnd, WinUser.GWL_STYLE)
        user32.SetWindowLong(
            hwnd,
            WinUser.GWL_STYLE,
            (style and WinUser.WS_CAPTION.inv()) or WinUser.WS_THICKFRAME or
                WinUser.WS_MINIMIZEBOX or WinUser.WS_MAXIMIZEBOX or WinUser.WS_SYSMENU,
        )
        enableShadow()
        applyImmersiveChrome(rounded = true, darkTitleBar = true)
        user32.SetWindowPos(
            hwnd,
            null,
            0, 0, 0, 0,
            WinUser.SWP_NOMOVE or WinUser.SWP_NOSIZE or WinUser.SWP_NOZORDER or WinUser.SWP_FRAMECHANGED,
        )
    }

    override fun callback(hWnd: HWND, uMsg: Int, wParam: WPARAM, lParam: LPARAM): LRESULT {
        return when (uMsg) {
            WM_NCCALCSIZE -> {
                if (wParam.toLong() != 0L) {
                    if (user32.IsZoomed(hWnd)) {
                        insetMaximizedToWorkArea(hWnd, lParam)
                    }
                    LRESULT(0)
                } else {
                    user32.CallWindowProc(previousProc, hWnd, uMsg, wParam, lParam)
                }
            }
            WinUser.WM_DESTROY -> {
                detach()
                user32.CallWindowProc(previousProc, hWnd, uMsg, wParam, lParam)
            }
            else -> user32.CallWindowProc(previousProc, hWnd, uMsg, wParam, lParam)
        }
    }

    fun setRounded(rounded: Boolean) {
        applyImmersiveChrome(rounded, lastDarkTitleBar, lastBorderColorRef)
    }

    fun setColorTheme(dark: Boolean) {
        applyImmersiveChrome(lastRounded, dark, if (dark) BORDER_DARK_COLORREF else BORDER_LIGHT_COLORREF)
    }

    private var lastRounded = true
    private var lastDarkTitleBar = true
    private var lastBorderColorRef = BORDER_DARK_COLORREF

    fun detach() {
        if (!attached) return
        attached = false
        runCatching { user32.SetWindowLongPtr(hwnd, GWLP_WNDPROC, previousProc) }
    }

    private fun enableShadow() {
        val margins = DwmMargins(-1, -1, -1, -1)
        DwmApi.INSTANCE?.DwmExtendFrameIntoClientArea(hwnd, margins)
            ?: runCatching {
                NativeLibrary.getInstance("dwmapi")
                    .getFunction("DwmExtendFrameIntoClientArea")
                    .invoke(arrayOf(hwnd, margins))
            }
    }

    private fun applyImmersiveChrome(
        rounded: Boolean,
        darkTitleBar: Boolean,
        borderColorRef: Int = if (darkTitleBar) BORDER_DARK_COLORREF else BORDER_LIGHT_COLORREF,
    ) {
        lastRounded = rounded
        lastDarkTitleBar = darkTitleBar
        lastBorderColorRef = borderColorRef
        val api = DwmApi.INSTANCE ?: return
        dwmSetInt(api, hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, if (darkTitleBar) 1 else 0)
        dwmSetInt(api, hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, if (rounded) DWMWCP_ROUND else DWMWCP_DONOTROUND)
        dwmSetInt(api, hwnd, DWMWA_BORDER_COLOR, borderColorRef)
    }

    private fun insetMaximizedToWorkArea(hWnd: HWND, lParam: LPARAM) {
        val monitor = user32.MonitorFromWindow(hWnd, WinUser.MONITOR_DEFAULTTONEAREST) ?: return
        val info = WinUser.MONITORINFO()
        info.cbSize = info.size()
        info.write()
        if (!user32.GetMonitorInfoA(monitor, info)) return
        info.read()
        val params = NcCalcSizeParams(Pointer(lParam.toLong()))
        params.rgrc0.left = info.rcWork.left
        params.rgrc0.top = info.rcWork.top
        params.rgrc0.right = info.rcWork.right
        params.rgrc0.bottom = info.rcWork.bottom
        params.write()
    }

    companion object {
        fun attach(window: Window): Win32Chrome? {
            if (!isWindowsDesktop) return null
            val hwnd = hwndOf(window) ?: return null
            val user32 = User32Ex.INSTANCE ?: return null
            return runCatching { Win32Chrome(hwnd, user32) }.getOrNull()
        }
    }
}

internal fun hwndOf(window: Window): HWND? = runCatching {
    val handle = (window as? ComposeWindow)?.windowHandle ?: 0L
    if (handle != 0L) HWND(Pointer.createConstant(handle))
    else {
        if (!window.isDisplayable) return@runCatching null
        val ptr = Native.getComponentPointer(window) ?: return@runCatching null
        HWND(ptr)
    }
}.getOrNull()

internal fun nativeResize(window: Window, hit: Int) {
    val hwnd = hwndOf(window) ?: return
    val user32 = User32Ex.INSTANCE ?: return
    user32.ReleaseCapture()
    user32.SendMessage(hwnd, WM_NCLBUTTONDOWN, WPARAM(hit.toLong()), LPARAM(0))
}

internal fun nativeMinimize(window: Window) {
    val hwnd = hwndOf(window) ?: return
    User32Ex.INSTANCE?.CloseWindow(hwnd)
}

internal fun nativeMaximize(window: Window) {
    val hwnd = hwndOf(window) ?: return
    User32Ex.INSTANCE?.ShowWindow(hwnd, WinUser.SW_MAXIMIZE)
}

internal fun nativeRestore(window: Window) {
    val hwnd = hwndOf(window) ?: return
    User32Ex.INSTANCE?.ShowWindow(hwnd, WinUser.SW_RESTORE)
}

internal fun nativeIsMaximized(window: Window): Boolean {
    val hwnd = hwndOf(window)
    if (hwnd != null) return User32Ex.INSTANCE?.IsZoomed(hwnd) == true
    return (window as? Frame)?.extendedState?.and(Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH
}

internal fun nativeShowSystemMenu(window: Window, screenX: Int, screenY: Int) {
    val hwnd = hwndOf(window) ?: return
    val user32 = User32Ex.INSTANCE ?: return
    val menu = user32.GetSystemMenu(hwnd, false) ?: return
    val maximized = user32.IsZoomed(hwnd)
    fun enable(cmd: Int, on: Boolean) {
        user32.EnableMenuItem(menu, cmd, MF_BYCOMMAND or if (on) MF_ENABLED else MF_GRAYED)
    }
    enable(SC_RESTORE, maximized)
    enable(SC_MOVE, !maximized)
    enable(SC_SIZE, !maximized)
    enable(SC_MINIMIZE, true)
    enable(SC_MAXIMIZE, !maximized)
    enable(SC_CLOSE, true)
    val cmd = user32.TrackPopupMenu(
        menu,
        TPM_RETURNCMD or TPM_RIGHTBUTTON,
        screenX,
        screenY,
        0,
        hwnd,
        null,
    )
    if (cmd != 0) {
        user32.PostMessage(hwnd, WM_SYSCOMMAND, WPARAM(cmd.toLong()), LPARAM(0))
    }
}

private fun dwmSetInt(api: DwmApi, hwnd: HWND, attr: Int, value: Int) {
    val ref = IntByReference(value)
    api.DwmSetWindowAttribute(hwnd, attr, ref.pointer, Integer.BYTES)
}
