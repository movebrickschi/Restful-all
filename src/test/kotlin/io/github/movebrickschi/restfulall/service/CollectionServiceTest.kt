package io.github.movebrickschi.restfulall.service

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.movebrickschi.restfulall.model.CollectionEntry
import io.github.movebrickschi.restfulall.model.CollectionItem
import io.github.movebrickschi.restfulall.model.RequestSpecData

/**
 * v1.3.1 W2-1 - CollectionService 服务层集成测试。
 *
 * 覆盖：CRUD、树形（roots / children / 级联删除 / 防循环 / 深度限制）、
 * Item 增删改 / 跨集合移动、按 routeRef 反查、reconcileDisabled。
 *
 * 数据模型纯逻辑单测见 [io.github.movebrickschi.restfulall.model.CollectionEntryTest]。
 */
class CollectionServiceTest : BasePlatformTestCase() {

    private lateinit var service: CollectionService

    override fun setUp() {
        super.setUp()
        service = CollectionService.getInstance(project)
        service.list().forEach { service.delete(it.id) }
    }

    fun testGetInstanceReturnsProjectScopedSingleton() {
        val a = CollectionService.getInstance(project)
        val b = CollectionService.getInstance(project)
        assertSame(a, b)
    }

    fun testUpsertCreatesAndUpdates() {
        val c = CollectionEntry.newDefault("API")
        service.upsert(c)
        assertEquals(1, service.list().size)

        c.name = "API v2"
        service.upsert(c)
        assertEquals(1, service.list().size)
        assertEquals("API v2", service.findById(c.id)?.name)
    }

    fun testUpsertRejectsBlankName() {
        val c = CollectionEntry.newDefault("   ")
        try {
            service.upsert(c)
            fail("Should reject blank name")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("blank"))
        }
    }

    fun testUpsertTrimsName() {
        val c = CollectionEntry.newDefault("  Trimmed  ")
        service.upsert(c)
        assertEquals("Trimmed", service.findById(c.id)?.name)
    }

    fun testRootsAndChildrenSplit() {
        val parent = CollectionEntry.newDefault("Parent")
        service.upsert(parent)
        val child = CollectionEntry.newDefault("Child").apply { parentId = parent.id }
        service.upsert(child)

        assertEquals(listOf(parent.id), service.roots().map { it.id })
        assertEquals(listOf(child.id), service.children(parent.id).map { it.id })
    }

    fun testUpsertRejectsSelfAsParent() {
        val c = CollectionEntry.newDefault("Self")
        service.upsert(c)
        c.parentId = c.id
        try {
            service.upsert(c)
            fail("Should reject self as parent")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("own parent"))
        }
    }

    fun testUpsertRejectsCycleParent() {
        val a = CollectionEntry.newDefault("A").also(service::upsert)
        val b = CollectionEntry.newDefault("B").apply { parentId = a.id }.also(service::upsert)
        val c = CollectionEntry.newDefault("C").apply { parentId = b.id }.also(service::upsert)

        a.parentId = c.id
        try {
            service.upsert(a)
            fail("Should detect cycle: A -> B -> C -> A")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Cycle"))
        }
    }

    fun testDeleteCascadesDescendants() {
        val a = CollectionEntry.newDefault("A").also(service::upsert)
        val b = CollectionEntry.newDefault("B").apply { parentId = a.id }.also(service::upsert)
        val c = CollectionEntry.newDefault("C").apply { parentId = b.id }.also(service::upsert)
        val orphan = CollectionEntry.newDefault("Orphan").also(service::upsert)

        val removed = service.delete(a.id)
        assertEquals(setOf(a.id, b.id, c.id), removed)
        assertEquals(listOf(orphan.id), service.list().map { it.id })
    }

    fun testDeleteUnknownIdReturnsEmpty() {
        assertTrue(service.delete("not-exists").isEmpty())
    }

    fun testAddItemAssignsIncreasingOrder() {
        val c = CollectionEntry.newDefault("API").also(service::upsert)
        service.addItem(c.id, CollectionItem(name = "first"))
        service.addItem(c.id, CollectionItem(name = "second"))

        val items = service.findById(c.id)!!.sortedItems()
        assertEquals(listOf("first", "second"), items.map { it.name })
        assertEquals(listOf(0, 1), items.map { it.order })
    }

    fun testRemoveItemReturnsTrueOnSuccess() {
        val c = CollectionEntry.newDefault("API").also(service::upsert)
        val item = CollectionItem(name = "x")
        service.addItem(c.id, item)

        assertTrue(service.removeItem(item.id))
        assertTrue(service.findById(c.id)!!.items.isEmpty())
        assertFalse(service.removeItem(item.id))
    }

    fun testRenameItemTrimsName() {
        val c = CollectionEntry.newDefault("API").also(service::upsert)
        val item = CollectionItem(name = "old")
        service.addItem(c.id, item)

        assertTrue(service.renameItem(item.id, "  new  "))
        assertEquals("new", service.findById(c.id)!!.items.first().name)
    }

    fun testMoveItemAcrossCollections() {
        val src = CollectionEntry.newDefault("SRC").also(service::upsert)
        val dst = CollectionEntry.newDefault("DST").also(service::upsert)
        val item = CollectionItem(name = "movable")
        service.addItem(src.id, item)

        assertTrue(service.moveItem(item.id, dst.id, 0))
        assertTrue(service.findById(src.id)!!.items.isEmpty())
        assertEquals(listOf("movable"), service.findById(dst.id)!!.items.map { it.name })
    }

    fun testMoveItemSameCollectionReorders() {
        val c = CollectionEntry.newDefault("C").also(service::upsert)
        val a = CollectionItem(name = "a"); service.addItem(c.id, a)
        val b = CollectionItem(name = "b"); service.addItem(c.id, b)
        val cc = CollectionItem(name = "c"); service.addItem(c.id, cc)

        assertTrue(service.moveItem(cc.id, c.id, 0))
        val ordered = service.findById(c.id)!!.sortedItems().map { it.name }
        assertEquals(listOf("c", "a", "b"), ordered)
    }

    fun testFindItemsByRouteRef() {
        val c1 = CollectionEntry.newDefault("C1").also(service::upsert)
        val c2 = CollectionEntry.newDefault("C2").also(service::upsert)
        val ref = "GET:/api/users:UserController#list"
        service.addItem(c1.id, CollectionItem(name = "a", routeRef = ref))
        service.addItem(c2.id, CollectionItem(name = "b", routeRef = ref))
        service.addItem(c2.id, CollectionItem(name = "c", routeRef = "OTHER"))

        val matches = service.findItemsByRouteRef(ref)
        assertEquals(2, matches.size)
        assertEquals(setOf("a", "b"), matches.map { it.second.name }.toSet())
    }

    fun testReconcileDisabledMarksMissingRoutes() {
        val c = CollectionEntry.newDefault("C").also(service::upsert)
        val keep = "GET:/keep:K#k"
        val drop = "GET:/drop:D#d"
        service.addItem(c.id, CollectionItem(name = "keep", routeRef = keep))
        service.addItem(c.id, CollectionItem(name = "drop", routeRef = drop))
        service.addItem(c.id, CollectionItem(name = "standalone"))

        val changed = service.reconcileDisabled(setOf(keep))
        assertEquals(1, changed)
        val items = service.findById(c.id)!!.items.associateBy { it.name }
        assertFalse(items["keep"]!!.disabled)
        assertTrue(items["drop"]!!.disabled)
        assertFalse(items["standalone"]!!.disabled)
    }

    fun testReconcileDisabledRecoversWhenRouteReturns() {
        val c = CollectionEntry.newDefault("C").also(service::upsert)
        val ref = "GET:/x:X#x"
        service.addItem(c.id, CollectionItem(name = "one", routeRef = ref))

        service.reconcileDisabled(emptySet())
        assertTrue(service.findById(c.id)!!.items.first().disabled)

        val recoveredCount = service.reconcileDisabled(setOf(ref))
        assertEquals(1, recoveredCount)
        assertFalse(service.findById(c.id)!!.items.first().disabled)
    }

    fun testUpdateItemSpecMutates() {
        val c = CollectionEntry.newDefault("C").also(service::upsert)
        val item = CollectionItem(
            name = "x",
            spec = RequestSpecData(method = "GET", url = "http://a"),
        )
        service.addItem(c.id, item)

        assertTrue(service.updateItemSpec(item.id) {
            it.spec.method = "POST"
            it.spec.url = "http://b"
        })
        val mutated = service.findById(c.id)!!.items.first().spec
        assertEquals("POST", mutated.method)
        assertEquals("http://b", mutated.url)
    }
}
