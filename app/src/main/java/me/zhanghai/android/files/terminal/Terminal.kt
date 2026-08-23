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
    private const val TERMUX_PREFIX = "/data/data/$TERMUX_PACKAGE_NAME/files/usr"
    private const val TERMUX_HOME = "/data/data/$TERMUX_PACKAGE_NAME/files/home"
    private const val TERMUX_FALLBACK_SHELL = "$TERMUX_PREFIX/bin/bash"

    // Standard zygote supplementary groups (including inet), so that networking keeps working
    // after dropping privileges.
    private const val TERMUX_SUPPLEMENTARY_GROUPS = "3001,3002,3003,3010,9997"

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
        val runCommandIntent = Intent()
            .setClassName(TERMUX_PACKAGE_NAME, TERMUX_RUN_COMMAND_SERVICE_CLASS_NAME)
            .setAction(TERMUX_RUN_COMMAND_ACTION)
        val termuxIdentity =
            if (Settings.ROOT_STRATEGY.valueCompat != RootStrategy.NEVER) probeTermuxIdentity()
            else null
        if (termuxIdentity != null) {
            // Root is only used to enter directories that Termux itself cannot chdir(2) into;
            // setpriv immediately drops back to Termux's own identity, so the session behaves
            // like a normal one and cannot leave root-owned files behind. The user escalates
            // with sudo(8) inside the session when needed.
            runCommandIntent
                .putExtra(TERMUX_RUN_COMMAND_PATH, "/system/bin/su")
                .putExtra(
                    TERMUX_RUN_COMMAND_ARGUMENTS,
                    arrayOf(
                        "-c",
                        "cd '${path.replace("'", "'\\''")}' && " +
                            "HOME='$TERMUX_HOME' exec '${termuxIdentity.setPriv}' " +
                            "--reuid ${termuxIdentity.uid} --regid ${termuxIdentity.gid} " +
                            "--groups $TERMUX_SUPPLEMENTARY_GROUPS '${termuxIdentity.shell}'"
                    )
                )
        } else {
            // An interactive shell instead of login(1), so that the working directory is kept.
            runCommandIntent
                .putExtra(TERMUX_RUN_COMMAND_PATH, TERMUX_FALLBACK_SHELL)
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

    private class TermuxIdentity(
        val shell: String,
        val setPriv: String,
        val uid: Int,
        val gid: Int
    )

    private fun probeTermuxIdentity(): TermuxIdentity? {
        try {
            val result = Shell.cmd(
                "echo \"shell=\$(readlink -f '$TERMUX_HOME/.termux/shell' 2>/dev/null)\"; " +
                    "echo \"ids=\$(stat -c '%u %g' '$TERMUX_HOME' 2>/dev/null)\"; " +
                    "echo \"setPriv=\$(ls '$TERMUX_PREFIX/bin/setpriv' 2>/dev/null)\""
            ).exec()
            if (!result.isSuccess) {
                return null
            }
            var shell = ""
            var setPriv = ""
            var uid = 0
            var gid = 0
            for (line in result.out) {
                when {
                    line.startsWith("shell=") -> shell = line.substring("shell=".length)
                    line.startsWith("ids=") -> {
                        val parts = line.substring("ids=".length).split(" ")
                        if (parts.size == 2) {
                            uid = parts[0].toIntOrNull() ?: 0
                            gid = parts[1].toIntOrNull() ?: 0
                        }
                    }
                    line.startsWith("setPriv=") -> setPriv = line.substring("setPriv=".length)
                }
            }
            if (uid == 0 || gid == 0 || setPriv.isEmpty()) {
                return null
            }
            // Respect the shell chosen by chsh(1), which is a symlink inside Termux's home.
            return TermuxIdentity(shell.ifEmpty { TERMUX_FALLBACK_SHELL }, setPriv, uid, gid)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}
