package com.focusguard.net

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.focusguard.core.Graph
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DNS-only VPN. The TUN interface advertises itself as the device's DNS server and
 * routes *only* the DNS addresses (ours + well-known public resolvers), so every
 * DNS lookup passes through [DnsResolver] while ordinary traffic flows directly.
 * Combined with the device-owner always-on + lockdown flag there is no path for a
 * lookup that bypasses the filter (DoH in Chrome is disabled by managed policy;
 * Private DNS is locked; other browsers/VPN apps are suspended).
 *
 * Nothing but DNS metadata is ever seen or stored by this service.
 */
class DnsFilterVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var readerThread: Thread? = null
    private val running = AtomicBoolean(false)
    private val pool: ThreadPoolExecutor = Executors.newFixedThreadPool(8) as ThreadPoolExecutor
    private lateinit var resolver: DnsResolver

    override fun onCreate() {
        super.onCreate()
        resolver = DnsResolver(
            filterProvider = { Graph.filterStore.filter },
            protect = { socket -> protect(socket) },
        )
        resolver.onDecision = { name, d -> Graph.stats.recordDns(name, d) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTunnel()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running.get()) startTunnel()
        return START_STICKY
    }

    /** Called by the system when always-on is set for this package. */
    override fun onRevoke() {
        Log.w(TAG, "VPN revoked by system")
        stopTunnel()
        super.onRevoke()
    }

    private fun startTunnel() {
        val builder = Builder()
            .setSession("FocusGuard DNS filter")
            .setMtu(1500)
            .addAddress(TUN_V4, 24)
            .addAddress(TUN_V6, 64)
            .addDnsServer(DNS_V4)
            .addDnsServer(DNS_V6)
            .addRoute(DNS_V4, 32)
            .addRoute(DNS_V6, 128)
            .setBlocking(true)
        for (ip in PUBLIC_RESOLVERS_V4) builder.addRoute(ip, 32)
        for (ip in PUBLIC_RESOLVERS_V6) builder.addRoute(ip, 128)
        val pfd = try {
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "establish failed", e); null
        }
        if (pfd == null) {
            Log.e(TAG, "establish returned null (not prepared / not always-on)")
            return
        }
        tun = pfd
        running.set(true)
        tunnelUp.set(true)
        readerThread = Thread(::readLoop, "fg-dns-tun").also { it.start() }
        Log.i(TAG, "tunnel up")
    }

    private fun readLoop() {
        val pfd = tun ?: return
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val buf = ByteArray(32767)
        while (running.get()) {
            val n = try { input.read(buf) } catch (e: IOException) { if (running.get()) Log.w(TAG, "tun read", e); break }
            if (n <= 0) continue
            val pkt = UdpPacket.parse(buf, n) ?: continue
            if (pkt.dstPort != 53) continue
            val query = pkt.payload
            pool.execute {
                val resp = resolver.resolve(query, query.size) ?: return@execute
                val out = pkt.reply(resp)
                synchronized(output) {
                    try { output.write(out) } catch (e: IOException) { Log.w(TAG, "tun write", e) }
                }
            }
        }
        Log.i(TAG, "reader loop exit")
    }

    private fun stopTunnel() {
        running.set(false)
        tunnelUp.set(false)
        runCatching { tun?.close() }
        tun = null
        readerThread = null
    }

    override fun onDestroy() {
        stopTunnel()
        pool.shutdownNow()
        pool.awaitTermination(1, TimeUnit.SECONDS)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DnsFilterVpn"
        const val ACTION_STOP = "com.focusguard.STOP_VPN"

        const val TUN_V4 = "10.111.222.1"
        const val DNS_V4 = "10.111.222.2"
        const val TUN_V6 = "fd42:fa11:5a1d::1"
        const val DNS_V6 = "fd42:fa11:5a1d::2"

        /** Hard-coded public resolvers some apps use directly; routed through us so they are filtered too. */
        val PUBLIC_RESOLVERS_V4 = listOf(
            "8.8.8.8", "8.8.4.4", "1.1.1.1", "1.0.0.1", "9.9.9.9", "149.112.112.112",
            "208.67.222.222", "208.67.220.220", "94.140.14.14", "94.140.15.15", "76.76.2.0", "76.76.10.0",
            "185.228.168.9", "185.228.169.9", "45.90.28.0", "45.90.30.0",
        )
        val PUBLIC_RESOLVERS_V6 = listOf(
            "2001:4860:4860::8888", "2001:4860:4860::8844", "2606:4700:4700::1111", "2606:4700:4700::1001",
            "2620:fe::fe", "2620:fe::9", "2620:119:35::35", "2620:119:53::53",
        )

        fun inet(s: String): InetAddress = InetAddress.getByName(s)

        private val tunnelUp = AtomicBoolean(false)

        /** True while a TUN interface is established in this process. */
        val isTunnelUp: Boolean get() = tunnelUp.get()

        /** True once the user (or the device owner, implicitly) has consented to this VPN. */
        fun isPrepared(context: Context): Boolean = prepare(context) == null

        /** Starts the tunnel if consent exists. No-op otherwise (the system starts it when always-on). */
        fun startIfPrepared(context: Context) {
            if (!isPrepared(context)) return
            runCatching { context.startService(Intent(context, DnsFilterVpnService::class.java)) }
                .onFailure { Log.w(TAG, "start failed", it) }
        }

        fun stop(context: Context) {
            runCatching { context.startService(Intent(context, DnsFilterVpnService::class.java).setAction(ACTION_STOP)) }
                .onFailure { Log.w(TAG, "stop failed", it) }
        }
    }
}
