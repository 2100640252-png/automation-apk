package com.automation.work

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object AdbWirelessHelper {
    private const val PREFS = "adb_wireless"
    private const val KEY_PAIRED = "paired"
    private const val SERVICE_PAIRING = "_adb-tls-pairing._tcp"
    private const val SERVICE_CONNECT = "_adb-tls-connect._tcp"

    @Volatile private var configured = false
    @Volatile private var connection: Kadb? = null

    fun isPaired(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PAIRED, false)

    private fun configure(context: Context) {
        if (configured) return
        synchronized(this) {
            if (configured) return
            val certFile = File(context.filesDir, "adb_cert.pem")
            val keyFile = File(context.filesDir, "adb_key.pem")
            if (certFile.exists() && keyFile.exists()) {
                KadbCert.set(certFile.readBytes(), keyFile.readBytes())
            } else {
                val (cert, key) = KadbCert.get()
                certFile.writeBytes(cert)
                keyFile.writeBytes(key)
            }
            configured = true
        }
    }

    private fun discover(context: Context, serviceType: String, timeoutSec: Long = 12): Pair<String, Int>? {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return null
        val wifi = context.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("adb_wireless")?.apply { setReferenceCounted(false); acquire() }
        val latch = CountDownLatch(1)
        var found: Pair<String, Int>? = null
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val host = resolved.host?.hostAddress ?: return
                        found = host to resolved.port
                        latch.countDown()
                    }
                })
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { latch.countDown() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        return try {
            nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            latch.await(timeoutSec, TimeUnit.SECONDS)
            found
        } catch (_: Exception) {
            null
        } finally {
            try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) {}
            try { if (lock?.isHeld == true) lock.release() } catch (_: Exception) {}
        }
    }

    fun pair(context: Context, code: String): Pair<Boolean, String> {
        return try {
            configure(context)
            val endpoint = discover(context, SERVICE_PAIRING)
                ?: return false to "没找到配对服务：请打开无线调试的“使用配对码配对”页面，停在显示配对码的界面再点配对。"
            kotlinx.coroutines.runBlocking { Kadb.pair(endpoint.first, endpoint.second, code.trim()) }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PAIRED, true).apply()
            closeConnection()
            true to "内置 ADB 配对成功：${endpoint.first}:${endpoint.second}"
        } catch (e: Exception) {
            false to "内置 ADB 配对失败：${e.message}"
        }
    }

    private fun closeConnection() {
        try { connection?.close() } catch (_: Exception) {}
        connection = null
    }

    fun runShell(context: Context, command: String): RootHelper.CmdResult {
        return try {
            configure(context)
            val kadb = connection ?: run {
                val endpoint = discover(context, SERVICE_CONNECT)
                    ?: return RootHelper.CmdResult(-1, "没找到无线调试连接服务：请确认无线调试已打开且已配对。")
                Kadb.create(endpoint.first, endpoint.second).also { connection = it }
            }
            val response = kadb.shell(command)
            RootHelper.CmdResult(response.exitCode, response.output.trim())
        } catch (e: Exception) {
            closeConnection()
            RootHelper.CmdResult(-1, "内置 ADB 执行失败：${e.message}")
        }
    }
}
