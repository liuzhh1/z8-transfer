package com.liuzh.z8transfer.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun propertyBytes(code: Int, type: Int, current: Long, values: List<Long>, writable: Boolean = true,
    range: Boolean = false): ByteArray {
    val p = ByteBuffer.allocate(65536).order(ByteOrder.LITTLE_ENDIAN)
    p.putShort(code.toShort()).putShort(type.toShort()).put(if (writable) 1 else 0)
    fun number(n: Long) { when (type) { 1, 2 -> p.put(n.toByte()); 3, 4 -> p.putShort(n.toShort()); else -> p.putInt(n.toInt()) } }
    number(current); number(current)
    p.put(if (range) 1 else 2)
    if (!range) p.putShort(values.size.toShort())
    values.forEach(::number)
    return p.array().copyOf(p.position())
}

class ShootingParametersTest {
    @Test fun unsignedShutterAndSignedExposureArePreservedByteExactly() {
        val shutter = DeviceProperty.parse(propertyBytes(0xd100,6,0xffffffffL,listOf(0xffffffffL,0x100fa)),0xd100)
        assertEquals(0xffffffffL,shutter.current)
        assertArrayEquals(byteArrayOf(-1,-1,-1,-1),shutter.encode(0xffffffffL))
        val ev = DeviceProperty.parse(propertyBytes(0x5010,3,-333,listOf(-333,0,333)),0x5010)
        assertEquals(-333L,ev.current); assertArrayEquals(byteArrayOf(0xb3.toByte(),0xfe.toByte()),ev.encode(-333))
    }
    @Test fun rangeExpansionHonorsStepAndBoundsWithoutInventingPresets() {
        val iso = DeviceProperty.parse(propertyBytes(0x500f,4,100,listOf(100,400,100),range=true),0x500f)
        assertEquals(listOf(100L,200L,300L,400L),iso.choices)
        val huge = DeviceProperty.parse(propertyBytes(0x500d,6,100,listOf(1,0xffffffffL,1),range=true),0x500d)
        assertTrue(huge.choices.isEmpty())
        assertThrows(Exception::class.java) { DeviceProperty.parse(propertyBytes(0x500f,4,100,listOf(100,400,0),range=true),0x500f) }
    }
    @Test fun truncatedWrongCodeTypeAndFormAreRejected() {
        val bytes = propertyBytes(0x5007,4,400,listOf(400,560))
        for (i in bytes.indices) assertThrows(Exception::class.java) { DeviceProperty.parse(bytes.copyOf(i),0x5007) }
        assertThrows(Exception::class.java) { DeviceProperty.parse(bytes,0x500f) }
        assertThrows(Exception::class.java) { DeviceProperty.parse(bytes + byteArrayOf(0),0x5007) }
        assertThrows(Exception::class.java) { DeviceProperty.parse(bytes.copyOf().also { it[2] = 7 },0x5007) }
        assertThrows(Exception::class.java) { DeviceProperty.parse(bytes.copyOf().also { it[9] = 9 },0x5007) }
    }
    @Test fun readOnlyAndValuesOutsideCameraOptionsCannotBeEncoded() {
        val p = DeviceProperty.parse(propertyBytes(0x5007,4,400,listOf(400,560),false),0x5007)
        assertFalse(ShootingParameter(ShootingSetting.APERTURE,p).editable)
        assertThrows(Exception::class.java) { p.encode(400) }
        assertThrows(Exception::class.java) { p.copy(writable=true).encode(180) }
    }
    @Test fun humanReadableLabelsUseActualNikonRationalValuesAndExcludeUnboundedExposures() {
        val p = ShootingParameter(ShootingSetting.SHUTTER,DeviceProperty(0xd100,6,true,0x100fa,listOf(0x100fa,0x1e0001,0xffffffffL,0xfffffffdL)))
        assertEquals("1/250 s",p.currentLabel); assertEquals("30 s",p.label(0x1e0001))
        assertEquals(listOf(0x100faL,0x1e0001L),p.options)
        assertEquals("f/5.6",ShootingParameter(ShootingSetting.APERTURE).label(560))
        assertEquals("-0.33 EV",ShootingParameter(ShootingSetting.EXPOSURE).label(-333))
        assertEquals("自然光自动",ShootingParameter(ShootingSetting.WHITE_BALANCE).label(0x8016))
    }
    private class Rig(val props: Set<Int>? = null, val ops: Set<Int> = setOf(0x1014,0x1016,0x100e)) {
        val calls = mutableListOf<Pair<Int,List<Int>>>()
        val writes = mutableListOf<ByteArray>()
        var value = 100L
        var readOnly = false
        var changeAfterSet = true
        var writeError = false
        var writeCode = 0x2001
        val camera = NikonRemoteCamera(ops,props,{op,args,bytes ->
            calls += op to args.toList(); writes += bytes
            if (writeError) throw IOException("connection lost")
            if (changeAfterSet && writeCode == 0x2001) value = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).short.toLong() and 65535
            PtpReply(writeCode)
        }) { op,args ->
            calls += op to args.toList()
            when {
                op == 0x1014 && args[0] == 0x500f -> PtpReply(0x2001,propertyBytes(0x500f,4,value,listOf(100,200,400),!readOnly))
                op == 0x1014 -> PtpReply(0x200a)
                else -> PtpReply(0x2001)
            }
        }
    }
    @Test fun entryAndShutterNeverLoadParametersAndExplicitReadDoesNotWrite() = runTest {
        val rig = Rig(); assertTrue(rig.camera.capabilities.parameters); assertTrue(rig.calls.isEmpty())
        rig.camera.capture(false) {}; assertEquals(listOf(0x100e),rig.calls.map { it.first })
        val list = rig.camera.readParameters()
        assertEquals(5,list.size); assertEquals("100",list.first { it.setting == ShootingSetting.ISO }.currentLabel)
        assertTrue(rig.writes.isEmpty())
    }
    @Test fun writesOnceWithCorrectTypeAndDisplaysCameraReadback() = runTest {
        val rig = Rig()
        val list = rig.camera.setParameter(ShootingSetting.ISO,200)
        assertEquals(200L,list.first { it.setting == ShootingSetting.ISO }.property!!.current)
        assertArrayEquals(byteArrayOf(-56,0),rig.writes.single())
        assertEquals(listOf(0x500f),rig.calls.single { it.first == 0x1016 }.second)
        assertTrue(rig.calls.last().first == 0x1014)
        rig.camera.setParameter(ShootingSetting.ISO,200); assertEquals(1,rig.writes.size)
    }
    @Test fun revalidatesReadOnlyOrUnsupportedValueImmediatelyBeforeAnyWrite() = runTest {
        val rig = Rig(); rig.camera.readParameters(); rig.readOnly = true
        try { rig.camera.setParameter(ShootingSetting.ISO,200); fail() } catch (_: IllegalStateException) {}
        rig.readOnly = false
        try { rig.camera.setParameter(ShootingSetting.ISO,800); fail() } catch (_: IllegalStateException) {}
        assertTrue(rig.writes.isEmpty())
    }
    @Test fun lostResponseAndCameraRejectionNeverReplayWrites() = runTest {
        val rig = Rig().apply { writeError = true }
        try { rig.camera.setParameter(ShootingSetting.ISO,200); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("未确认")) }
        assertEquals(1,rig.writes.size)
        val rejected = Rig().apply { writeCode = 0x201d }
        try { rejected.camera.setParameter(ShootingSetting.ISO,200); fail() } catch (_: IOException) {}
        assertEquals(1,rejected.writes.size)
    }
    @Test fun successfulResponseWithoutMatchingReadbackReportsActualValue() = runTest {
        val rig = Rig().apply { changeAfterSet = false }
        try { rig.camera.setParameter(ShootingSetting.ISO,200); fail() }
        catch (e: ParameterVerificationException) { assertEquals(100L,e.actual.first { it.setting == ShootingSetting.ISO }.property!!.current) }
        assertEquals(1,rig.writes.size)
    }
    @Test fun missingAdvertisedPropertyIsNotProbedAndReadOnlyConnectionStillDisplaysValues() = runTest {
        val rig = Rig(setOf(0x500f),setOf(0x1014))
        val list = rig.camera.readParameters()
        assertEquals(listOf(0x500f),rig.calls.filter { it.first == 0x1014 }.map { it.second.single() })
        assertEquals("100",list.first { it.setting == ShootingSetting.ISO }.currentLabel)
        assertTrue(list.none { it.editable })
    }
    @Test fun nikonShutterIsPreferredAndStandardShutterIsFallback() = runTest {
        var nikonWorks = true
        val camera = NikonRemoteCamera(setOf(0x1014,0x1016),dataCommand = {_,_,_->PtpReply(0x2001)}) { op,args ->
            val c = args[0]
            when {
                op == 0x1014 && c == 0xd100 && nikonWorks -> PtpReply(0x2001,propertyBytes(c,6,0x100fa,listOf(0x100fa,0x101f4)))
                op == 0x1014 && c == 0x500d -> PtpReply(0x2001,propertyBytes(c,6,40,listOf(40,80)))
                else -> PtpReply(0x200a)
            }
        }
        assertEquals(0xd100,camera.readParameters().first().property!!.code)
        nikonWorks = false
        assertEquals(0x500d,camera.readParameters().first().property!!.code)
    }
    @Test fun vendorPropertyListDiscoversNikonShutterWithoutScanningObjects() = runTest {
        val calls = mutableListOf<Int>()
        val camera = NikonRemoteCamera(setOf(0x1014,0x1016,0x90ca),emptySet(),{_,_,_->PtpReply(0x2001)}) { op,args ->
            calls += op
            if (op == 0x90ca) PtpReply(0x2001,ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).putInt(1).putShort(0xd100.toShort()).array())
            else PtpReply(0x2001,propertyBytes(args[0],6,0x100fa,listOf(0x100fa,0x101f4)))
        }
        assertTrue(calls.isEmpty())
        assertEquals("1/250 s",camera.readParameters().first().currentLabel)
        camera.readParameters()
        assertEquals(1,calls.count { it == 0x90ca }); assertTrue(calls.all { it == 0x90ca || it == 0x1014 })
    }
}
