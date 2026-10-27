package com.example.banaanscan.scan

import android.content.Context
import android.net.wifi.WifiManager
import com.example.banaanscan.classify.DeviceClassifier
import com.example.banaanscan.data.LanDevice
import com.example.banaanscan.data.OuiDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Local network discovery. Runs in phases:
 *  1. passive-ish listeners (mDNS, SSDP) in the background,
 *  2. a fast TCP/ICMP liveness sweep of the subnet,
 *  3. per-host fingerprinting: common ports, reverse DNS, NetBIOS, web banner,
 *  4. MAC addresses from ARP (old Android) or NetBIOS, then OUI vendor + type guess.
 */
class LanScanner(context: Context) {
    private val app = context.applicationContext
    private val wifi: WifiManager? = app.getSystemService(WifiManager::class.java)
    private val hosts = ConcurrentHashMap<String, LanDevice>()

    private val _devices = MutableStateFlow<List<LanDevice>>(emptyList())
    val devices: StateFlow<List<LanDevice>> = _devices.asStateFlow()
    val network = MutableStateFlow<LocalNetwork?>(null)
    val progress = MutableStateFlow(0f)
    val running = MutableStateFlow(false)
    val status = MutableStateFlow("Tap Scan to discover devices on this network")

    @Volatile private var lastPublish = 0L

    fun refreshNetwork() {
        network.value = NetworkInfo.current(app)
    }

    suspend fun scan() = coroutineScope {
        val net = NetworkInfo.current(app)
        network.value = net
        if (net == null) {
            status.value = "Connect to Wi-Fi or Ethernet to scan the local network"
            return@coroutineScope
        }
        running.value = true
        progress.value = 0f
        hosts.clear()
        publish()
        val lock = wifi?.createMulticastLock("banaanscan")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        try {
            update(net.ip) { it.copy(isSelf = true, mac = it.mac ?: ownMac(net.ip), macSource = "This device") }
            net.gateway?.let { gw ->
                val apVendor = OuiDatabase.lookup(net.bssid)
                update(gw) {
                    it.copy(
                        isGateway = true,
                        vendor = it.vendor ?: apVendor,
                        note = if (apVendor != null) "Vendor guessed from Wi-Fi BSSID ${net.bssid}" else null,
                    )
                }
            }

            status.value = "Listening for mDNS / UPnP announcements…"
            val mdns = launch {
                runCatching {
                    MdnsScanner.discover(6000) { h ->
                        if (!NetworkInfo.isInSubnet(h.ip, net)) return@discover
                        update(h.ip) {
                            it.copy(
                                mdnsServices = it.mdnsServices + h.services,
                                mdnsNames = it.mdnsNames + h.instances,
                                mdnsHosts = it.mdnsHosts + h.hostnames,
                                mdnsTxt = h.txt + it.mdnsTxt,
                            )
                        }
                    }
                }
            }
            val ssdp = launch {
                runCatching {
                    SsdpScanner.discover(5000) { ip, info ->
                        if (NetworkInfo.isInSubnet(ip, net)) update(ip) { it.copy(ssdp = info) }
                    }
                }
            }

            val targets = NetworkInfo.hostsToScan(net)
            status.value = "Probing ${targets.size} addresses on ${net.cidr}…"
            val swept = AtomicInteger()
            val sweepGate = Semaphore(SWEEP_PARALLELISM)
            targets.map { ip ->
                launch(Dispatchers.IO) {
                    sweepGate.withPermit {
                        if (isAlive(ip)) update(ip) { it }
                        progress.value = 0.7f * swept.incrementAndGet() / targets.size
                    }
                }
            }.joinAll()
            mdns.join()
            ssdp.join()
            applyArp(net)

            val found = hosts.keys.toList()
            status.value = "Fingerprinting ${found.size} devices…"
            val enriched = AtomicInteger()
            val enrichGate = Semaphore(ENRICH_PARALLELISM)
            found.map { ip ->
                launch(Dispatchers.IO) {
                    enrichGate.withPermit {
                        enrich(ip)
                        progress.value = 0.7f + 0.3f * enriched.incrementAndGet() / found.size
                    }
                }
            }.joinAll()
            applyArp(net)
            status.value = "Found ${hosts.size} devices on ${net.cidr}"
        } catch (e: CancellationException) {
            status.value = "Scan stopped – ${hosts.size} devices so far"
            throw e
        } finally {
            lock?.release()
            running.value = false
            progress.value = 1f
            publish()
        }
    }

    private suspend fun isAlive(ip: String): Boolean = coroutineScope {
        val addr = InetAddress.getByName(ip)
        val checks = Probes.SWEEP_PORTS.map { port ->
            async { Probes.tcp(addr, port, SWEEP_TIMEOUT_MS) != PortState.FILTERED }
        } + async { runCatching { addr.isReachable(SWEEP_TIMEOUT_MS + 100) }.getOrDefault(false) }
        checks.awaitAll().any { it }
    }

    private suspend fun enrich(ip: String) = coroutineScope {
        val addr = InetAddress.getByName(ip)
        val ports = async {
            Probes.COMMON_PORTS.keys.map { port ->
                async { port.takeIf { Probes.tcp(addr, port, PORT_TIMEOUT_MS) == PortState.OPEN } }
            }.awaitAll().filterNotNull().toSet()
        }
        val reverseDns = async { runCatching { addr.canonicalHostName }.getOrNull()?.takeIf { it != ip } }
        val netbios = async { NetBios.query(ip) }

        val open = ports.await()
        val banner = Probes.HTTP_PORTS.firstOrNull { it in open }?.let { Probes.httpBanner(ip, it) }
        val rdns = reverseDns.await()
        val nb = netbios.await()
        update(ip) { d ->
            val nbMac = nb?.mac?.takeIf { d.mac == null }
            d.copy(
                openPorts = d.openPorts + open,
                hostname = rdns ?: d.hostname,
                netbiosName = nb?.name ?: d.netbiosName,
                netbiosGroup = nb?.group ?: d.netbiosGroup,
                mac = d.mac ?: nbMac,
                macSource = if (nbMac != null) "NetBIOS" else d.macSource,
                httpServer = banner?.first ?: d.httpServer,
                httpTitle = banner?.second ?: d.httpTitle,
            )
        }
    }

    private fun applyArp(net: LocalNetwork) {
        Probes.readArp().forEach { (ip, mac) ->
            if (NetworkInfo.isInSubnet(ip, net)) {
                update(ip) { d -> if (d.mac == null) d.copy(mac = mac, macSource = "ARP table") else d }
            }
        }
    }

    /** Usually null on Android 11+, which hides the hardware address from apps. */
    private fun ownMac(ip: String): String? = runCatching {
        NetworkInterface.getByInetAddress(InetAddress.getByName(ip))?.hardwareAddress
            ?.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
            ?.takeIf { it != "02:00:00:00:00:00" }
    }.getOrNull()

    private fun update(ip: String, change: (LanDevice) -> LanDevice) {
        hosts.compute(ip) { _, old -> change(old ?: LanDevice(ip)).copy(lastSeen = System.currentTimeMillis()) }
        val now = System.currentTimeMillis()
        if (now - lastPublish > PUBLISH_INTERVAL_MS) publish()
    }

    private fun publish() {
        lastPublish = System.currentTimeMillis()
        _devices.value = hosts.values.map { d ->
            val withVendor = if (d.vendor == null && d.mac != null) d.copy(vendor = OuiDatabase.lookup(d.mac)) else d
            withVendor.copy(guess = DeviceClassifier.classify(withVendor))
        }.sortedBy { NetworkInfo.ipToLong(it.ip) }
    }

    private companion object {
        const val SWEEP_PARALLELISM = 48
        const val ENRICH_PARALLELISM = 12
        const val SWEEP_TIMEOUT_MS = 350
        const val PORT_TIMEOUT_MS = 600
        const val PUBLISH_INTERVAL_MS = 300L
    }
}
