package com.ai.assistance.operit.data.stats

import android.content.Context
import com.ai.assistance.operit.data.model.BillingMode
import com.ai.assistance.operit.data.model.normalizeProviderModel
import com.ai.assistance.operit.data.model.normalizeProviderTypeId
import java.util.Locale

/**
 * Resolves and formats the per-message cost label shown under an AI message.
 * Price precedence matches the statistics screen: user price setting first, then the built-in
 * model pricing table. The label is rendered locally and never costs an extra token.
 */
object MessagePricingResolver {
    suspend fun costLabel(
        context: Context,
        provider: String,
        model: String,
        inputTokens: Long,
        cachedInputTokens: Long,
        outputTokens: Long,
    ): String? {
        if (provider.isBlank() || model.isBlank()) return null
        if (inputTokens <= 0L && outputTokens <= 0L) return null
        val cleanModel = model.trim()
        val providerModel = normalizeProviderModel("$provider:$cleanModel")
        val providerTypeId = normalizeProviderTypeId(provider)
        val repository = TokenUsageRepository.getInstance(context.applicationContext)
        val pricing =
            try {
                val setting =
                    repository.withDao { dao ->
                        dao.getStatsModel("", providerTypeId, cleanModel)?.toModelPriceSettings()
                    }
                TokenPriceResolver.resolve(providerModel, setting)
            } catch (_: Exception) {
                return null
            }
        return formatMessageCostLabel(inputTokens, cachedInputTokens, outputTokens, pricing)
    }
}

internal fun formatMessageCostLabel(
    inputTokens: Long,
    cachedInputTokens: Long,
    outputTokens: Long,
    pricing: ResolvedTokenPricing,
): String? {
    val amount =
        if (pricing.billingMode == BillingMode.COUNT) {
            if (pricing.pricePerRequest <= 0.0) return null
            pricing.pricePerRequest
        } else {
            if (pricing.inputPricePerMillion <= 0.0 &&
                pricing.cachedInputPricePerMillion <= 0.0 &&
                pricing.outputPricePerMillion <= 0.0
            ) {
                return null
            }
            val cached = cachedInputTokens.coerceIn(0L, inputTokens.coerceAtLeast(0L))
            val uncached = (inputTokens - cached).coerceAtLeast(0L)
            uncached * pricing.inputPricePerMillion / 1000000.0 +
                cached * pricing.cachedInputPricePerMillion / 1000000.0 +
                outputTokens * pricing.outputPricePerMillion / 1000000.0
        }
    if (amount <= 0.0) return null
    return "花费 " + pricing.currency.symbol + formatCostAmount(amount)
}

private fun formatCostAmount(amount: Double): String =
    when {
        amount < 0.000001 -> String.format(Locale.US, "%.8f", amount)
        amount < 0.01 -> String.format(Locale.US, "%.6f", amount)
        amount < 1.0 -> String.format(Locale.US, "%.4f", amount)
        else -> String.format(Locale.US, "%.3f", amount)
    }
