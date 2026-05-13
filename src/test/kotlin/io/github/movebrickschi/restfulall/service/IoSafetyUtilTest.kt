package io.github.movebrickschi.restfulall.service

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

/**
 * v1.3.1 - IoSafetyUtil 纯逻辑单测，不依赖 IntelliJ Platform。
 */
class IoSafetyUtilTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    // ---------- isInsideDirectory ----------

    @Test
    fun `direct child file is inside`() {
        val base = temp.newFolder()
        val child = File(base, "sub/file.txt")
        child.parentFile.mkdirs()
        child.createNewFile()
        assertTrue(IoSafetyUtil.isInsideDirectory(child, base))
    }

    @Test
    fun `base itself is inside`() {
        val base = temp.newFolder()
        assertTrue(IoSafetyUtil.isInsideDirectory(base, base))
    }

    @Test
    fun `parent directory is outside`() {
        val base = temp.newFolder()
        val outside = base.parentFile
        assertFalse(IoSafetyUtil.isInsideDirectory(outside, base))
    }

    @Test
    fun `dotdot traversal is rejected`() {
        val base = temp.newFolder()
        val traverse = File(base, "../sibling")
        assertFalse(IoSafetyUtil.isInsideDirectory(traverse, base))
    }

    @Test
    fun `sibling sharing prefix is outside`() {
        val baseRoot = temp.newFolder()
        val base = File(baseRoot, "myproj").also { it.mkdirs() }
        val sibling = File(baseRoot, "myproj-other").also { it.mkdirs() }
        val target = File(sibling, "file.txt").also { it.createNewFile() }
        assertFalse(
            "sibling 'myproj-other' must not be considered inside 'myproj'",
            IoSafetyUtil.isInsideDirectory(target, base),
        )
    }

    @Test
    fun `non-existent target still resolves canonically and respects boundary`() {
        val base = temp.newFolder()
        val ghost = File(base, "doesnotexist/file.txt")
        assertTrue(IoSafetyUtil.isInsideDirectory(ghost, base))
        val outsideGhost = File(base, "../ghost.txt")
        assertFalse(IoSafetyUtil.isInsideDirectory(outsideGhost, base))
    }

    // ---------- readBoundedBytes ----------

    @Test
    fun `read fully when under limit`() {
        val data = "hello world".toByteArray()
        val (bytes, truncated) = IoSafetyUtil.readBoundedBytes(ByteArrayInputStream(data), 100)
        assertArrayEquals(data, bytes)
        assertFalse(truncated)
    }

    @Test
    fun `read up to limit and mark truncated`() {
        val data = ByteArray(2048) { (it and 0xff).toByte() }
        val (bytes, truncated) = IoSafetyUtil.readBoundedBytes(ByteArrayInputStream(data), 1024)
        assertEquals(1024, bytes.size)
        assertTrue(truncated)
        assertArrayEquals(data.copyOfRange(0, 1024), bytes)
    }

    @Test
    fun `exact boundary is not marked truncated`() {
        val data = ByteArray(1024) { 7 }
        val (bytes, truncated) = IoSafetyUtil.readBoundedBytes(ByteArrayInputStream(data), 1024)
        assertEquals(1024, bytes.size)
        assertFalse("data length == limit must not be marked truncated", truncated)
    }

    @Test
    fun `empty stream returns empty bytes`() {
        val (bytes, truncated) = IoSafetyUtil.readBoundedBytes(ByteArrayInputStream(ByteArray(0)), 100)
        assertEquals(0, bytes.size)
        assertFalse(truncated)
    }

    @Test
    fun `zero max returns empty bytes and marks truncated when stream has data`() {
        val (bytes, truncated) = IoSafetyUtil.readBoundedBytes(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 0)
        assertEquals(0, bytes.size)
        assertTrue(truncated)
    }

    @Test
    fun `negative max throws`() {
        var thrown = false
        try {
            IoSafetyUtil.readBoundedBytes(ByteArrayInputStream(ByteArray(0)), -1)
        } catch (_: IllegalArgumentException) {
            thrown = true
        }
        assertTrue("must reject negative max", thrown)
    }
}
