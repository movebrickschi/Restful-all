package io.github.movebrickschi.restfulall.service

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import io.github.movebrickschi.restfulall.model.CollectionEntry
import io.github.movebrickschi.restfulall.model.CollectionItem

/**
 * v1.3.1 F3 - Collection 集合组织。
 *
 * 管理用户自定义的 [CollectionEntry] 树（最多 [MAX_DEPTH] 层），以及每个 Collection
 * 内的 [CollectionItem]（最多 [MAX_ITEMS_PER_COLLECTION] 条）。持久化到项目级 XML：
 * `.idea/restful-all-collections.xml`。
 *
 * ## 设计要点
 *
 * - **Project 级**：每个项目独立保存自己的 Collection；多项目互不影响
 * - **不依赖源码路由**：删除源码路由仅触发 [markItemsDisabledByMissingRoutes] 标灰，
 *   Collection 数据本身保留，用户可手动清理或重新关联
 * - **重命名隔离**：[CollectionItem.name] 与 [CollectionItem.routeRef] 解耦，
 *   用户在 Collection 中改名不影响源码路由的真实路径展示
 * - **Order 管理**：新增 item 时自动取当前最大 order + 1，避免冲突；
 *   [moveItem] 重排时按目标 order 插入并重排相邻 item
 *
 * ## 线程模型
 *
 * 写操作（upsert / delete / addItem / moveItem 等）期望在 EDT 上调用；
 * 读操作（list / findById / findItemsByRouteRef）可在任意线程，
 * 内部 `toList()` 拷贝避免并发遍历问题。
 */
@Service(Service.Level.PROJECT)
@State(
    name = "RestfulAll.Collections",
    storages = [Storage("restful-all-collections.xml")],
)
class CollectionService(@Suppress("unused") private val project: Project) :
    PersistentStateComponent<CollectionService.CollectionState> {

    data class CollectionState(
        var collections: MutableList<CollectionEntry> = mutableListOf(),
    )

    @Volatile
    private var myState: CollectionState = CollectionState()

    companion object {
        const val MAX_COLLECTIONS: Int = 200
        const val MAX_ITEMS_PER_COLLECTION: Int = 500
        const val MAX_DEPTH: Int = 5

        fun getInstance(project: Project): CollectionService =
            project.getService(CollectionService::class.java)
    }

    override fun getState(): CollectionState = myState

    override fun loadState(state: CollectionState) {
        try {
            myState = state
            val knownIds = state.collections.map { it.id }.toSet()
            val danglingParents = state.collections
                .mapNotNull { it.parentId }
                .filter { it !in knownIds }
            if (danglingParents.isNotEmpty()) {
                thisLogger().warn(
                    "CollectionService.loadState: ${danglingParents.size} collection(s) reference " +
                        "missing parentId; treating them as roots in this session. parents=$danglingParents",
                )
                state.collections.forEach { coll ->
                    if (coll.parentId != null && coll.parentId !in knownIds) {
                        coll.parentId = null
                    }
                }
            }
        } catch (e: Exception) {
            thisLogger().error(
                "Failed to load CollectionService state; resetting to empty list.",
                e,
            )
            myState = CollectionState()
        }
    }

    fun list(): List<CollectionEntry> = myState.collections.toList()

    fun roots(): List<CollectionEntry> = list().filter { it.parentId == null }

    fun children(parentId: String): List<CollectionEntry> =
        list().filter { it.parentId == parentId }

    fun findById(id: String): CollectionEntry? =
        myState.collections.firstOrNull { it.id == id }

    /**
     * 创建或更新 Collection。
     *
     * - 名称会被 trim；trim 后为空抛 [IllegalArgumentException]
     * - 若 [CollectionEntry.parentId] 不为 null，必须指向已存在的 Collection
     * - 修改 parentId 时检测循环（不能成为自己的子孙）
     * - 创建时若总数已达 [MAX_COLLECTIONS] 抛 [IllegalStateException]
     * - 单 Collection 的 items 超过 [MAX_ITEMS_PER_COLLECTION] 抛 [IllegalStateException]
     */
    fun upsert(entry: CollectionEntry) {
        val trimmed = entry.name.trim()
        require(trimmed.isNotEmpty()) { "Collection name must not be blank" }
        check(entry.items.size <= MAX_ITEMS_PER_COLLECTION) {
            "Too many items in collection (max=$MAX_ITEMS_PER_COLLECTION)"
        }
        entry.name = trimmed

        entry.parentId?.let { pid ->
            require(pid != entry.id) { "Collection cannot be its own parent" }
            requireNotNull(findById(pid)) { "Parent collection not found: $pid" }
            require(!isDescendantOf(pid, entry.id)) {
                "Cycle detected: parent $pid is a descendant of ${entry.id}"
            }
            require(parentDepth(pid) + 1 < MAX_DEPTH) {
                "Tree depth exceeds max=$MAX_DEPTH"
            }
        }

        val idx = myState.collections.indexOfFirst { it.id == entry.id }
        if (idx >= 0) {
            entry.touch()
            myState.collections[idx] = entry
        } else {
            check(myState.collections.size < MAX_COLLECTIONS) {
                "Too many collections (max=$MAX_COLLECTIONS)"
            }
            myState.collections.add(entry)
        }
    }

    /**
     * 删除 Collection 及其所有后代。返回被删除的 Collection ID 集合（含自身）。
     *
     * 不存在的 ID 返回空集（idempotent）。
     */
    fun delete(id: String): Set<String> {
        if (myState.collections.none { it.id == id }) return emptySet()
        val toRemove = collectDescendants(id) + id
        myState.collections.removeIf { it.id in toRemove }
        return toRemove
    }

    fun addItem(collectionId: String, item: CollectionItem) {
        val coll = findById(collectionId)
            ?: throw IllegalArgumentException("Collection not found: $collectionId")
        check(coll.items.size < MAX_ITEMS_PER_COLLECTION) {
            "Items in collection ${coll.name} exceed $MAX_ITEMS_PER_COLLECTION"
        }
        item.name = item.name.trim().ifEmpty { "Untitled" }
        item.order = coll.nextItemOrder()
        coll.items.add(item)
        coll.touch()
    }

    fun removeItem(itemId: String): Boolean {
        for (coll in myState.collections) {
            val removed = coll.items.removeIf { it.id == itemId }
            if (removed) {
                coll.touch()
                return true
            }
        }
        return false
    }

    fun renameItem(itemId: String, newName: String): Boolean {
        val trimmed = newName.trim()
        require(trimmed.isNotEmpty()) { "Item name must not be blank" }
        val (coll, item) = findItem(itemId) ?: return false
        item.name = trimmed
        coll.touch()
        return true
    }

    fun updateItemSpec(itemId: String, mutate: (CollectionItem) -> Unit): Boolean {
        val (coll, item) = findItem(itemId) ?: return false
        mutate(item)
        coll.touch()
        return true
    }

    /**
     * 在 Collection 之间移动 item，并按 [targetOrder] 插入。
     *
     * - 同 collection 内重排：仅改 order
     * - 跨 collection：从源 collection 移除，加入目标 collection 的 items 列表
     * - 移动后整体 order 重新归一为 0..N-1（保持稳定）
     *
     * @return true = 移动成功；false = item 或目标 collection 不存在
     */
    fun moveItem(itemId: String, targetCollectionId: String, targetOrder: Int): Boolean {
        val target = findById(targetCollectionId) ?: return false
        val (sourceColl, item) = findItem(itemId) ?: return false

        if (sourceColl.id == targetCollectionId) {
            val sorted = sourceColl.items.sortedBy { it.order }.toMutableList()
            sorted.remove(item)
            val clamped = targetOrder.coerceIn(0, sorted.size)
            sorted.add(clamped, item)
            replaceWithCompacted(sourceColl, sorted)
        } else {
            check(target.items.size < MAX_ITEMS_PER_COLLECTION) {
                "Target collection items exceed $MAX_ITEMS_PER_COLLECTION"
            }
            sourceColl.items.removeIf { it.id == itemId }
            compactOrder(sourceColl)
            sourceColl.touch()

            val sorted = target.items.sortedBy { it.order }.toMutableList()
            val clamped = targetOrder.coerceIn(0, sorted.size)
            sorted.add(clamped, item)
            replaceWithCompacted(target, sorted)
        }
        return true
    }

    /**
     * 按当前 order 重排并紧凑编号为 0..n-1，避免 holes。
     * 提取为内部 helper 消除 [moveItem] 内的重复段。
     */
    private fun compactOrder(coll: CollectionEntry) {
        coll.items.sortedBy { it.order }.forEachIndexed { idx, it -> it.order = idx }
    }

    private fun replaceWithCompacted(coll: CollectionEntry, sortedItems: MutableList<CollectionItem>) {
        sortedItems.forEachIndexed { idx, it -> it.order = idx }
        coll.items.clear()
        coll.items.addAll(sortedItems)
        coll.touch()
    }

    fun findItemsByRouteRef(routeRef: String): List<Pair<CollectionEntry, CollectionItem>> =
        list().flatMap { coll ->
            coll.items.filter { it.routeRef == routeRef }.map { coll to it }
        }

    /**
     * F3 验收 ②：删除源码路由后 collection 内对应项标灰 + 提示。
     *
     * 由路由扫描完成后的回调（[RouteScanStartupActivity] 或 RouteService.onRoutesUpdated）
     * 调用，传入当前所有有效路由的 stableId 集合；本服务把不在该集合内的 [CollectionItem]
     * 全部 `disabled = true`，并对重新出现的项恢复 `disabled = false`（用户改名 / git 切分支
     * 后路由回归的场景）。
     *
     * @return 状态发生变化的 item 数量
     */
    fun reconcileDisabled(validRouteRefs: Set<String>): Int {
        var changed = 0
        for (coll in myState.collections) {
            var collTouched = false
            for (item in coll.items) {
                val ref = item.routeRef ?: continue
                val shouldBeDisabled = ref !in validRouteRefs
                if (item.disabled != shouldBeDisabled) {
                    item.disabled = shouldBeDisabled
                    changed++
                    collTouched = true
                }
            }
            if (collTouched) coll.touch()
        }
        if (changed > 0) {
            thisLogger().info("CollectionService.reconcileDisabled: $changed items toggled")
        }
        return changed
    }

    private fun findItem(itemId: String): Pair<CollectionEntry, CollectionItem>? {
        for (coll in myState.collections) {
            val item = coll.items.firstOrNull { it.id == itemId } ?: continue
            return coll to item
        }
        return null
    }

    private fun collectDescendants(parentId: String): Set<String> {
        val result = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(parentId)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            for (c in myState.collections) {
                if (c.parentId == cur && result.add(c.id)) queue.add(c.id)
            }
        }
        return result
    }

    private fun isDescendantOf(candidateChildId: String, ancestorId: String): Boolean {
        var cursor: String? = candidateChildId
        var depth = 0
        while (cursor != null && depth <= MAX_DEPTH) {
            if (cursor == ancestorId) return true
            cursor = findById(cursor)?.parentId
            depth++
        }
        return false
    }

    private fun parentDepth(parentId: String): Int {
        var cursor: String? = parentId
        var depth = 0
        while (cursor != null && depth <= MAX_DEPTH) {
            depth++
            cursor = findById(cursor)?.parentId
        }
        return depth
    }
}
