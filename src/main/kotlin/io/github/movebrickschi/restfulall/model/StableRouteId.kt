package io.github.movebrickschi.restfulall.model

/**
 * 稳定的路由标识，不依赖文件路径和行号。
 * 格式：METHOD:fullPath:ClassName#functionName
 *
 * 选择这个粒度的原因：
 * - 不含 file path / lineNumber：重构移动文件、代码行上下移动都不会丢数据
 * - 含 className#functionName：能区分同路径不同实现（如多环境分包）
 *
 * 对于 [io.github.movebrickschi.restfulall.service.PluginSettingsState.userRouteMeta]
 * 中的 `routeKey`，继续沿用 [UserRouteMeta.keyOf]（method:fullPath）以保持向后兼容。
 * [stableId] 是更严格的唯一键，用于 stats/report 等新模块的聚合。
 */
val RouteInfo.stableId: String
    get() = "${method.displayName}:$displayPath:$className#$functionName"

/**
 * 纯路由维度的 key（不含类/方法），用于收藏/备注等"以路径为中心"的场景。
 * 与 [UserRouteMeta.keyOf] 行为一致。
 */
val RouteInfo.routeKey: String
    get() = UserRouteMeta.keyOf(this)
