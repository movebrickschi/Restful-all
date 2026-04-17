package io.github.movebrickschi.restfulall.service

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import io.github.movebrickschi.restfulall.model.*

object SpringPsiParamExtractor {

    private val LOG = Logger.getInstance(SpringPsiParamExtractor::class.java)

    /**
     * 从 Spring Controller 方法中提取参数信息。
     * 必须在 ReadAction 中调用。
     * 返回 null 表示 PSI 解析失败，调用方应回退到默认行为。
     */
    fun extract(project: Project, routeInfo: RouteInfo): ExtractedMethodParams? {
        if (routeInfo.framework != Framework.SPRING) return null

        val psiMethod = findPsiMethod(project, routeInfo)
        if (psiMethod == null) {
            LOG.info("Could not resolve PsiMethod for ${routeInfo.className}#${routeInfo.functionName}")
            return null
        }

        return extractFromMethod(psiMethod)
    }

    // ========== PsiMethod 定位 ==========

    private fun findPsiMethod(project: Project, routeInfo: RouteInfo): PsiMethod? {
        val psiFile = PsiManager.getInstance(project).findFile(routeInfo.file) ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null

        val lineNumber = routeInfo.lineNumber
        if (lineNumber < 0 || lineNumber >= document.lineCount) return null

        // 主策略：从注解行向下搜索 PsiMethod
        for (line in lineNumber..(lineNumber + 15).coerceAtMost(document.lineCount - 1)) {
            val offset = document.getLineStartOffset(line)
            val element = psiFile.findElementAt(offset) ?: continue
            val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
            if (method != null && method.name == routeInfo.functionName) {
                return method
            }
        }

        // 兜底策略：按类名+方法名查找
        return findByClassName(psiFile, routeInfo)
    }

    private fun findByClassName(psiFile: PsiFile, routeInfo: RouteInfo): PsiMethod? {
        val classes = when (psiFile) {
            is PsiJavaFile -> psiFile.classes.toList()
            else -> {
                // Kotlin 文件：尝试通过 PsiTreeUtil 查找所有 PsiClass
                PsiTreeUtil.findChildrenOfType(psiFile, PsiClass::class.java).toList()
            }
        }

        val targetClass = classes.find { it.name == routeInfo.className } ?: return null
        return targetClass.findMethodsByName(routeInfo.functionName, false).firstOrNull()
    }

    // ========== 参数提取 ==========

    private fun extractFromMethod(psiMethod: PsiMethod): ExtractedMethodParams {
        val queryParams = mutableListOf<ExtractedParam>()
        val pathParams = mutableListOf<ExtractedParam>()
        val headerParams = mutableListOf<ExtractedParam>()
        val cookieParams = mutableListOf<ExtractedParam>()
        var bodyJson: String? = null

        for (param in psiMethod.parameterList.parameters) {
            val extracted = extractSingleParam(param) ?: continue

            when (extracted.location) {
                ParamLocation.QUERY -> queryParams.add(extracted)
                ParamLocation.PATH -> pathParams.add(extracted)
                ParamLocation.HEADER -> headerParams.add(extracted)
                ParamLocation.COOKIE -> cookieParams.add(extracted)
                ParamLocation.BODY -> {
                    bodyJson = TestValueGenerator.generateJson(param.type)
                }
            }
        }

        return ExtractedMethodParams(
            queryParams = queryParams,
            pathParams = pathParams,
            headerParams = headerParams,
            cookieParams = cookieParams,
            bodyJson = bodyJson,
        )
    }

    private fun extractSingleParam(param: PsiParameter): ExtractedParam? {
        val type = param.type

        for (annotation in param.annotations) {
            val fqn = annotation.qualifiedName
            val shortName = fqn?.substringAfterLast('.') ?: continue

            when {
                fqn == "org.springframework.web.bind.annotation.RequestParam" || shortName == "RequestParam" -> {
                    val name = resolveParamName(annotation, param)
                    val defaultValue = resolveStringAttribute(annotation, "defaultValue")
                    val testValue = defaultValue ?: TestValueGenerator.generateTestValue(type)
                    return ExtractedParam(name, ParamLocation.QUERY, testValue)
                }

                fqn == "org.springframework.web.bind.annotation.PathVariable" || shortName == "PathVariable" -> {
                    val name = resolveParamName(annotation, param)
                    val testValue = TestValueGenerator.generateTestValue(type)
                    return ExtractedParam(name, ParamLocation.PATH, testValue)
                }

                fqn == "org.springframework.web.bind.annotation.RequestBody" || shortName == "RequestBody" -> {
                    return ExtractedParam(param.name ?: "body", ParamLocation.BODY, "")
                }

                fqn == "org.springframework.web.bind.annotation.RequestHeader" || shortName == "RequestHeader" -> {
                    val name = resolveParamName(annotation, param)
                    val defaultValue = resolveStringAttribute(annotation, "defaultValue")
                    val testValue = defaultValue ?: TestValueGenerator.generateTestValue(type)
                    return ExtractedParam(name, ParamLocation.HEADER, testValue)
                }

                fqn == "org.springframework.web.bind.annotation.CookieValue" || shortName == "CookieValue" -> {
                    val name = resolveParamName(annotation, param)
                    val defaultValue = resolveStringAttribute(annotation, "defaultValue")
                    val testValue = defaultValue ?: TestValueGenerator.generateTestValue(type)
                    return ExtractedParam(name, ParamLocation.COOKIE, testValue)
                }
            }
        }

        // 无注解的简单类型 → Query 参数
        if (isSimpleType(type)) {
            return ExtractedParam(
                param.name ?: "param",
                ParamLocation.QUERY,
                TestValueGenerator.generateTestValue(type),
            )
        }

        // 框架类型（HttpServletRequest 等）→ 跳过
        if (isFrameworkType(type)) return null

        // 未识别的复杂类型 → 跳过
        return null
    }

    // ========== 注解属性解析 ==========

    private fun resolveParamName(annotation: PsiAnnotation, param: PsiParameter): String {
        return resolveStringAttribute(annotation, "value")
            ?: resolveStringAttribute(annotation, "name")
            ?: param.name
            ?: "param"
    }

    private fun resolveStringAttribute(annotation: PsiAnnotation, attribute: String): String? {
        val value = annotation.findAttributeValue(attribute) ?: return null
        val text = when (value) {
            is PsiLiteralExpression -> value.value as? String
            is PsiReferenceExpression -> value.text
            else -> null
        } ?: return null

        // 过滤 Spring 的 ValueConstants.DEFAULT_NONE
        if (text.isBlank() || text.contains('\uE000')) return null
        return text
    }

    // ========== 类型判断 ==========

    private fun isSimpleType(type: PsiType): Boolean {
        val fqn = type.canonicalText
        return fqn in SIMPLE_TYPES
    }

    private fun isFrameworkType(type: PsiType): Boolean {
        val fqn = type.canonicalText
        return FRAMEWORK_TYPE_PREFIXES.any { fqn.startsWith(it) }
    }

    private val SIMPLE_TYPES = setOf(
        "java.lang.String", "String",
        "int", "java.lang.Integer",
        "long", "java.lang.Long",
        "boolean", "java.lang.Boolean",
        "double", "java.lang.Double",
        "float", "java.lang.Float",
        "short", "java.lang.Short",
        "byte", "java.lang.Byte",
        "char", "java.lang.Character",
        "java.math.BigDecimal", "java.math.BigInteger",
        "java.util.Date", "java.time.LocalDate", "java.time.LocalDateTime", "java.time.LocalTime",
        "java.util.UUID",
    )

    private val FRAMEWORK_TYPE_PREFIXES = setOf(
        "javax.servlet.",
        "jakarta.servlet.",
        "org.springframework.web.",
        "org.springframework.ui.",
        "org.springframework.validation.",
        "org.springframework.http.HttpEntity",
        "org.springframework.http.ResponseEntity",
        "java.security.Principal",
    )
}
