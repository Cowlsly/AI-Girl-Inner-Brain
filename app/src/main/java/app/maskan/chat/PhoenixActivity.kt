/*
 * Maskan — Private AI chat
 * Copyright (C) 2025 Humam Malhas and Maskan contributors
 *
 * This program is free software: you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * version 3 as published by the Free Software Foundation.
 *
 * See LICENSE file for full terms.
 */
package app.maskan.chat

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.util.Log
import java.io.File

/**
 * The restart after a restore. The ProcessPhoenix pattern with no library: this activity runs in
 * its own process (`:phoenix`), kills the main process, starts the launcher activity again and
 * exits. `MaskanApplication.onCreate` does nothing at all in the `:phoenix` process, so the
 * staged restore is applied by exactly one process - the fresh main one.
 *
 * The restart is convenience, not mechanism. The staged restore is applied at the next start of
 * the main process whoever starts it, so if the relaunch is refused the app has simply closed,
 * with a sentence on screen telling the user to open it again. In debug builds a file named
 * [BLOCK_FILE] in cacheDir makes the relaunch skip deliberately, so that path can be tested
 * instead of reasoned about.
 */
class PhoenixActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pid = intent.getIntExtra(EXTRA_PID, -1)
        if (pid > 0 && pid != Process.myPid()) {
            Process.killProcess(pid)
            waitForDeath(pid)
        }
        val blocked = BuildConfig.DEBUG && File(cacheDir, BLOCK_FILE).exists()
        if (blocked) {
            Log.w(TAG, "relaunch deliberately skipped: " + BLOCK_FILE + " is present")
        } else {
            val next = packageManager.getLaunchIntentForPackage(packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            if (next == null) Log.w(TAG, "no launch intent")
            else runCatching { startActivity(next) }.onFailure { Log.w(TAG, "relaunch refused", it) }
        }
        finish()
        Runtime.getRuntime().exit(0)
    }

    private fun waitForDeath(pid: Int) {
        val proc = File("/proc/" + pid)
        var waited = 0
        while (proc.exists() && waited < 2000) {
            Thread.sleep(20)
            waited += 20
        }
        Log.i(TAG, "process " + pid + (if (proc.exists()) " still alive after " else " gone after ") + waited + " ms")
    }

    companion object {
        private const val TAG = "MaskanRestore"
        const val EXTRA_PID = "pid"
        const val BLOCK_FILE = "phoenix-block"

        /**
         * Hands the relaunch to the `:phoenix` process and ends this one. This process must not
         * go on running over a committed stage whatever happens to the relaunch, so the exit is
         * unconditional.
         */
        fun restart(context: Context) {
            val intent = Intent(context, PhoenixActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(EXTRA_PID, Process.myPid())
            try {
                context.startActivity(intent)
            } catch (e: Throwable) {
                Log.w(TAG, "could not start the phoenix activity", e)
            }
            (context as? Activity)?.finishAffinity()
            Runtime.getRuntime().exit(0)
        }
    }
}
