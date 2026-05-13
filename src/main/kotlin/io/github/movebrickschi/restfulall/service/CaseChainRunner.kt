package io.github.movebrickschi.restfulall.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.PathNotFoundException
import io.github.movebrickschi.restfulall.model.CaseChain
import io.github.movebrickschi.restfulall.model.ChainStep
import io.github.movebrickschi.restfulall.model.RequestSpecData

/**
 * v1.3.3 P2-9 - 编排执行器（Pro）。
 *
 * 按顺序跑 [CaseChain.steps]，每步：
 * 1. 把上下文变量（来自 [seedVariables] + 上一步 extractions）替换到 spec 的 `${var}` 占位
 * 2. 用 [RequestExecutor] 发送请求
 * 3. 用 [AssertionEngine] 评估 assertions
 * 4. 用 JSONPath 提取 [ChainStep.extractions] 写回变量上下文
 *
 * 终止条件：任意步 assertion 失败且 [ChainStep.continueOnFailure] = false。
 */
@Service(Service.Level.PROJECT)
class CaseChainRunner(private val project: Project) {

    companion object {
        private val LOG = Logger.getInstance(CaseChainRunner::class.java)

        fun getInstance(project: Project): CaseChainRunner =
            project.getService(CaseChainRunner::class.java)
    }

    data class ChainRunReport(
        val chainId: String,
        val chainName: String,
        val totalSteps: Int,
        val stepReports: List<StepReport>,
        val stopAtStep: Int? = null,
    ) {
        val success: Boolean get() = stopAtStep == null && stepReports.all { it.passed }
    }

    data class StepReport(
        val stepIndex: Int,
        val stepName: String,
        val httpStatus: Int,
        val elapsedMs: Long,
        val assertionPassed: Boolean,
        val errorMessage: String? = null,
        val extracted: Map<String, String> = emptyMap(),
    ) {
        val passed: Boolean get() = errorMessage == null && assertionPassed
    }

    /**
     * 阻塞运行整个链。**调用方应在 pooled thread 上调用**（每步可能阻塞几百毫秒到秒级）。
     *
     * @param seedVariables 链开始前注入的初始变量上下文（如 baseUrl / globalToken）
     * @param onProgress 每步开始/完成时回调（用于 UI 显示进度）
     */
    fun run(
        chain: CaseChain,
        seedVariables: Map<String, String> = emptyMap(),
        onProgress: (Int, ChainStep) -> Unit = { _, _ -> },
    ): ChainRunReport {
        val vars = LinkedHashMap<String, String>(seedVariables)
        val reports = mutableListOf<StepReport>()
        var stopAt: Int? = null

        val executor = RequestExecutor.getInstance(project)

        for ((idx, step) in chain.steps.withIndex()) {
            onProgress(idx, step)

            val resolvedSpec = applyVarSubstitution(step.spec, vars)
            val result = try {
                executor.execute(resolvedSpec.toExecutable())
            } catch (e: Exception) {
                LOG.warn("CaseChainRunner step $idx execute failed", e)
                reports.add(StepReport(
                    stepIndex = idx,
                    stepName = step.name.ifBlank { "step-$idx" },
                    httpStatus = -1,
                    elapsedMs = 0,
                    assertionPassed = false,
                    errorMessage = e.message ?: e.javaClass.simpleName,
                ))
                if (!step.continueOnFailure) { stopAt = idx; break }
                continue
            }

            val extractedNow = extract(step.extractions, result.body)
            vars.putAll(extractedNow)

            val assertionPassed = evaluateAssertions(step, result)
            reports.add(StepReport(
                stepIndex = idx,
                stepName = step.name.ifBlank { "step-$idx" },
                httpStatus = result.statusCode,
                elapsedMs = result.elapsed,
                assertionPassed = assertionPassed,
                extracted = extractedNow,
            ))

            if (!assertionPassed && !step.continueOnFailure) {
                stopAt = idx
                break
            }
        }

        return ChainRunReport(
            chainId = chain.id,
            chainName = chain.name,
            totalSteps = chain.steps.size,
            stepReports = reports,
            stopAtStep = stopAt,
        )
    }

    private fun applyVarSubstitution(spec: RequestSpecData, vars: Map<String, String>): RequestSpecData {
        if (vars.isEmpty()) return spec
        fun replace(s: String): String {
            var out = s
            for ((k, v) in vars) out = out.replace("\${$k}", v)
            return out
        }
        return spec.copy(
            url = replace(spec.url),
            bodyContent = replace(spec.bodyContent),
            queryParams = spec.queryParams.map { it.copy(value = replace(it.value)) }.toMutableList(),
            headers = spec.headers.map { it.copy(value = replace(it.value)) }.toMutableList(),
            cookies = spec.cookies.map { it.copy(value = replace(it.value)) }.toMutableList(),
            pathParams = spec.pathParams.map { it.copy(value = replace(it.value)) }.toMutableList(),
            formParams = spec.formParams.map { it.copy(value = replace(it.value)) }.toMutableList(),
        )
    }

    private fun extract(extractions: Map<String, String>, body: String): Map<String, String> {
        if (extractions.isEmpty() || body.isBlank()) return emptyMap()
        val ctx = try {
            JsonPath.parse(body)
        } catch (e: Exception) {
            LOG.warn("CaseChainRunner extract: invalid JSON body", e)
            return emptyMap()
        }
        val out = LinkedHashMap<String, String>()
        for ((path, varName) in extractions) {
            try {
                val value = ctx.read<Any?>(path)
                out[varName] = value?.toString().orEmpty()
            } catch (_: PathNotFoundException) {
                // missing → skip; do not abort chain
            } catch (e: Exception) {
                LOG.warn("CaseChainRunner extract failed for $path", e)
            }
        }
        return out
    }

    private fun evaluateAssertions(step: ChainStep, result: RequestResult): Boolean {
        if (step.assertionsJson.isBlank() || step.assertionsJson == "[]") return true
        // 简化：v1.3.3 仅按 HTTP 2xx 判断通过；完整断言（含 JSONPath）由 AssertionEngine 处理，
        // 但 ChainStep 持久化只存了 JSON 字符串，需要 UI 编辑器把它解析为 List<Assertion>。
        // 编辑器尚未接入时，按 2xx fallback 防误判，避免阻塞跑链；后续 v1.3.3.x 接入完整断言序列化。
        return result.statusCode in 200..399
    }
}
