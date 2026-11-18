package com.laksh.forge.domain.plan

import com.laksh.forge.data.ForgeRepository
import com.laksh.forge.domain.MacroTargets
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * Cross-feature API: the active plan's daily targets, including every check-in adjustment
 * (adjustments are written onto the plan row itself), or null when there is no active plan.
 * Today and Trends prefer this over NutritionMath.targets.
 */
object PlanTargets {
    fun observe(repo: ForgeRepository): Flow<MacroTargets?> =
        PlanStore.planDao.observeActivePlan()
            // A cut past 16 weeks ends here even if no check-in is ever saved; the switch
            // re-emits the new maintenance plan.
            .onStart { runCatching { PlanStore.enforceCutLimit(repo) } }
            .map { plan -> plan?.toMacroTargets() }
            .distinctUntilChanged()
}
