package io.github.movebrickschi.restfulall.ui

import io.github.movebrickschi.restfulall.export.ApiDocumentFormat
import io.github.movebrickschi.restfulall.export.ApiDocumentOptions
import io.github.movebrickschi.restfulall.model.RouteInfo
import io.github.movebrickschi.restfulall.service.PluginSettingsState

class ApiDocumentExportSelectionModel(
    private val routes: List<RouteInfo>,
) {
    private val selectedRoutes = routes.associateWith { true }.toMutableMap()

    val totalCount: Int
        get() = routes.size

    val selectedCount: Int
        get() = routes.count { selectedRoutes[it] == true }

    fun selectedRoutes(): List<RouteInfo> =
        routes.filter { selectedRoutes[it] == true }

    fun visibleRoutes(query: String): List<RouteInfo> {
        val normalized = query.trim().lowercase()
        if (normalized.isBlank()) return routes
        return routes.filter { it.searchKey.contains(normalized) }
    }

    fun isSelected(route: RouteInfo): Boolean =
        selectedRoutes[route] == true

    fun setSelected(route: RouteInfo, selected: Boolean) {
        if (route in selectedRoutes) {
            selectedRoutes[route] = selected
        }
    }

    fun selectAll() {
        routes.forEach { selectedRoutes[it] = true }
    }

    fun selectNone() {
        routes.forEach { selectedRoutes[it] = false }
    }

    fun selectVisible(query: String) {
        visibleRoutes(query).forEach { selectedRoutes[it] = true }
    }

    fun invertVisible(query: String) {
        visibleRoutes(query).forEach { route ->
            selectedRoutes[route] = selectedRoutes[route] != true
        }
    }

    companion object {
        fun persistOptions(
            state: PluginSettingsState.State,
            format: ApiDocumentFormat,
            options: ApiDocumentOptions,
        ) {
            state.lastExportFormat = format.name
            state.lastExportTitle = options.title
            state.lastExportVersion = options.version
            state.lastExportDescription = options.description
        }
    }
}
