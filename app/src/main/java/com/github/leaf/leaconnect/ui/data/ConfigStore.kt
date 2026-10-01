package com.github.leaf.leaconnect.ui.data

import android.content.Context
import android.net.Uri
import android.os.Bundle
import com.github.leaf.leaconnect.ConfigProvider

/**
 * UI 到 ConfigProvider 的通道。这里刻意不做本地缓存：Core 那边每 3 秒读一次 provider，
 * UI 再多一次跨进程调用不算成本，换来的是"改完立刻看得见"。
 */
class ConfigStore(private val context: Context) {

    private val uri = Uri.parse("content://${ConfigProvider.AUTHORITY}")

    fun get(key: String): String? = try {
        context.contentResolver.call(uri, "get", key, null)?.getString("value")
    } catch (t: Throwable) {
        null
    }

    fun set(key: String, value: String?) {
        try {
            val b = Bundle()
            b.putString("value", value)
            context.contentResolver.call(uri, "set", key, b)
        } catch (t: Throwable) {
            // provider 在蓝牙进程没起来时也可能被拉起，失败就静默：Core 有 persist 兜底
        }
    }

    fun reset() {
        try {
            context.contentResolver.call(uri, "reset", null, null)
        } catch (t: Throwable) {
        }
    }

    fun boolean(key: String, def: Boolean): Boolean {
        val v = get(key) ?: return def
        return "true".equals(v, true) || v == "1"
    }

    /** 蓝牙进程读配置的心跳。ageMs 为 null 表示从来没读过。 */
    data class Heartbeat(val ageMs: Long?, val uid: Int, val reads: Int)

    fun heartbeat(): Heartbeat = try {
        val b = context.contentResolver.call(uri, "state", null, null)
            ?: return Heartbeat(null, -1, 0)
        val at = b.getLong("hb_at", -1L)
        val now = b.getLong("now", -1L)
        if (at < 0 || now < 0) {
            Heartbeat(null, b.getInt("hb_uid", -1), b.getInt("hb_n", 0))
        } else {
            Heartbeat(now - at, b.getInt("hb_uid", -1), b.getInt("hb_n", 0))
        }
    } catch (t: Throwable) {
        Heartbeat(null, -1, 0)
    }

    companion object {
        /** 和 Core 里 BT_UID 同一个值：心跳来自它才算"蓝牙进程真的在读配置" */
        const val BLUETOOTH_UID = 1002
    }
}
