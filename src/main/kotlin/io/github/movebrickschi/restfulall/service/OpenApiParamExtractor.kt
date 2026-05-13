package io.github.movebrickschi.restfulall.service

import com.intellij.openapi.project.Project
import io.github.movebrickschi.restfulall.model.ExtractedMethodParams
import io.github.movebrickschi.restfulall.model.RouteInfo
import io.github.movebrickschi.restfulall.model.stableId

/**
 * F4: OpenAPI 导入而来的虚拟 [RouteInfo] 的参数提取器。
 *
 * 与 Spring / NestJS / Express / Python 走源码 PSI 解析不同，OpenAPI 操作在
 * 导入时（[SwaggerImporter.import]）就已经把每个 operation 的参数解析好，
 * 直接以 `stableId -> ExtractedMethodParams` 形式存进 [RouteService.openApiParamsCache]。
 *
 * 本 extractor 唯一职责：按 stableId 反查 cache。
 * cache 未命中即返回 `null`，与其它 extractor 行为保持一致，
 * UI 会跳过 `populateFromExtraction`。
 */
object OpenApiParamExtractor {

    fun extract(project: Project, routeInfo: RouteInfo): ExtractedMethodParams? =
        RouteService.getInstance(project).getImportedParams(routeInfo.stableId)
}
