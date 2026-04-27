package io.github.movebrickschi.restfulall.ui

import io.github.movebrickschi.restfulall.MyMessageBundle
import io.github.movebrickschi.restfulall.model.RouteInfo
import java.io.File
import java.util.TreeMap
import javax.swing.tree.DefaultMutableTreeNode

sealed class RouteTreeItem {
    data class Group(
        val title: String,
        val count: Int,
        val level: RouteTreeLevel,
    ) : RouteTreeItem()

    data class Leaf(val route: RouteInfo) : RouteTreeItem()
}

enum class RouteTreeLevel {
    ROOT,
    MODULE,
    PACKAGE,
    ROUTE_GROUP,
}

object RouteTreeBuilder {

    fun buildSwingTree(
        routes: List<RouteInfo>,
        projectName: String,
        projectBasePath: String?,
    ): DefaultMutableTreeNode =
        toSwingNode(buildTree(routes, projectName, projectBasePath))

    fun buildTree(
        routes: List<RouteInfo>,
        projectName: String,
        projectBasePath: String?,
    ): RouteTreeNode {
        val root = RouteTreeNode(RouteTreeItem.Group("", routes.size, RouteTreeLevel.ROOT))
        val routesByModule = routes.groupBy { detectModuleName(it.file.path, projectName, projectBasePath) }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)

        for ((moduleName, moduleRoutes) in routesByModule) {
            val moduleNode = RouteTreeNode(RouteTreeItem.Group(moduleName, moduleRoutes.size, RouteTreeLevel.MODULE))
            addPackageNodes(moduleNode, buildPackageTree(moduleRoutes))
            root.children.add(moduleNode)
        }
        return root
    }

    fun toSwingNode(node: RouteTreeNode): DefaultMutableTreeNode {
        val swingNode = DefaultMutableTreeNode(node.item)
        node.children.forEach { swingNode.add(toSwingNode(it)) }
        return swingNode
    }

    fun routeGroupTitle(route: RouteInfo): String =
        route.routeGroupName.ifBlank {
            route.className.ifBlank { route.file.nameWithoutExtension }
        }

    private fun buildPackageTree(routes: List<RouteInfo>): PackageTreeNode {
        val root = PackageTreeNode("")
        val noPackageTitle = MyMessageBundle.message("route.list.tree.no.package")

        routes.forEach { route ->
            val packageSegments = route.packageName
                .split(".")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .ifEmpty { listOf(noPackageTitle) }

            var current = root
            packageSegments.forEach { segment ->
                current = current.children.getOrPut(segment) { PackageTreeNode(segment) }
            }
            current.routes.add(route)
        }

        return root
    }

    private fun addPackageNodes(parentNode: RouteTreeNode, packageRoot: PackageTreeNode) {
        packageRoot.children.values.forEach { addPackageNode(parentNode, it) }
    }

    private fun addPackageNode(parentNode: RouteTreeNode, packageTreeNode: PackageTreeNode) {
        val (title, displayNode) = compressedPackageNode(packageTreeNode)
        val packageNode = RouteTreeNode(
            RouteTreeItem.Group(title, displayNode.totalRouteCount(), RouteTreeLevel.PACKAGE),
        )

        addRouteGroupNodes(packageNode, displayNode.routes)
        displayNode.children.values.forEach { addPackageNode(packageNode, it) }
        parentNode.children.add(packageNode)
    }

    private fun compressedPackageNode(packageTreeNode: PackageTreeNode): Pair<String, PackageTreeNode> {
        val segments = mutableListOf(packageTreeNode.segment)
        var current = packageTreeNode

        while (current.routes.isEmpty() && current.children.size == 1) {
            val child = current.children.values.first()
            segments.add(child.segment)
            current = child
        }

        return segments.joinToString(".") to current
    }

    private fun addRouteGroupNodes(parentNode: RouteTreeNode, routes: List<RouteInfo>) {
        val routesByGroup = routes.groupBy { routeGroupTitle(it) }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
        for ((groupName, groupRoutes) in routesByGroup) {
            val groupNode = RouteTreeNode(RouteTreeItem.Group(groupName, groupRoutes.size, RouteTreeLevel.ROUTE_GROUP))
            groupRoutes.forEach { route ->
                groupNode.children.add(RouteTreeNode(RouteTreeItem.Leaf(route)))
            }
            parentNode.children.add(groupNode)
        }
    }

    private fun detectModuleName(filePath: String, projectName: String, projectBasePath: String?): String {
        val basePath = projectBasePath ?: return projectName
        val normalizedFile = filePath.replace("\\", "/")
        val normalizedBase = basePath.replace("\\", "/")
        if (!normalizedFile.startsWith(normalizedBase)) return projectName

        val relative = normalizedFile.removePrefix(normalizedBase).trimStart('/')
        val parts = relative.split("/")

        for (i in parts.indices) {
            val dir = "$normalizedBase/${parts.take(i + 1).joinToString("/")}"
            val dirFile = File(dir)
            if (dirFile.isDirectory) {
                val hasBuildFile = dirFile.listFiles()?.any {
                    it.name in setOf("build.gradle", "build.gradle.kts", "pom.xml", "package.json")
                } == true
                if (hasBuildFile && dir != normalizedBase) {
                    return parts[i]
                }
            }
        }

        return projectName
    }

    data class RouteTreeNode(
        val item: RouteTreeItem,
        val children: MutableList<RouteTreeNode> = mutableListOf(),
    )

    private class PackageTreeNode(val segment: String) {
        val children: MutableMap<String, PackageTreeNode> = TreeMap(String.CASE_INSENSITIVE_ORDER)
        val routes = mutableListOf<RouteInfo>()

        fun totalRouteCount(): Int =
            routes.size + children.values.sumOf { it.totalRouteCount() }
    }
}
