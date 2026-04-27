package io.github.movebrickschi.restfulall.service

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtil
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
        val parameters = psiMethod.parameterList.parameters

        // 第一遍：检测是否存在文件参数
        val hasFile = parameters.any { isFileType(it.type) }

        val base = if (hasFile) extractAsFormData(parameters) else extractRegular(parameters)
        val responseJson = extractReturnJson(psiMethod)
        return base.copy(responseJson = responseJson)
    }

    private fun extractReturnJson(psiMethod: PsiMethod): String? {
        val returnType = psiMethod.returnType ?: return null
        val inner = unwrapWrapperType(returnType) ?: return null
        val fqn = inner.canonicalText
        if (fqn == "void" || fqn == "kotlin.Unit" || fqn == "java.lang.Void") return null
        return TestValueGenerator.generateJson(inner)
    }

    /**
     * 剥离 ResponseEntity<T>、Mono<T>、Flux<T> 等外壳，返回内部类型。
     * 若外壳内是 Void/Unit 则返回 null（表示无响应体）。
     * 若类型本身不是包装类则直接返回原类型。
     */
    private fun unwrapWrapperType(type: PsiType): PsiType? {
        if (type is PsiClassType) {
            val resolved = type.resolve()
            val fqn = resolved?.qualifiedName ?: return type
            if (fqn in WRAPPER_TYPE_FQNS) {
                val typeArgs = type.parameters
                if (typeArgs.isEmpty()) return null
                val inner = typeArgs[0]
                val innerFqn = inner.canonicalText
                if (innerFqn == "java.lang.Void" || innerFqn == "kotlin.Unit" || innerFqn == "void") return null
                return inner
            }
        }
        return type
    }

    private val WRAPPER_TYPE_FQNS = setOf(
        "org.springframework.http.ResponseEntity",
        "org.springframework.http.HttpEntity",
        "reactor.core.publisher.Mono",
        "reactor.core.publisher.Flux",
        "java.util.concurrent.CompletableFuture",
        "java.util.concurrent.Future",
        "org.springframework.web.context.request.async.DeferredResult",
    )

    /**
     * 普通模式：保持现有 query/path/header/cookie/body 划分。
     */
    private fun extractRegular(parameters: Array<PsiParameter>): ExtractedMethodParams {
        val queryParams = mutableListOf<ExtractedParam>()
        val pathParams = mutableListOf<ExtractedParam>()
        val headerParams = mutableListOf<ExtractedParam>()
        val cookieParams = mutableListOf<ExtractedParam>()
        var bodyJson: String? = null

        for (param in parameters) {
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

    /**
     * 存在文件参数时：所有非 path/header/cookie 参数统一进 form-data。
     * - 文件类型 → FILE
     * - @RequestParam / @RequestPart / 简单类型无注解 → TEXT
     * - @PathVariable / @RequestHeader / @CookieValue → 各自原位置
     * - 框架类型（HttpServletRequest 等）跳过
     */
    private fun extractAsFormData(parameters: Array<PsiParameter>): ExtractedMethodParams {
        val pathParams = mutableListOf<ExtractedParam>()
        val headerParams = mutableListOf<ExtractedParam>()
        val cookieParams = mutableListOf<ExtractedParam>()
        val formParams = mutableListOf<ExtractedFormParam>()

        for (param in parameters) {
            val type = param.type

            // 文件类型直接进 form-data
            if (isFileType(type)) {
                val name = resolveFormFieldName(param)
                formParams.add(ExtractedFormParam(name, FormFieldType.FILE, ""))
                continue
            }

            // 框架类型跳过
            if (isFrameworkType(type)) continue

            // 注解分流
            val annotationRoute = routeByAnnotation(param)
            if (annotationRoute != null) {
                when (annotationRoute.location) {
                    ParamLocation.PATH -> pathParams.add(annotationRoute)
                    ParamLocation.HEADER -> headerParams.add(annotationRoute)
                    ParamLocation.COOKIE -> cookieParams.add(annotationRoute)
                    ParamLocation.QUERY,
                    ParamLocation.BODY -> formParams.add(
                        ExtractedFormParam(annotationRoute.name, FormFieldType.TEXT, annotationRoute.testValue)
                    )
                }
                continue
            }

            // 无注解：简单类型 → form text；复杂类型在 multipart 上下文里跳过
            if (isSimpleType(type)) {
                val name = param.name ?: "param"
                formParams.add(
                    ExtractedFormParam(name, FormFieldType.TEXT, TestValueGenerator.generateTestValue(type))
                )
            }
        }

        return ExtractedMethodParams(
            pathParams = pathParams,
            headerParams = headerParams,
            cookieParams = cookieParams,
            formParams = formParams,
        )
    }

    private fun routeByAnnotation(param: PsiParameter): ExtractedParam? {
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

                fqn == "org.springframework.web.bind.annotation.RequestPart" || shortName == "RequestPart" -> {
                    val name = resolveParamName(annotation, param)
                    val testValue = if (isSimpleType(type)) TestValueGenerator.generateTestValue(type) else ""
                    return ExtractedParam(name, ParamLocation.QUERY, testValue) // 在 form-data 模式下统一作为 text
                }

                fqn == "org.springframework.web.bind.annotation.PathVariable" || shortName == "PathVariable" -> {
                    val name = resolveParamName(annotation, param)
                    return ExtractedParam(name, ParamLocation.PATH, TestValueGenerator.generateTestValue(type))
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
        return null
    }

    private fun extractSingleParam(param: PsiParameter): ExtractedParam? {
        val type = param.type

        val annotated = routeByAnnotation(param)
        if (annotated != null) return annotated

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

    /**
     * 解析 form-data 字段名：优先取 @RequestParam/@RequestPart 的 value/name，否则取参数名。
     */
    private fun resolveFormFieldName(param: PsiParameter): String {
        for (annotation in param.annotations) {
            val fqn = annotation.qualifiedName ?: continue
            if (fqn == "org.springframework.web.bind.annotation.RequestParam" ||
                fqn == "org.springframework.web.bind.annotation.RequestPart" ||
                fqn.endsWith(".RequestParam") || fqn.endsWith(".RequestPart")
            ) {
                resolveStringAttribute(annotation, "value")?.let { return it }
                resolveStringAttribute(annotation, "name")?.let { return it }
            }
        }
        return param.name ?: "file"
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
        return FRAMEWORK_TYPE_PREFIXES.any { fqn.startsWith(it) } || fqn in FRAMEWORK_TYPE_FQNS
    }

    /**
     * 判断是否为文件参数类型，支持单值、数组、Collection 容器。
     */
    private fun isFileType(type: PsiType): Boolean {
        val raw = unwrapContainer(type)
        val fqn = raw.canonicalText
        if (fqn == "byte[]" || fqn == "java.lang.Byte[]") return true
        return fqn in FILE_TYPE_FQNS
    }

    private fun unwrapContainer(type: PsiType): PsiType {
        if (type is PsiArrayType) return type.componentType
        if (type is PsiClassType) {
            val resolved = type.resolve()
            val resolvedFqn = resolved?.qualifiedName
            if (resolvedFqn != null && resolvedFqn in COLLECTION_FQNS) {
                val elementType = PsiUtil.extractIterableTypeParameter(type, false)
                if (elementType != null) return elementType
            }
        }
        return type
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

    private val FILE_TYPE_FQNS = setOf(
        "org.springframework.web.multipart.MultipartFile",
        "org.springframework.http.codec.multipart.FilePart",
        "org.springframework.http.codec.multipart.Part",
        "jakarta.servlet.http.Part",
        "javax.servlet.http.Part",
        "java.io.File",
    )

    private val COLLECTION_FQNS = setOf(
        "java.util.List", "java.util.ArrayList", "java.util.LinkedList",
        "java.util.Set", "java.util.HashSet", "java.util.LinkedHashSet", "java.util.TreeSet",
        "java.util.Collection", "java.lang.Iterable",
    )

    // 精确化框架类型前缀，去掉过宽的 "org.springframework.web."，避免误杀 MultipartFile
    private val FRAMEWORK_TYPE_PREFIXES = setOf(
        "javax.servlet.",
        "jakarta.servlet.",
        "org.springframework.web.context.",
        "org.springframework.web.server.",
        "org.springframework.web.util.",
        "org.springframework.ui.",
        "org.springframework.validation.",
        "org.springframework.http.HttpEntity",
        "org.springframework.http.RequestEntity",
        "org.springframework.http.ResponseEntity",
        "org.springframework.session.",
        "java.security.Principal",
    )

    private val FRAMEWORK_TYPE_FQNS = setOf(
        "org.springframework.web.context.request.WebRequest",
        "org.springframework.web.context.request.NativeWebRequest",
        "org.springframework.web.context.request.async.DeferredResult",
        "org.springframework.web.context.request.async.WebAsyncTask",
    )
}
