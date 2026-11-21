package com.liuzh.z8transfer.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

enum class ShootingSetting(val title: String, val codes: List<Int>, val type: Int) {
    SHUTTER("快门", listOf(0xd100, 0x500d), 6),
    APERTURE("光圈", listOf(0x5007), 4),
    ISO("ISO", listOf(0x500f), 4),
    EXPOSURE("曝光补偿", listOf(0x5010), 3),
    WHITE_BALANCE("白平衡", listOf(0x5005), 4),
}

/** Scalar PTP DevicePropDesc. Unknown types/forms are rejected, never guessed. */
data class DeviceProperty(val code: Int, val type: Int, val writable: Boolean, val current: Long,
    val choices: List<Long>) {
    companion object {
        fun parse(bytes: ByteArray, expectedCode: Int): DeviceProperty {
            val p = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val code = p.short.toInt() and 0xffff
            require(code == expectedCode) { "相机参数编号不匹配" }
            val type = p.short.toInt() and 0xffff
            val access = p.get().toInt() and 0xff
            require(access in 0..1) { "相机参数权限无效" }
            fun number(): Long = when (type) {
                1 -> p.get().toLong()
                2 -> p.get().toLong() and 0xff
                3 -> p.short.toLong()
                4 -> p.short.toLong() and 0xffff
                5 -> p.int.toLong()
                6 -> p.int.toLong() and 0xffffffffL
                else -> error("暂不支持此参数数据类型")
            }
            number() // Factory default is informational; never apply it.
            val current = number()
            val choices = when (p.get().toInt() and 0xff) {
                0 -> emptyList()
                1 -> {
                    val min = number(); val max = number(); val step = number()
                    require(max >= min && step >= 0 && (step != 0L || min == max)) { "相机参数范围无效" }
                    if (min == max) listOf(min)
                    else if ((max - min) / step < 1024) (0..((max - min) / step).toInt()).map { min + it * step }
                    else emptyList() // Do not invent presets or allocate unbounded ranges.
                }
                2 -> {
                    val size = p.short.toInt() and 0xffff
                    require(size <= 4096) { "相机参数选项过多" }
                    List(size) { number() }.distinct()
                }
                else -> error("暂不支持此参数选项格式")
            }
            require(!p.hasRemaining()) { "相机参数数据长度不匹配" }
            return DeviceProperty(code, type, access == 1, current, choices)
        }
    }
    fun encode(value: Long): ByteArray {
        require(writable && value in choices) { "参数已变化，请刷新后重新选择" }
        val p = ByteBuffer.allocate(when (type) { 1, 2 -> 1; 3, 4 -> 2; 5, 6 -> 4; else -> error("参数类型不支持") })
            .order(ByteOrder.LITTLE_ENDIAN)
        when (type) { 1, 2 -> p.put(value.toByte()); 3, 4 -> p.putShort(value.toShort()); else -> p.putInt(value.toInt()) }
        return p.array()
    }
}

data class ShootingParameter(val setting: ShootingSetting, val property: DeviceProperty? = null,
    val reason: String? = null) {
    val options: List<Long> get() = property?.choices.orEmpty().filter { selectable(it) }
    val editable: Boolean get() = reason == null && property?.writable == true && options.isNotEmpty()
    val currentLabel: String get() = property?.let { label(it.current) } ?: "不可用"
    val explanation: String? get() = reason ?: when {
        property == null -> "当前连接未开放"
        !property.writable -> "相机设为只读，请检查 P/A/S/M 模式、镜头和参数锁定"
        options.isEmpty() -> "相机未提供可选值，请在机身调节"
        else -> null
    }
    private fun selectable(value: Long): Boolean = when (setting) {
        ShootingSetting.SHUTTER -> if (property?.code == 0xd100) value < 0xfffffffdL && (value and 0xffff) != 0L && value shr 16 > 0
            else value in 1..0xfffffffcL
        ShootingSetting.APERTURE -> value > 0
        else -> true
    }
    fun label(value: Long): String = when (setting) {
        ShootingSetting.APERTURE -> "f/${decimal(value / 100.0)}"
        ShootingSetting.ISO -> if (value == 0xffffL) "自动" else value.toString()
        ShootingSetting.EXPOSURE -> String.format(Locale.ROOT, "%+.2f EV", value / 1000.0)
        ShootingSetting.WHITE_BALANCE -> when (value.toInt()) {
            1 -> "手动"; 2 -> "自动"; 3 -> "单次自动"; 4 -> "晴天"; 5 -> "荧光灯"; 6 -> "白炽灯"; 7, 0x8015 -> "闪光灯"
            0x8010 -> "阴天"; 0x8011 -> "阴影"; 0x8012 -> "色温"; 0x8013 -> "预设"; 0x8014 -> "关闭"; 0x8016 -> "自然光自动"
            else -> "相机选项 0x${value.toString(16)}"
        }
        ShootingSetting.SHUTTER -> if (property?.code == 0xd100) when (value) {
            0xffffffffL -> "B 门（机身控制）"; 0xfffffffdL -> "Time（机身控制）"; 0xfffffffeL -> "闪光同步（机身控制）"
            else -> { val n = value shr 16; val d = value and 0xffff; if (d == 1L) "$n s" else "$n/$d s" }
        } else when {
            value == 0xffffffffL -> "B 门（机身控制）"
            value == 0L -> "未设定"
            value < 10000L -> "1/${decimal(10000.0 / value)} s"
            else -> "${decimal(value / 10000.0)} s"
        }
    }
}

private fun decimal(value: Double): String = String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
