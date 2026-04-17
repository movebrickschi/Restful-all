package io.github.movebrickschi.restfulall.service

import com.intellij.psi.*
import com.intellij.psi.util.PsiUtil

object TestValueGenerator {

    private const val MAX_DEPTH = 3

    fun generateTestValue(type: PsiType): String {
        val fqn = type.canonicalText
        return generateScalar(fqn) ?: "test"
    }

    fun generateJson(type: PsiType): String {
        return generateJsonValue(type, 0)
    }

    private fun generateScalar(fqn: String): String? {
        return when (fqn) {
            "java.lang.String", "String" -> "test"
            "int", "java.lang.Integer" -> "1"
            "long", "java.lang.Long" -> "1"
            "boolean", "java.lang.Boolean" -> "true"
            "double", "java.lang.Double" -> "1.0"
            "float", "java.lang.Float" -> "1.0"
            "short", "java.lang.Short" -> "1"
            "byte", "java.lang.Byte" -> "0"
            "char", "java.lang.Character" -> "a"
            "java.math.BigDecimal" -> "1.00"
            "java.math.BigInteger" -> "1"
            "java.util.Date", "java.time.LocalDate", "java.sql.Date" -> "2024-01-01"
            "java.time.LocalDateTime" -> "2024-01-01T00:00:00"
            "java.time.LocalTime" -> "00:00:00"
            "java.util.UUID" -> "00000000-0000-0000-0000-000000000001"
            else -> null
        }
    }

    private fun generateJsonValue(type: PsiType, depth: Int): String {
        val fqn = type.canonicalText

        // Scalar types
        val scalar = generateScalar(fqn)
        if (scalar != null) {
            return if (isJsonQuotedType(fqn)) "\"$scalar\"" else scalar
        }

        // Array type
        if (type is PsiArrayType) {
            val componentValue = generateJsonValue(type.componentType, depth)
            return "[$componentValue]"
        }

        // PsiClassType (collections, maps, POJOs)
        if (type is PsiClassType) {
            val resolved = type.resolve()
            if (resolved != null) {
                val resolvedFqn = resolved.qualifiedName ?: ""

                // Enum
                if (resolved.isEnum) {
                    val firstConst = resolved.fields.filterIsInstance<PsiEnumConstant>().firstOrNull()
                    return if (firstConst != null) "\"${firstConst.name}\"" else "\"\""
                }

                // Collection types (List, Set, Collection)
                if (isCollectionType(resolvedFqn)) {
                    val elementType = PsiUtil.extractIterableTypeParameter(type, false)
                    val elementValue = if (elementType != null) generateJsonValue(elementType, depth) else "\"\""
                    return "[$elementValue]"
                }

                // Map types
                if (isMapType(resolvedFqn)) {
                    val typeArgs = type.parameters
                    val valueType = if (typeArgs.size >= 2) typeArgs[1] else null
                    val valueJson = if (valueType != null) generateJsonValue(valueType, depth) else "\"\""
                    return "{\"key\": $valueJson}"
                }
            }

            // POJO - generate object
            if (depth >= MAX_DEPTH) return "{}"
            val psiClass = type.resolve() ?: return "{}"
            return generateJsonObject(psiClass, depth)
        }

        return "\"\""
    }

    private fun generateJsonObject(psiClass: PsiClass, depth: Int): String {
        val fields = psiClass.allFields.filter { field ->
            !field.hasModifierProperty(PsiModifier.STATIC) &&
                !field.hasModifierProperty(PsiModifier.TRANSIENT) &&
                !hasJsonIgnore(field)
        }

        if (fields.isEmpty()) return "{}"

        val indent = "  ".repeat(depth + 1)
        val closingIndent = "  ".repeat(depth)
        val entries = fields.map { field ->
            val key = field.name
            val value = generateJsonValue(field.type, depth + 1)
            "$indent\"$key\": $value"
        }

        return "{\n${entries.joinToString(",\n")}\n$closingIndent}"
    }

    private fun isJsonQuotedType(fqn: String): Boolean {
        return fqn in setOf(
            "java.lang.String", "String",
            "char", "java.lang.Character",
            "java.util.Date", "java.time.LocalDate", "java.sql.Date",
            "java.time.LocalDateTime", "java.time.LocalTime",
            "java.util.UUID",
        )
    }

    private fun isCollectionType(fqn: String): Boolean {
        return fqn in setOf(
            "java.util.List", "java.util.ArrayList", "java.util.LinkedList",
            "java.util.Set", "java.util.HashSet", "java.util.LinkedHashSet", "java.util.TreeSet",
            "java.util.Collection",
        )
    }

    private fun isMapType(fqn: String): Boolean {
        return fqn in setOf(
            "java.util.Map", "java.util.HashMap", "java.util.LinkedHashMap", "java.util.TreeMap",
            "java.util.ConcurrentHashMap",
        )
    }

    private fun hasJsonIgnore(field: PsiField): Boolean {
        return field.annotations.any { annotation ->
            val name = annotation.qualifiedName ?: return@any false
            name == "com.fasterxml.jackson.annotation.JsonIgnore" ||
                name == "org.codehaus.jackson.annotate.JsonIgnore"
        }
    }
}
