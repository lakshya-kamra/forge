package com.laksh.forge.ui.skeleton

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.laksh.forge.domain.exercise.CatalogParser
import com.laksh.forge.domain.exercise.ParsedCatalog
import org.junit.Test
import java.io.File

/** FK + IK over the real catalog motions: bones keep their length and planted feet stay planted. */
class PoseSolverTest {

    private val catalog: ParsedCatalog by lazy {
        val file = listOf(
            File("src/main/assets/exercises/catalog.json"),
            File("app/src/main/assets/exercises/catalog.json")
        ).first { it.isFile }
        CatalogParser.parse(file.readText())
    }

    @Test fun everyMotionSolvesWithExactBoneLengths() {
        assertThat(catalog.motions).isNotEmpty()
        catalog.motions.forEach { (id, motion) ->
            val s = PoseSolver(motion)
            for (i in 0..24) {
                s.solve(i / 24f)
                s.pos.forEach { assertWithMessage("$id finite").that(it.isNaN() || it.isInfinite()).isFalse() }
                for (side in 0..1) {
                    assertWithMessage("$id thigh").that(V.dist(s.pos, Joint.hip(side), Joint.knee(side))).isWithin(1e-3f).of(Anthro.THIGH)
                    assertWithMessage("$id shank").that(V.dist(s.pos, Joint.knee(side), Joint.ankle(side))).isWithin(1e-3f).of(Anthro.SHANK)
                    assertWithMessage("$id upper arm").that(V.dist(s.pos, Joint.shoulder(side), Joint.elbow(side))).isWithin(1e-3f).of(Anthro.UPPER_ARM)
                    assertWithMessage("$id forearm").that(V.dist(s.pos, Joint.elbow(side), Joint.wrist(side))).isWithin(1e-3f).of(Anthro.FOREARM)
                }
            }
        }
    }

    @Test fun squatFeetStayPlantedThroughTheRep() {
        val s = PoseSolver(catalog.motions.getValue("back_squat"))
        s.solve(0f)
        val start = s.pos.copyOf()
        for (i in 0..60) {
            s.solve(i / 60f)
            for (side in 0..1) {
                val a = Joint.ankle(side) * 3
                for (k in 0..2) {
                    assertWithMessage("ankle $side axis $k at ${i / 60f}").that(s.pos[a + k]).isWithin(0.01f).of(start[a + k])
                }
            }
        }
    }

    @Test fun bonesNeverSinkBelowTheFloorWhenStanding() {
        val s = PoseSolver(catalog.motions.getValue("back_squat"))
        for (i in 0..30) {
            s.solve(i / 30f)
            for (j in 0 until Joint.COUNT) assertThat(s.pos[j * 3 + 1]).isGreaterThan(-0.02f)
        }
    }
}
