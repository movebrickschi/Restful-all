package io.github.movebrickschi.restfulall.model

import com.intellij.util.xmlb.annotations.OptionTag
import java.util.UUID

/**
 * v1.3 - 环境变量条目。
 *
 * 用于 F1：环境变量管理 + `${var}` 模板替换。
 * 一个项目可同时拥有多个 EnvironmentEntry（如 dev / test / prod），同一时刻只有一个 active。
 *
 * 含 secret=true 的变量不会落盘到本对象，仅在内存中保留占位；真实值通过
 * SecretStorageService 写入 IntelliJ PasswordSafe，按 `${projectHash}:${envId}:${key}` 索引。
 */
data class EnvironmentEntry(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    @get:OptionTag(tag = "variables")
    var variables: MutableList<EnvVariable> = mutableListOf(),
    var isActive: Boolean = false,
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
) {
    fun touch() {
        updatedAt = System.currentTimeMillis()
    }

    fun activeVariables(): List<EnvVariable> =
        variables.filter { it.enabled && it.key.isNotBlank() }

    fun findVariable(key: String): EnvVariable? =
        variables.firstOrNull { it.enabled && it.key == key }

    companion object {
        const val DEFAULT_ID: String = "default"
        const val DEFAULT_NAME: String = "默认"

        fun newDefault(): EnvironmentEntry = EnvironmentEntry(
            id = DEFAULT_ID,
            name = DEFAULT_NAME,
            isActive = true,
        )
    }
}

/**
 * 环境变量键值对。
 *
 * - `enabled`：被禁用的变量在 `${var}` 替换时按未定义处理（透明降级、保留原文）。
 * - `secret`：标记是否为敏感字段；secret 变量的 `value` 字段不会被持久化为明文，
 *   仅以 `__SECRET_REF__:${ref}` 占位字符串保存，真实值由 SecretStorageService 管理。
 */
data class EnvVariable(
    var enabled: Boolean = true,
    var key: String = "",
    var value: String = "",
    var secret: Boolean = false,
    var description: String = "",
) {
    fun isSecretRef(): Boolean = value.startsWith(SECRET_REF_PREFIX)

    fun secretRefKey(): String? =
        if (isSecretRef()) value.removePrefix(SECRET_REF_PREFIX) else null

    companion object {
        const val SECRET_REF_PREFIX: String = "__SECRET_REF__:"

        fun secretRef(ref: String): String = SECRET_REF_PREFIX + ref
    }
}
