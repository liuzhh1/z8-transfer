package com.liuzh.z8transfer.core

enum class ShootingGroup(val title: String) {
    COMMON("模式/曝光"), EXPOSURE("ISO/测光"), FOCUS("对焦"), QUALITY("画质"), COLOR("色彩"),
    RELEASE("释放"), FLASH("闪光"), VIEW("取景"), VIDEO("视频"), STATUS("状态")
}

/** Nikon/PTP property catalogue; a catalogue entry is not a claim of device support.
 * Indices with model-dependent meanings remain read-only until their encoding is verified.
 * Codes checked against libgphoto2 ptp.h/config.c, including the Z8-specific menu selection.
 */
enum class ShootingSetting(val title: String, val codes: List<Int>, val type: Int,
    val group: ShootingGroup = ShootingGroup.COMMON, val readOnlyNote: String? = null) {
    MODE("拍摄模式", listOf(0x500e), 4),
    SHUTTER("快门", listOf(0xd100, 0x500d), 6),
    APERTURE("光圈", listOf(0x5007), 4),
    ISO("ISO", listOf(0x500f), 4),
    EXPOSURE("曝光补偿", listOf(0x5010), 3),
    WHITE_BALANCE("白平衡", listOf(0x5005), 4),
    AUTO_ISO("自动 ISO", listOf(0xd054), 2, ShootingGroup.EXPOSURE),
    METERING("测光模式", listOf(0x500b), 4, ShootingGroup.EXPOSURE),
    EV_STEP("曝光步长", listOf(0xd056), 2, ShootingGroup.EXPOSURE),
    ISO_STEP("ISO 步长", listOf(0xd055), 2, ShootingGroup.EXPOSURE),
    MIN_SHUTTER("自动 ISO 最低快门", listOf(0xd164), 2, ShootingGroup.EXPOSURE),
    SHUTTER_LOCK("快门锁定", listOf(0xd112), 2, ShootingGroup.EXPOSURE),
    APERTURE_LOCK("光圈锁定", listOf(0xd113), 2, ShootingGroup.EXPOSURE),
    FOCUS_MODE("对焦模式", listOf(0xd061,0x500a), 0, ShootingGroup.FOCUS),
    AF_AREA("AF 区域模式", listOf(0x501c), 4, ShootingGroup.FOCUS),
    AF_ACTIVATION("AF 激活方式", listOf(0xd04b), 2, ShootingGroup.FOCUS),
    AFC_PRIORITY("AF-C 优先选择", listOf(0xd048), 2, ShootingGroup.FOCUS),
    AFS_PRIORITY("AF-S 优先选择", listOf(0xd049), 2, ShootingGroup.FOCUS),
    LOW_LIGHT_AF("低光照 AF", listOf(0xd17a), 2, ShootingGroup.FOCUS),
    AF_ASSIST("AF 辅助照明", listOf(0xd163), 2, ShootingGroup.FOCUS),
    FOCUS_POINT_LOCK("对焦点锁定", listOf(0x1d015), 0, ShootingGroup.FOCUS),
    IMAGE_QUALITY("图像品质", listOf(0x5004), 2, ShootingGroup.QUALITY),
    IMAGE_SIZE("图像尺寸", listOf(0x5003), 0xffff, ShootingGroup.QUALITY),
    RAW_COMPRESSION("RAW 记录", listOf(0xd016), 2, ShootingGroup.QUALITY),
    RAW_BITS("RAW 位深", listOf(0xd149), 2, ShootingGroup.QUALITY),
    IMAGE_AREA("图像区域", listOf(0xd030), 2, ShootingGroup.QUALITY),
    PRIMARY_SLOT("主插槽", listOf(0xd156), 2, ShootingGroup.QUALITY, "插槽编号需实机核对，请在机身设置"),
    SECONDARY_SLOT("副插槽功能", listOf(0xd148), 2, ShootingGroup.QUALITY, "副卡记录策略需实机核对，请在机身设置"),
    COLOR_TEMPERATURE("白平衡色温", listOf(0xd01e), 4, ShootingGroup.COLOR),
    COLOR_SPACE("色空间", listOf(0xd032), 2, ShootingGroup.COLOR),
    D_LIGHTING("动态 D-Lighting", listOf(0xd14e), 0, ShootingGroup.COLOR, "D-Lighting 档位编码随机型变化，请在机身设置"),
    PICTURE_CONTROL("优化校准", listOf(0xd200), 0, ShootingGroup.COLOR, "优化校准档位需进一步适配，请在机身选择"),
    HIGH_ISO_NR("高 ISO 降噪", listOf(0xd070), 2, ShootingGroup.COLOR),
    LONG_EXPOSURE_NR("长曝光降噪", listOf(0xd06b), 2, ShootingGroup.COLOR),
    RELEASE_MODE("释放模式", listOf(0x5013), 4, ShootingGroup.RELEASE),
    LOW_FPS("低速连拍速度", listOf(0xd068), 2, ShootingGroup.RELEASE, "连拍速度档位随机型变化，请在机身设置"),
    HIGH_FPS("高速连拍速度", listOf(0xd186), 2, ShootingGroup.RELEASE, "连拍速度档位随机型变化，请在机身设置"),
    BURST_COUNT("遥控连拍张数", listOf(0x5018), 4, ShootingGroup.RELEASE),
    SELF_TIMER("自拍延时", listOf(0xd063), 2, ShootingGroup.RELEASE),
    SELF_TIMER_COUNT("自拍张数", listOf(0xd0f5), 2, ShootingGroup.RELEASE),
    SHOOTING_BANK("照片拍摄菜单库", listOf(0xd010), 2, ShootingGroup.RELEASE),
    CUSTOM_BANK("自定义设置库", listOf(0xd040), 2, ShootingGroup.RELEASE),
    FLASH_MODE("闪光模式", listOf(0x500c), 4, ShootingGroup.FLASH),
    FLASH_COMPENSATION("闪光补偿", listOf(0xd126), 1, ShootingGroup.FLASH),
    FLASH_SYNC("闪光同步速度", listOf(0xd074), 2, ShootingGroup.FLASH, "同步速度档位随机型变化，请在机身设置"),
    STARLIGHT("星光视图", listOf(0x1d00e), 0, ShootingGroup.VIEW),
    SILENT_MODE("静音模式", listOf(0x1d011), 0, ShootingGroup.VIEW),
    GRID("构图网格", listOf(0xd16c), 2, ShootingGroup.VIEW),
    EXPOSURE_PREVIEW("曝光效果预览", listOf(0xd1a5), 2, ShootingGroup.VIEW),
    VIEW_ZOOM("取景放大档位", listOf(0xd1a3), 2, ShootingGroup.VIEW, "取景倍率编码需实机确认，请在机身调整"),
    VIDEO_SHUTTER("视频快门", listOf(0xd1a8), 6, ShootingGroup.VIDEO),
    VIDEO_APERTURE("视频光圈", listOf(0xd1a9), 4, ShootingGroup.VIDEO),
    VIDEO_ISO("视频 ISO", listOf(0xd1aa), 0, ShootingGroup.VIDEO),
    VIDEO_EXPOSURE("视频曝光补偿", listOf(0xd1ab), 3, ShootingGroup.VIDEO),
    VIDEO_WB("视频白平衡", listOf(0xd23a), 4, ShootingGroup.VIDEO),
    VIDEO_KELVIN("视频白平衡色温", listOf(0xd21a), 4, ShootingGroup.VIDEO),
    VIDEO_FOCUS("视频对焦模式", listOf(0xd1fa), 0, ShootingGroup.VIDEO),
    VIDEO_METERING("视频测光模式", listOf(0xd1af), 4, ShootingGroup.VIDEO),
    VIDEO_NR("视频高 ISO 降噪", listOf(0xd236), 2, ShootingGroup.VIDEO),
    VIDEO_EVR("视频电子减震", listOf(0xd314), 2, ShootingGroup.VIDEO),
    BATTERY("电池电量", listOf(0x5001), 2, ShootingGroup.STATUS, "状态信息"),
    FOCAL_LENGTH("镜头焦距", listOf(0x5008), 6, ShootingGroup.STATUS, "状态信息"),
    REMAINING("剩余可拍张数", listOf(0xd1f1), 0, ShootingGroup.STATUS, "状态信息"),
    ACTIVE_SLOT("当前存储卡", listOf(0xd1f2), 0, ShootingGroup.STATUS, "状态信息"),
    SUBJECT_DETECTION("主体侦测档位", listOf(0x1d005), 0, ShootingGroup.STATUS, "主体侦测枚举需进一步适配，请在机身选择"),
}

private val choiceTables by lazy { ShootingSetting.entries.associateWith { it.buildChoiceTable(0xd061) } }
private val standardFocusChoices by lazy { ShootingSetting.FOCUS_MODE.buildChoiceTable(0x500a) }
internal fun ShootingSetting.namedChoices(code: Int): Map<Long,String>? =
    if (this == ShootingSetting.FOCUS_MODE && code == 0x500a) standardFocusChoices else choiceTables[this]

private fun ShootingSetting.buildChoiceTable(code: Int): Map<Long,String>? {
    fun names(vararg values: Pair<Int,String>) = values.associate { it.first.toLong() to it.second }
    val onOff = names(0 to "关闭",1 to "开启")
    return when (this) {
        ShootingSetting.MODE -> names(1 to "M · 手动",2 to "P · 程序自动",3 to "A · 光圈优先",4 to "S · 快门优先")
        ShootingSetting.AUTO_ISO, ShootingSetting.LOW_LIGHT_AF, ShootingSetting.AF_ASSIST,
        ShootingSetting.FOCUS_POINT_LOCK, ShootingSetting.SHUTTER_LOCK, ShootingSetting.APERTURE_LOCK,
        ShootingSetting.STARLIGHT, ShootingSetting.SILENT_MODE, ShootingSetting.GRID,
        ShootingSetting.LONG_EXPOSURE_NR, ShootingSetting.VIDEO_EVR -> onOff
        ShootingSetting.EXPOSURE_PREVIEW -> names(0 to "开启",1 to "关闭")
        ShootingSetting.METERING, ShootingSetting.VIDEO_METERING -> names(2 to "中央重点",3 to "矩阵",4 to "点测光",0x8010 to "亮部重点")
        ShootingSetting.EV_STEP, ShootingSetting.ISO_STEP -> names(0 to "1/3 档",1 to "1/2 档")
        ShootingSetting.FOCUS_MODE -> if (code == 0x500a) names(1 to "MF",0x8010 to "AF-S",0x8011 to "AF-C",0x8013 to "AF-F")
            else names(0 to "AF-S",1 to "AF-C",2 to "AF-F",3 to "MF（固定）",4 to "MF（可选择）")
        ShootingSetting.VIDEO_FOCUS -> names(0 to "AF-S",1 to "AF-C",2 to "AF-F",3 to "MF（固定）",4 to "MF（可选择）")
        ShootingSetting.AF_AREA -> names(0x8010 to "单点 AF",0x8011 to "自动区域 AF",0x8012 to "3D 跟踪",0x8017 to "微点 AF",
            0x8018 to "宽区域 AF（S）",0x8019 to "宽区域 AF（L）",0x801e to "宽区域 AF（C1）",0x801f to "宽区域 AF（C2）")
        ShootingSetting.AF_ACTIVATION -> names(0 to "快门按钮 / AF-ON",1 to "仅 AF-ON")
        ShootingSetting.AFC_PRIORITY -> names(0 to "释放",1 to "释放 + 对焦",2 to "对焦")
        ShootingSetting.AFS_PRIORITY -> names(0 to "释放",1 to "对焦")
        ShootingSetting.IMAGE_QUALITY -> names(0 to "JPEG/HEIF 基本",1 to "JPEG/HEIF 基本★",2 to "JPEG/HEIF 标准",3 to "JPEG/HEIF 标准★",
            4 to "JPEG/HEIF 精细",5 to "JPEG/HEIF 精细★",7 to "NEF（RAW）",8 to "RAW + 基本",9 to "RAW + 基本★",
            10 to "RAW + 标准",11 to "RAW + 标准★",12 to "RAW + 精细",13 to "RAW + 精细★")
        ShootingSetting.RAW_COMPRESSION -> names(0 to "无损压缩",1 to "有损压缩",3 to "高效率★",4 to "高效率")
        ShootingSetting.RAW_BITS -> names(0 to "12 位",1 to "14 位")
        ShootingSetting.IMAGE_AREA -> names(0 to "FX",1 to "DX",2 to "1:1",3 to "16:9")
        ShootingSetting.COLOR_SPACE -> names(0 to "sRGB",1 to "Adobe RGB")
        ShootingSetting.HIGH_ISO_NR, ShootingSetting.VIDEO_NR -> names(0 to "关闭",1 to "低",2 to "标准",3 to "高")
        ShootingSetting.RELEASE_MODE -> names(1 to "单张",2 to "连拍",0x8010 to "低速连拍",0x8011 to "自拍")
        ShootingSetting.SELF_TIMER -> names(0 to "2 秒",1 to "5 秒",2 to "10 秒",3 to "20 秒")
        ShootingSetting.SHOOTING_BANK, ShootingSetting.CUSTOM_BANK -> names(0 to "A",1 to "B",2 to "C",3 to "D")
        ShootingSetting.FLASH_MODE -> names(1 to "自动",2 to "关闭",3 to "补充闪光",4 to "自动防红眼",5 to "防红眼",6 to "外部同步",
            0x8010 to "自动",0x8011 to "自动慢同步",0x8012 to "后帘慢同步",0x8013 to "防红眼慢同步")
        ShootingSetting.MIN_SHUTTER -> (listOf("1/4000","1/3200","1/2500","1/2000","1/1600","1/1250","1/1000","1/800","1/640","1/500",
            "1/400","1/320","1/250","1/200","1/160","1/125","1/100","1/80","1/60","1/50","1/40","1/30","1/25","1/20",
            "1/15","1/13","1/10","1/8","1/6","1/5","1/4","1/3","1/2.5","1/2","1/1.6","1/1.3","1","1.3","1.6","2","2.5","3","4","5","6","8","10","13","15","20","25","30","自动"))
            .mapIndexed { index,label -> index.toLong() to label }.toMap()
        else -> null
    }
}
