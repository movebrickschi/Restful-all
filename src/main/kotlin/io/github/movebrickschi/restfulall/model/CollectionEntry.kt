package io.github.movebrickschi.restfulall.model

import com.intellij.util.xmlb.annotations.OptionTag
import java.util.UUID

/**
 * v1.3.1 F3 - Collection 集合（树形结构）。
 *
 * 一个 Collection 是一个命名分组，可包含多个 [CollectionItem]，并可作为子节点
 * 挂在另一个 Collection 下（[parentId]）形成树形。删除父 Collection 会级联
 * 删除所有后代（详见 [io.github.movebrickschi.restfulall.service.CollectionService.delete]）。
 *
 * ## 命名规则
 *
 * - 同一父节点下的 Collection 名称**可以重复**（用户场景：两个 dev / test 子分组）
 * - 名称不能为空白；trim 后保存
 *
 * ## 容量上限
 *
 * - 总 Collection 数 ≤ 200（[CollectionService.MAX_COLLECTIONS]）
 * - 单 Collection items ≤ 500（[CollectionService.MAX_ITEMS_PER_COLLECTION]）
 * - 树深度 ≤ 5（[CollectionService.MAX_DEPTH]，避免无限嵌套）
 */
data class CollectionEntry(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var parentId: String? = null,
    @get:OptionTag(tag = "items")
    var items: MutableList<CollectionItem> = mutableListOf(),
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
) {
    fun touch() {
        updatedAt = System.currentTimeMillis()
    }

    fun sortedItems(): List<CollectionItem> = items.sortedBy { it.order }

    fun nextItemOrder(): Int = (items.maxOfOrNull { it.order } ?: -1) + 1

    companion object {
        fun newDefault(name: String = "Default Collection"): CollectionEntry =
            CollectionEntry(name = name)
    }
}

/**
 * v1.3.1 F3 - Collection 内的请求条目。
 *
 * - [routeRef]：源码路由的 [RouteInfo.stableId]；null 表示纯独立请求（不绑定源码）。
 * - [spec]：完整请求快照（[RequestSpecData]，可序列化）。
 * - [disabled]：源码路由被删除时由 CollectionService 标记（验收 ②：标灰 + 提示）。
 *   用户重命名后修改 [name] 不影响 [routeRef] 与源码路由的绑定关系。
 */
data class CollectionItem(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var routeRef: String? = null,
    @get:OptionTag(tag = "spec")
    var spec: RequestSpecData = RequestSpecData(),
    var order: Int = 0,
    var note: String = "",
    var disabled: Boolean = false,
)
