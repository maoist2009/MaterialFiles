/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.topjohnwu.superuser.Shell
import me.zhanghai.android.files.app.packageManager
import me.zhanghai.android.files.provider.root.LibSuFileServiceLauncher
import me.zhanghai.android.files.provider.root.RootStrategy
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.util.valueCompat

object Terminal {
    private const val TERMUX_PACKAGE_NAME = "com.termux"
    private const val TERMUX_RUN_COMMAND_SERVICE_CLASS_NAME = "com.termux.app.RunCommandService"
    private const val TERMUX_RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
    private const val TERMUX_RUN_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val TERMUX_RUN_COMMAND_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    private const val TERMUX_RUN_COMMAND_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"

    fun open(path: String, context: Context) {
        if (openInTermux(path, context)) {
            return
        }
        val componentName =
            packageManager.queryIntentActivities(Intent(Intent.ACTION_SEND).setType("*/*"), 0)
                .firstOrNull { it.activityInfo.name.endsWith(".TermHere") }?.activityInfo
                ?.let { ComponentName(it.packageName, it.name) }
                ?: ComponentName("jackpal.androidterm", "jackpal.androidterm.TermHere")
        val intent = Intent()
            .setComponent(componentName)
            .setAction(Intent.ACTION_SEND)
            .putExtra(Intent.EXTRA_STREAM, Uri.parse(path))
        context.startActivitySafe(intent)
    }

    private fun openInTermux(path: String, context: Context): Boolean {
        // Termux itself cannot chdir(2) into root-only directories, which would make the session
        // fall back to $HOME. But don't take over Termux's own directories, where a root shell
        // would leave root-owned files behind.
        val rooted = Settings.ROOT_STRATEGY.valueCompat != RootStrategy.NEVER &&
            LibSuFileServiceLauncher.isSuAvailable() &&
            !path.startsWith("/data/data/$TERMUX_PACKAGE_NAME") &&
            !path.startsWith("/data/user/0/$TERMUX_PACKAGE_NAME")
        val runCommandIntent = Intent()
            .setClassName(TERMUX_PACKAGE_NAME, TERMUX_RUN_COMMAND_SERVICE_CLASS_NAME)
            .setAction(TERMUX_RUN_COMMAND_ACTION)
        if (rooted) {
            runCommandIntent
                .putExtra(TERMUX_RUN_COMMAND_PATH, "/system/bin/su")
                .putExtra(
                    TERMUX_RUN_COMMAND_ARGUMENTS,
                    arrayOf(
                        "-c",
                        "cd '${path.replace("'", "'\\''")}' && exec " +
                            "'${getUserShell()}'"
                    )
                )
        } else {
            // An interactive shell instead of login(1), so that the working directory is kept.
            runCommandIntent
                .putExtra(TERMUX_RUN_COMMAND_PATH, "/data/data/$TERMUX_PACKAGE_NAME/files/usr/bin/sh")
                .putExtra(TERMUX_RUN_COMMAND_ARGUMENTS, emptyArray<String>())
                .putExtra(TERMUX_RUN_COMMAND_WORKDIR, path)
        }
        if (packageManager.resolveService(runCommandIntent, 0) == null) {
            return false
        }
        return try {
            context.startService(runCommandIntent)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun getUserShell(): String {
        // Respect the shell chosen by chsh(1), which is a symlink inside Termux's home.
        try {
            val result = Shell.cmd(
                "readlink -f /data/data/$TERMUX_PACKAGE_NAME/files/home/.termux/shell"
            ).exec()
            if (result.isSuccess) {
                val shell = result.out.firstOrNull()?.trim()
                if (!shell.isNullOrEmpty()) {
                    return shell
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "/data/data/$TERMUX_PACKAGE_NAME/files/usr/bin/bash"
    }
}
