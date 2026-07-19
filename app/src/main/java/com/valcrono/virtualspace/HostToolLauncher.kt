package com.valcrono.virtualspace

/** Host-only routing marker for viewers/tools that must never reserve VAPP slots. */
class HostToolLauncher {
    fun open(tool: HostTool, path: String = "/"): HostToolLaunchResult {
        logLaunch("HOST_TOOL_OPENED", null, null, null, null, null, tool.name, path)
        return HostToolLaunchResult(tool, path, true)
    }
}

enum class HostTool { Browser, FileBrowser, SQLiteViewer, TextViewer, HexViewer, ImageViewer, ApkInspector, ImportExport, Diagnostics, Settings }
data class HostToolLaunchResult(val tool: HostTool, val path: String, val openedInHost: Boolean)
