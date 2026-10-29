package com.lscardinali.pocketwow.realm

import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/** Reads memory and network facts straight from /proc and the network stack. */
object SystemStats {

    /** Values from /proc/meminfo in kB. */
    data class DeviceMemory(val totalKb: Long, val availableKb: Long, val swapTotalKb: Long, val swapFreeKb: Long)

    /** VmRSS / VmHWM (peak RSS) / VmSwap of a process in kB. */
    data class ProcessMemory(val pid: Int, val rssKb: Long, val peakRssKb: Long, val swapKb: Long)

    fun deviceMemory(): DeviceMemory {
        val fields = readKbFields(File("/proc/meminfo"))
        return DeviceMemory(
            totalKb = fields["MemTotal"] ?: 0,
            availableKb = fields["MemAvailable"] ?: 0,
            swapTotalKb = fields["SwapTotal"] ?: 0,
            swapFreeKb = fields["SwapFree"] ?: 0,
        )
    }

    fun processMemory(pid: Int = android.os.Process.myPid()): ProcessMemory? {
        val file = File("/proc/$pid/status")
        if (!file.canRead()) return null
        val fields = readKbFields(file)
        return ProcessMemory(pid, fields["VmRSS"] ?: 0, fields["VmHWM"] ?: 0, fields["VmSwap"] ?: 0)
    }

    /** Non-loopback IPv4 addresses, e.g. "wlan0" -> "192.168.1.42". */
    fun lanAddresses(): List<Pair<String, String>> =
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nic ->
                nic.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .map { nic.name to it.hostAddress.orEmpty() }
            }

    private fun readKbFields(file: File): Map<String, Long> =
        file.readLines().mapNotNull { line ->
            val key = line.substringBefore(':', "")
            val value = line.substringAfter(':').trim().substringBefore(' ').toLongOrNull()
            if (key.isEmpty() || value == null) null else key to value
        }.toMap()
}
