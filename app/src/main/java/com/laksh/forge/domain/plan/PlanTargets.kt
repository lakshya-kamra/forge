package com.laksh.forge.domain.plan

import com.laksh.forge.data.ForgeRepository
import com.laksh.forge.domain.MacroTargets
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Cross-feature API: the active plan's daily targets, including every check-in adjustment
 * (adjustments are written onto the plan row itself), or null when there is no active plan.
 * Today and Trends prefer this over NutritionMath.targets.
 */
object PlanTargets {
    @Suppress("UNUSED_PARAMETER") // repo is part of the contract so callers need no DB wiring
    fun observe(repo: ForgeRepository): Flow<MacroTargets?> =
        PlanStore.planDao.observeActivePlan()
            .map { plan -> plan?.toMacroTargets() }
            .distinctUntilChanged()
}
