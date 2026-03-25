package io.github.movebrickschi.restfulall.scanner

object CommentFilter {

    // Returns a BooleanArray where true = line is inside a comment.
    // Handles single-line (// , #) and block comments.
    fun buildCommentMap(lines: List<String>, language: Language): BooleanArray {
        val map = BooleanArray(lines.size)
        var inBlock = false

        for ((i, line) in lines.withIndex()) {
            val trimmed = line.trim()

            if (inBlock) {
                map[i] = true
                if (language == Language.C_STYLE && trimmed.contains("*/")) {
                    inBlock = false
                }
                continue
            }

            when (language) {
                Language.C_STYLE -> {
                    when {
                        trimmed.startsWith("//") -> map[i] = true
                        trimmed.startsWith("/*") -> {
                            map[i] = true
                            if (!trimmed.contains("*/")) inBlock = true
                        }
                    }
                }
                Language.PYTHON -> {
                    if (trimmed.startsWith("#")) map[i] = true
                }
            }
        }

        return map
    }

    enum class Language {
        C_STYLE,
        PYTHON,
    }
}
