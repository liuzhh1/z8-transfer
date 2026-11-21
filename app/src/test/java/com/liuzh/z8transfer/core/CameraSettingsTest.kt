package com.liuzh.z8transfer.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun stringPropertyBytes(code: Int,current: String,choices: List<String>): ByteArray {
    val p = ByteBuffer.allocate(65536).order(ByteOrder.LITTLE_ENDIAN)
    fun text(s: String) { p.put((s.length + 1).toByte()).put((s + '\u0000').toByteArray(Charsets.UTF_16LE)) }
    p.putShort(code.toShort()).putShort(0xffff.toShort()).put(1)
    text(current); text(current); p.put(2).putShort(choices.size.toShort()); choices.forEach(::text)
    return p.array().copyOf(p.position())
}

class CameraSettingsTest {
    @Test fun catalogueHasDistinctKnownCodesAndSeparatesCommonFromAdvanced() {
        assertEquals(65,ShootingSetting.entries.size)
        assertEquals(6,ShootingSetting.entries.count { it.group == ShootingGroup.COMMON })
        val codes = ShootingSetting.entries.flatMap { it.codes }
        assertEquals(codes.size,codes.toSet().size)
        assertFalse(codes.any { it in setOf(0xd015,0xd045,0xd15d,0xd20e,0x5011) }) // No reset/clock writes.
        assertTrue(ShootingSetting.entries.all { it.group in ShootingGroup.entries })
    }
    @Test fun modeMappingNeverConfusesNikonManualWithProgramOrWritesUnknownValues() {
        val p = ShootingParameter(ShootingSetting.MODE,DeviceProperty(0x500e,4,true,1,listOf(1,2,3,4,0x8010)))
        assertEquals("M · 手动",p.currentLabel); assertEquals("P · 程序自动",p.label(2))
        assertEquals("A · 光圈优先",p.label(3)); assertEquals("S · 快门优先",p.label(4))
        assertEquals(listOf(1L,2L,3L,4L),p.options); assertFalse(p.accepts(0x8010))
        assertEquals("开启",ShootingParameter(ShootingSetting.EXPOSURE_PREVIEW).label(0))
    }
    @Test fun modeSwitchRereadsCommonPermissionsAfterSingleWrite() = runTest {
        var mode = 1L; val calls = mutableListOf<Int>(); val writes = mutableListOf<ByteArray>()
        val camera = NikonRemoteCamera(setOf(0x1014,0x1016),setOf(0x500e,0x5007),{op,args,data ->
            assertEquals(0x1016,op); assertEquals(0x500e,args.single()); writes += data
            mode = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).short.toLong(); PtpReply(0x2001)
        }) { _,args ->
            val code = args.single(); calls += code
            PtpReply(0x2001,if (code == 0x500e) propertyBytes(code,4,mode,listOf(1,2,3,4))
                else propertyBytes(code,4,400,listOf(400,560),mode == 1L || mode == 3L))
        }
        assertTrue(camera.readParameters().first { it.setting == ShootingSetting.APERTURE }.editable)
        val actual = camera.setParameter(ShootingSetting.MODE,4)
        assertFalse(actual.first { it.setting == ShootingSetting.APERTURE }.editable)
        assertEquals(4L,actual.first { it.setting == ShootingSetting.MODE }.property!!.current)
        assertArrayEquals(byteArrayOf(4,0),writes.single()); assertEquals(3,calls.count { it == 0x500e })
    }
    @Test fun groupsOnlyReadTheirOwnAdvertisedPropertiesAndNeverEnumerateFiles() = runTest {
        val calls = mutableListOf<Int>()
        val camera = NikonRemoteCamera(setOf(0x1014),setOf(0x500e,0x5007,0x500b,0xd054)) { op,args ->
            assertEquals(0x1014,op); val code = args.single(); calls += code
            PtpReply(0x2001,propertyBytes(code,if (code == 0xd054) 2 else 4,1,listOf(0,1)))
        }
        assertTrue(calls.isEmpty()); camera.readParameters()
        assertEquals(setOf(0x500e,0x5007),calls.toSet()); calls.clear()
        val list = camera.readParameterGroup(ShootingGroup.EXPOSURE)
        assertTrue(list.all { it.setting.group == ShootingGroup.EXPOSURE })
        assertEquals(setOf(0x500b,0xd054),calls.toSet())
    }
    @Test fun vendor32DiscoveryKeepsFullPropertyIdForReadWriteAndVerification() = runTest {
        var value = 0L; val calls = mutableListOf<Pair<Int,List<Int>>>()
        val camera = NikonRemoteCamera(setOf(0x1014,0x1016,0x9439),emptySet(),{op,args,data ->
            calls += op to args.toList(); assertEquals(0x1d00e,args.single()); value = data.single().toLong(); PtpReply(0x2001)
        }) { op,args ->
            calls += op to args.toList()
            if (op == 0x9439) PtpReply(0x2001,ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(1).putInt(0x1d00e).array())
            else PtpReply(0x2001,propertyBytes(0xd00e,2,value,listOf(0,1)))
        }
        val list = camera.readParameterGroup(ShootingGroup.VIEW)
        assertTrue(list.first { it.setting == ShootingSetting.STARLIGHT }.editable)
        assertEquals(0x1d00e,list.first { it.setting == ShootingSetting.STARLIGHT }.property!!.code)
        assertEquals(1L,camera.setParameter(ShootingSetting.STARLIGHT,1).first { it.setting == ShootingSetting.STARLIGHT }.property!!.current)
        assertEquals(listOf(0xd),calls.single { it.first == 0x9439 }.second)
        assertTrue(calls.filter { it.first != 0x9439 }.all { it.second == listOf(0x1d00e) })
        assertTrue(camera.parameterCoverage.contains("0x1d00e")); assertTrue(camera.parameterCoverage.contains("发现 1 个"))
    }
    @Test fun malformedVendor32ListFallsBackToStandardPropertiesAndReportsGap() = runTest {
        val camera = NikonRemoteCamera(setOf(0x1014,0x9439),setOf(0x500f)) { op,_ ->
            if (op == 0x9439) PtpReply(0x2001,byteArrayOf(-1,-1,-1,-1))
            else PtpReply(0x2001,propertyBytes(0x500f,4,100,listOf(100,200)))
        }
        assertEquals("100",camera.readParameters().first { it.setting == ShootingSetting.ISO }.currentLabel)
        assertTrue(camera.parameterCoverage.contains("不可解析"))
    }
    @Test fun largeKelvinRangeAcceptsOnlyCameraBoundsAndExactStepWithoutHugeChoiceList() = runTest {
        var value = 6500L; var writes = 0
        val camera = NikonRemoteCamera(setOf(0x1014,0x1016),setOf(0xd01e),{_,_,data ->
            value = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).short.toLong() and 65535; writes++; PtpReply(0x2001)
        }) { _,_ -> PtpReply(0x2001,propertyBytes(0xd01e,4,value,listOf(2500,10000,1),range=true)) }
        val p = camera.readParameterGroup(ShootingGroup.COLOR).first { it.setting == ShootingSetting.COLOR_TEMPERATURE }
        assertTrue(p.options.isEmpty()); assertTrue(p.editable); assertEquals(2500L,p.manualRange!!.min)
        assertFalse(p.accepts(2499)); assertTrue(p.accepts(7601))
        assertEquals("7601 K",camera.setParameter(ShootingSetting.COLOR_TEMPERATURE,7601).first().currentLabel)
        assertEquals(1,writes)
        try { camera.setParameter(ShootingSetting.COLOR_TEMPERATURE,10001); fail() } catch (_: IllegalStateException) {}
        assertEquals(1,writes)
        assertFalse(DeviceProperty(0xd01e,4,true,6500,emptyList(),PropertyRange(2500,10000,10)).accepts(6501))
    }
    @Test fun stringEnumUsesExactCameraTextAndUtf16EncodingWithoutNumericIndex() = runTest {
        var current = "8256x5504"; var values = listOf("8256x5504","6192x4128"); val writes = mutableListOf<ByteArray>()
        val camera = NikonRemoteCamera(setOf(0x1014,0x1016),setOf(0x5003),{_,args,data ->
            assertEquals(0x5003,args.single()); writes += data
            current = String(data,1,data.size - 3,Charsets.UTF_16LE); PtpReply(0x2001)
        }) { _,_ -> PtpReply(0x2001,stringPropertyBytes(0x5003,current,values)) }
        val p = camera.readParameterGroup(ShootingGroup.QUALITY).first { it.setting == ShootingSetting.IMAGE_SIZE }
        assertEquals("8256x5504",p.currentLabel); assertTrue(p.editable)
        assertEquals("6192x4128",camera.setTextParameter(ShootingSetting.IMAGE_SIZE,"6192x4128").first { it.setting == ShootingSetting.IMAGE_SIZE }.currentLabel)
        assertEquals(10,writes.single()[0].toInt()); assertEquals(21,writes.single().size)
        values = listOf("8256x5504")
        try { camera.setTextParameter(ShootingSetting.IMAGE_SIZE,"6192x4128"); fail() } catch (_: IllegalStateException) {}
        assertEquals(1,writes.size)
    }
    @Test fun stringMalformedTerminationAndTruncationAreRejected() {
        val data = stringPropertyBytes(0x5003,"大尺寸",listOf("大尺寸","小尺寸"))
        val p = DeviceProperty.parse(data,0x5003); assertEquals("大尺寸",p.currentText)
        assertEquals(4,p.encodeText("大尺寸")[0].toInt())
        for (i in 0 until data.size - 1) if (i != 23) // A string descriptor may omit the optional FormFlag after its current value.
            assertThrows(Exception::class.java) { DeviceProperty.parse(data.copyOf(i),0x5003) }
        assertThrows(Exception::class.java) { p.encodeText("中尺寸") }
        assertThrows(Exception::class.java) { DeviceProperty.parse(data.copyOf().also { it[12] = 1 },0x5003) }
    }
    @Test fun undecodedCatalogSettingsStayReadOnlyEvenWhenCameraSaysWritable() = runTest {
        var writes = 0
        val camera = NikonRemoteCamera(setOf(0x1014,0x1016),setOf(0xd200),{_,_,_-> writes++; PtpReply(0x2001)}) { _,_ ->
            PtpReply(0x2001,propertyBytes(0xd200,2,1,listOf(1,2,3)))
        }
        val p = camera.readParameterGroup(ShootingGroup.COLOR).first { it.setting == ShootingSetting.PICTURE_CONTROL }
        assertFalse(p.editable); assertTrue(p.explanation!!.contains("进一步适配"))
        try { camera.setParameter(ShootingSetting.PICTURE_CONTROL,2); fail() } catch (_: IllegalStateException) {}
        assertEquals(0,writes)
    }
    @Test fun statusInformationCannotBeWrittenEvenWithMisleadingAccessBit() {
        val p = ShootingParameter(ShootingSetting.BATTERY,DeviceProperty(0x5001,2,true,60,(1L..100L).toList()))
        assertEquals("60%",p.currentLabel); assertFalse(p.editable); assertFalse(p.accepts(80))
        assertEquals("28 mm",ShootingParameter(ShootingSetting.FOCAL_LENGTH).label(2800))
        assertEquals("-0.50 EV",ShootingParameter(ShootingSetting.FLASH_COMPENSATION).label(-3))
    }
    @Test fun adaptiveScalarSettingsRejectStringDescriptorsBeforeAnyWrite() = runTest {
        var writes = 0
        val camera = NikonRemoteCamera(setOf(0x1014,0x1016),setOf(0xd061),{_,_,_->writes++;PtpReply(0x2001)}) { _,_ ->
            PtpReply(0x2001,stringPropertyBytes(0xd061,"AF-S",listOf("AF-S","MF")))
        }
        val p = camera.readParameterGroup(ShootingGroup.FOCUS).first { it.setting == ShootingSetting.FOCUS_MODE }
        assertFalse(p.editable); assertNull(p.property)
        try { camera.setTextParameter(ShootingSetting.FOCUS_MODE,"MF"); fail() } catch (_: IllegalStateException) {}
        assertEquals(0,writes)
    }
}
