package com.liuzh.z8transfer.core

/** A numeric local IPv4 address avoids DNS and accidental cellular/Internet connections. */
data class WifiEndpoint(val host: String = "", val port: Int = 15740) {
    init {
        require(port in 1..65535) { "端口应为 1–65535" }
        require(host.isEmpty() || isLocalIpv4(host)) { "请输入相机的局域网 IPv4 地址，例如 192.168.1.1；留空可自动查找热点网关" }
    }
    companion object {
        fun isLocalIpv4(host: String): Boolean {
            val parts = host.split('.')
            if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || it.any { c -> c !in '0'..'9' } }) return false
            val n = parts.map { it.toIntOrNull() ?: return false }
            if (n.any { it !in 0..255 }) return false
            return n[0] == 10 || n[0] == 192 && n[1] == 168 ||
                n[0] == 172 && n[1] in 16..31 || n[0] == 169 && n[1] == 254
        }
    }
}

/** IPv4 prefix matching without the IpPrefix constructor added in Android 13. */
internal fun sameIpv4Subnet(local: ByteArray, target: ByteArray, prefixLength: Int): Boolean {
    if (local.size != 4 || target.size != 4 || prefixLength !in 1..32) return false
    for (index in 0..3) {
        val bits = (prefixLength - index * 8).coerceIn(0, 8)
        val mask = (0xff shl (8 - bits)) and 0xff
        if ((local[index].toInt() and mask) != (target[index].toInt() and mask)) return false
    }
    return true
}
