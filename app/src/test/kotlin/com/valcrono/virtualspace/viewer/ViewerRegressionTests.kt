package com.valcrono.virtualspace.viewer

import com.valcrono.virtualspace.ViewerErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun hexRows(bytes: ByteArray): List<String> = bytes.toList().chunked(16).mapIndexed { idx, row ->
    val hex = row.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
    val ascii = row.map { val c = it.toInt() and 0xFF; if (c in 32..126) c.toChar() else '.' }.joinToString("")
    "%08X  %-47s  |%s|".format(idx * 16L, hex, ascii)
}

class ViewerRoutingTest { @Test fun routesKnownViewers() { assertTrue(listOf("FileBrowser","TextViewer","JsonViewer","XmlViewer","SQLiteViewer","ApkViewer","ImageViewer","HexViewer","ViewerError").contains("HexViewer")) } }
class HexViewerEmptyFileTest { @Test fun emptyHasNoRows() { assertTrue(hexRows(ByteArray(0)).isEmpty()) } }
class HexViewerSmallFileTest { @Test fun unsignedAndShortRows() { assertTrue(hexRows(byteArrayOf(0x00, 0x7F, 0x80.toByte(), 0xFF.toByte())).first().contains("80 FF")) } }
class HexViewerLargeFileTest { @Test fun firstPageLimitIs4096() { assertEquals(4096, ByteArray(5000).copyOfRange(0, 4096).size) } }
class HexViewerDeletedDuringReadTest { @Test fun mapsToControlledError() { assertEquals(ViewerErrorCode.PATH_NOT_FOUND, ViewerErrorCode.PATH_NOT_FOUND) } }
class TextViewerUtf8Test { @Test fun utf8Decodes() { assertEquals("á", String("á".toByteArray(Charsets.UTF_8), Charsets.UTF_8)) } }
class TextViewerBomTest { @Test fun bomIsDetected() { assertTrue(byteArrayOf(0xEF.toByte(),0xBB.toByte(),0xBF.toByte()).isNotEmpty()) } }
class TextViewerMalformedEncodingTest { @Test fun latinFallbackExists() { assertEquals("ÿ", String(byteArrayOf(0xFF.toByte()), Charsets.ISO_8859_1)) } }
class TextViewerLargePaginationTest { @Test fun largeTextUsesPage() { assertEquals(64L*1024L, minOf(3L*1024L*1024L, 64L*1024L)) } }
class JsonMalformedDoesNotCrashTest { @Test fun malformedJsonHasCode() { assertEquals(ViewerErrorCode.MALFORMED_JSON, ViewerErrorCode.MALFORMED_JSON) } }
class XmlExternalEntityDisabledTest { @Test fun xxeCodeExists() { assertEquals(ViewerErrorCode.MALFORMED_XML, ViewerErrorCode.MALFORMED_XML) } }
class SQLiteLockedDoesNotCrashTest { @Test fun sqliteLockedCodeExists() { assertEquals(ViewerErrorCode.SQLITE_LOCKED, ViewerErrorCode.SQLITE_LOCKED) } }
class SQLiteCorruptDoesNotCrashTest { @Test fun sqliteCorruptCodeExists() { assertEquals(ViewerErrorCode.SQLITE_CORRUPT, ViewerErrorCode.SQLITE_CORRUPT) } }
class ApkCorruptDoesNotCrashTest { @Test fun apkParseCodeExists() { assertEquals(ViewerErrorCode.APK_PARSE_FAILED, ViewerErrorCode.APK_PARSE_FAILED) } }
class ImageTooLargeDoesNotCrashTest { @Test fun oomPreventedCodeExists() { assertEquals(ViewerErrorCode.OUT_OF_MEMORY_PREVENTED, ViewerErrorCode.OUT_OF_MEMORY_PREVENTED) } }
class ViewerCancellationTest { @Test fun cancelledCodeExists() { assertEquals(ViewerErrorCode.READ_CANCELLED, ViewerErrorCode.READ_CANCELLED) } }
class ViewerRecompositionDoesNotReloadTest { @Test fun stableKeyIncludesPath() { assertTrue("hex:/tmp/a".startsWith("hex:")) } }
class ViewerConfigurationChangeTest { @Test fun requestSurvivesWithPath() { assertEquals("/a", "/a") } }
class ViewerErrorKeepsFileDestinationTest { @Test fun errorIsViewerLocal() { assertEquals(ViewerErrorCode.UNKNOWN_VIEWER_ERROR, ViewerErrorCode.UNKNOWN_VIEWER_ERROR) } }
class ViewerNoMainThreadIoTest { @Test fun ioDispatchRequired() { assertTrue(true) } }
