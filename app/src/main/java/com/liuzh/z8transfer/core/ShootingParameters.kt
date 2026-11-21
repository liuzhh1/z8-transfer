package com.liuzh.z8transfer.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

data class PropertyRange(val min: Long, val max: Long, val step: Long) {
    fun contains(value: Long): Boolean = value in min..max && (step == 0L || (value - min) % step == 0L)
}

/** Scalar PTP DevicePropDesc. Unknown types/forms are rejected, never guessed. */
data class DeviceProperty(val code: Int, val type: Int, val writable: Boolean, val current: Long,
    val choices: List<Long>, val range: PropertyRange? = null, val currentText: String? = null,
    val textChoices: List<String> = emptyList()) {
    companion object {
        fun parse(bytes: ByteArray, expectedCode: Int): DeviceProperty {
            val p = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val wireCode = p.short.toInt() and 0xffff
            // Nikon GetVendorCodes exposes uint32 IDs. GetDevicePropDesc still carries a uint16
            // header, while the request parameter retains the full ID (libgphoto2 ptp.c/ptp-pack.c).
            require(wireCode == (expectedCode and 0xffff)) { "相机参数编号不匹配" }
            val code = expectedCode
            val type = p.short.toInt() and 0xffff
            val access = p.get().toInt() and 0xff
            require(access in 0..1) { "相机参数权限无效" }
            fun string(): String {
                val size = p.get().toInt() and 0xff
                if (size == 0) return ""
                val chars = CharArray(size) { p.char }
                require(chars.last() == '\u0000' && chars.dropLast(1).none { it == '\u0000' }) { "相机字符串没有有效结束标记" }
                return String(chars,0,size - 1)
            }
            if (type == 0xffff) {
                string(); val current = string()
                val form = if (p.hasRemaining()) p.get().toInt() and 0xff else 0
                require(form == 0 || form == 2) { "相机文本参数格式暂不支持" }
                val values = if (form == 2) {
                    val n = p.short.toInt() and 0xffff
                    require(n <= 4096) { "相机参数选项过多" }
                    List(n) { string() }.distinct()
                } else emptyList()
                require(!p.hasRemaining()) { "相机参数数据长度不匹配" }
                return DeviceProperty(code,type,access == 1,0,emptyList(),currentText=current,textChoices=values)
            }
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
            var range: PropertyRange? = null
            val choices = when (p.get().toInt() and 0xff) {
                0 -> emptyList()
                1 -> {
                    val min = number(); val max = number(); val step = number()
                    require(max >= min && step >= 0 && (step != 0L || min == max)) { "相机参数范围无效" }
                    range = PropertyRange(min,max,step)
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
            return DeviceProperty(code, type, access == 1, current, choices,range)
        }
    }
    fun encode(value: Long): ByteArray {
        require(writable && accepts(value)) { "参数已变化，请刷新后重新选择" }
        val p = ByteBuffer.allocate(when (type) { 1, 2 -> 1; 3, 4 -> 2; 5, 6 -> 4; else -> error("参数类型不支持") })
            .order(ByteOrder.LITTLE_ENDIAN)
        when (type) { 1, 2 -> p.put(value.toByte()); 3, 4 -> p.putShort(value.toShort()); else -> p.putInt(value.toInt()) }
        return p.array()
    }
    fun accepts(value: Long): Boolean = value in choices || range?.contains(value) == true
    fun encodeText(value: String): ByteArray {
        require(type == 0xffff && writable && value in textChoices && value.length <= 254 && '\u0000' !in value) {
            "文本选项已变化，请刷新后重新选择"
        }
        return byteArrayOf((value.length + 1).toByte()) + (value + '\u0000').toByteArray(Charsets.UTF_16LE)
    }
}

data class ShootingParameter(val setting: ShootingSetting, val property: DeviceProperty? = null,
    val reason: String? = null) {
    val options: List<Long> get() = property?.choices.orEmpty().filter { selectable(it) }
    val manualRange: PropertyRange? get() = property?.range?.takeIf { property.choices.isEmpty() && setting in setOf(
        ShootingSetting.COLOR_TEMPERATURE,ShootingSetting.VIDEO_KELVIN,ShootingSetting.BURST_COUNT,ShootingSetting.SELF_TIMER_COUNT) }
    val editable: Boolean get() = reason == null && setting.readOnlyNote == null && property?.writable == true &&
        (options.isNotEmpty() || property.textChoices.isNotEmpty() || manualRange != null)
    val currentLabel: String get() = property?.let { it.currentText ?: label(it.current) } ?: "不可用"
    val explanation: String? get() = reason ?: when {
        property == null -> "当前连接未开放"
        setting.readOnlyNote != null -> setting.readOnlyNote
        !property.writable -> "相机设为只读，请检查 P/A/S/M 模式、镜头和参数锁定"
        !editable -> "相机未提供已适配的可选档位，请在机身调节"
        else -> null
    }
    fun accepts(value: Long): Boolean = editable && property?.accepts(value) == true && (manualRange != null || selectable(value))
    private fun selectable(value: Long): Boolean = setting.namedChoices(property?.code ?: 0)?.let { value in it } ?: when (setting) {
        ShootingSetting.SHUTTER, ShootingSetting.VIDEO_SHUTTER -> if (property?.code in setOf(0xd100,0xd1a8)) value < 0xfffffffdL && (value and 0xffff) != 0L && value shr 16 > 0
            else value in 1..0xfffffffcL
        ShootingSetting.APERTURE, ShootingSetting.VIDEO_APERTURE -> value > 0
        else -> setting.readOnlyNote == null
    }
    fun label(value: Long): String = setting.namedChoices(property?.code ?: 0)?.get(value) ?: when (setting) {
        ShootingSetting.APERTURE, ShootingSetting.VIDEO_APERTURE -> "f/${decimal(value / 100.0)}"
        ShootingSetting.ISO, ShootingSetting.VIDEO_ISO -> if (value == 0xffffL) "自动" else value.toString()
        ShootingSetting.EXPOSURE, ShootingSetting.VIDEO_EXPOSURE -> String.format(Locale.ROOT, "%+.2f EV", value / 1000.0)
        ShootingSetting.FLASH_COMPENSATION -> String.format(Locale.ROOT, "%+.2f EV",value / 6.0)
        ShootingSetting.COLOR_TEMPERATURE,ShootingSetting.VIDEO_KELVIN -> "$value K"
        ShootingSetting.BATTERY -> "$value%"
        ShootingSetting.FOCAL_LENGTH -> "${decimal(value / 100.0)} mm"
        ShootingSetting.BURST_COUNT,ShootingSetting.SELF_TIMER_COUNT,ShootingSetting.REMAINING -> "$value 张"
        ShootingSetting.WHITE_BALANCE,ShootingSetting.VIDEO_WB -> when (value.toInt()) {
            1 -> "手动"; 2 -> "自动"; 3 -> "单次自动"; 4 -> "晴天"; 5 -> "荧光灯"; 6 -> "白炽灯"; 7, 0x8015 -> "闪光灯"
            0x8010 -> "阴天"; 0x8011 -> "阴影"; 0x8012 -> "色温"; 0x8013 -> "预设"; 0x8014 -> "关闭"; 0x8016 -> "自然光自动"
            else -> "相机选项 0x${value.toString(16)}"
        }
        ShootingSetting.SHUTTER,ShootingSetting.VIDEO_SHUTTER -> if (property?.code in setOf(0xd100,0xd1a8)) when (value) {
            0xffffffffL -> "B 门（机身控制）"; 0xfffffffdL -> "Time（机身控制）"; 0xfffffffeL -> "闪光同步（机身控制）"
            else -> { val n = value shr 16; val d = value and 0xffff; if (d == 1L) "$n s" else "$n/$d s" }
        } else when {
            value == 0xffffffffL -> "B 门（机身控制）"
            value == 0L -> "未设定"
            value < 10000L -> "1/${decimal(10000.0 / value)} s"
            else -> "${decimal(value / 10000.0)} s"
        }
        else -> "机身档位 $value"
    }
}

private fun decimal(value: Double): String = String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
