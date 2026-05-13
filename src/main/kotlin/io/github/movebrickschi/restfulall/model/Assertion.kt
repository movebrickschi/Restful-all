package io.github.movebrickschi.restfulall.model

import java.util.UUID

/**
 * F7: 单条响应断言。
 *
 * 设计取舍：
 * - 字段全部为 `var` 是为了适配 IntelliJ XmlSerializer 的反射读写；并不意味着鼓励运行时修改
 *   单条 Assertion；UI 改动应通过创建新实例 + 整体替换列表的方式
 * - [expected] 是字符串而非泛型 Any，因为最常见的 JSON 标量（string / number / boolean）
 *   都可序列化为字符串；引擎 evaluate 时按 [Operator] 做必要的数值/布尔转换
 *
 * 持久化由 [io.github.movebrickschi.restfulall.model.CollectionItem] 内嵌的列表承载（F7 + F3
 * 集成；目前 CollectionItem 暂未挂 Assertion 字段，待 UI 完成后再迁移）
 */
data class Assertion(
    var id: String = UUID.randomUUID().toString(),
    var enabled: Boolean = true,
    var source: Source = Source.JSON_PATH,
    var expression: String = "",
    var operator: Operator = Operator.EQUALS,
    var expected: String = "",
) {

    /** 断言取值的来源。 */
    enum class Source {
        /** JSON Path 表达式，如 `$.code` / `$.data.users[0].name` */
        JSON_PATH,

        /** HTTP 状态码本身 */
        STATUS_CODE,

        /** 响应耗时（毫秒）；与 GREATER_THAN / LESS_THAN 等数值算子配合 */
        RESPONSE_TIME_MS,

        /** Header 名 → [expression] 写 header 名（大小写不敏感） */
        HEADER,
    }

    /** 断言算子。EXISTS / NOT_EXISTS 不消费 [expected]；其它算子比较 actual vs expected。 */
    enum class Operator {
        EQUALS,
        NOT_EQUALS,
        CONTAINS,
        NOT_CONTAINS,
        EXISTS,
        NOT_EXISTS,
        GREATER_THAN,
        LESS_THAN,
        MATCHES_REGEX,
    }
}

/**
 * 单条断言执行结果。
 * - [passed]：true = 通过；false = 失败
 * - [actual]：实际取到的字符串值（null = 取值失败 / 路径不存在）
 * - [error]：执行异常的可读消息；passed=true 时为 null
 */
data class AssertionResult(
    val assertion: Assertion,
    val passed: Boolean,
    val actual: String?,
    val error: String? = null,
)
