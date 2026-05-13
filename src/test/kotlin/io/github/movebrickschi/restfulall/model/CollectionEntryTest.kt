package io.github.movebrickschi.restfulall.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.1 W2-1 - CollectionEntry / CollectionItem / RequestSpecData 纯逻辑单测。
 *
 * 不依赖 IntelliJ Platform；服务层 CRUD / 树形 / 标灰逻辑见 [CollectionServiceTest]。
 */
class CollectionEntryTest {

    @Test
    fun `newDefault should produce blank parent and empty items`() {
        val c = CollectionEntry.newDefault("My Collection")
        assertEquals("My Collection", c.name)
        assertNull(c.parentId)
        assertTrue(c.items.isEmpty())
        assertTrue(c.id.isNotBlank())
    }

    @Test
    fun `nextItemOrder should be 0 for empty collection`() {
        val c = CollectionEntry.newDefault()
        assertEquals(0, c.nextItemOrder())
    }

    @Test
    fun `nextItemOrder should be max plus one`() {
        val c = CollectionEntry.newDefault().apply {
            items.add(CollectionItem(name = "a", order = 0))
            items.add(CollectionItem(name = "b", order = 5))
            items.add(CollectionItem(name = "c", order = 2))
        }
        assertEquals(6, c.nextItemOrder())
    }

    @Test
    fun `sortedItems should sort by order ascending`() {
        val c = CollectionEntry.newDefault().apply {
            items.add(CollectionItem(name = "z", order = 9))
            items.add(CollectionItem(name = "a", order = 1))
            items.add(CollectionItem(name = "m", order = 5))
        }
        val sorted = c.sortedItems().map { it.name }
        assertEquals(listOf("a", "m", "z"), sorted)
    }

    @Test
    fun `touch should bump updatedAt`() {
        val c = CollectionEntry.newDefault()
        val before = c.updatedAt
        Thread.sleep(5)
        c.touch()
        assertTrue(c.updatedAt > before)
    }

    @Test
    fun `CollectionItem default disabled should be false`() {
        val item = CollectionItem(name = "create-order")
        assertFalse(item.disabled)
        assertNull(item.routeRef)
        assertEquals("", item.note)
    }

    @Test
    fun `CollectionItem id should be unique by default`() {
        val a = CollectionItem(name = "a")
        val b = CollectionItem(name = "b")
        assertNotEquals(a.id, b.id)
    }

    @Test
    fun `RequestSpecData default should reflect GET none`() {
        val d = RequestSpecData()
        assertEquals("GET", d.method)
        assertEquals("none", d.bodyType)
        assertEquals(30L, d.timeoutSeconds)
        assertTrue(d.queryParams.isEmpty())
    }

    @Test
    fun `RequestSpecData toExecutable should drop disabled and blank-name params`() {
        val data = RequestSpecData(
            method = "POST",
            url = "http://api/x",
            queryParams = mutableListOf(
                ParamEntry(enabled = true, name = "page", value = "1"),
                ParamEntry(enabled = false, name = "size", value = "10"),
                ParamEntry(enabled = true, name = "", value = "ignored"),
            ),
            headers = mutableListOf(
                ParamEntry(enabled = true, name = "Authorization", value = "Bearer X"),
            ),
            bodyType = "json",
            bodyContent = "{\"k\":1}",
        )
        val spec = data.toExecutable()
        assertEquals("POST", spec.method)
        assertEquals(listOf("page" to "1"), spec.queryParams)
        assertEquals(listOf("Authorization" to "Bearer X"), spec.headers)
        assertEquals("{\"k\":1}", spec.bodyContent)
    }

    @Test
    fun `RequestSpecData toExecutable should clamp timeout to bounds`() {
        val tooBig = RequestSpecData(timeoutSeconds = 9999).toExecutable()
        assertEquals(RequestSpecData.MAX_TIMEOUT, tooBig.timeoutSeconds)

        val tooSmall = RequestSpecData(timeoutSeconds = 0).toExecutable()
        assertEquals(RequestSpecData.MIN_TIMEOUT, tooSmall.timeoutSeconds)
    }

    @Test
    fun `RequestSpecData fromExecutable should mark all params enabled`() {
        val spec = io.github.movebrickschi.restfulall.service.RequestSpec(
            method = "PUT",
            url = "http://api/y",
            queryParams = listOf("a" to "1", "b" to "2"),
        )
        val data = RequestSpecData.fromExecutable(spec)
        assertEquals("PUT", data.method)
        assertEquals(2, data.queryParams.size)
        assertTrue(data.queryParams.all { it.enabled })
        assertEquals("a", data.queryParams[0].name)
    }
}
