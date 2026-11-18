package com.laksh.forge.ui.skeleton

/**
 * Where each catalog muscle id glows on the skeleton.
 *
 * A glow is a soft capsule between two points. Each point sits a fraction of the way between
 * two joints and is then pushed off that line in a body frame (thorax or pelvis anterior/up,
 * plus world-lateral), which is how trunk muscles land on the front or back of the ribcage
 * rather than on the spine. Limb muscles just ride their segment: at phone size a glowing
 * upper arm reads as "arms" whether it is the biceps or the triceps working.
 */
internal class MuscleGlows(primary: List<String>, secondary: List<String>) {

    private class End(val j1: Int, val j2: Int, val f: Float, val frame: Int, val ant: Float, val up: Float, val lat: Float)
    private class Def(val a: End, val b: End, val radius: Float, val primary: Boolean)

    val count: Int
    /** World endpoints, a xyz then b xyz, refreshed by [update]. */
    val ends: FloatArray
    val radius: FloatArray
    val primary: BooleanArray
    private val defs: Array<Def>

    init {
        val list = ArrayList<Def>()
        val seen = HashSet<String>()
        for (m in primary) if (seen.add(m)) defsFor(m, true, list)
        for (m in secondary) if (seen.add(m)) defsFor(m, false, list)
        defs = list.toTypedArray()
        count = defs.size
        ends = FloatArray(count * 6)
        radius = FloatArray(count) { defs[it].radius }
        this.primary = BooleanArray(count) { defs[it].primary }
    }

    fun update(s: PoseSolver) {
        for (i in 0 until count) {
            val d = defs[i]
            place(s, d.a, i * 6)
            place(s, d.b, i * 6 + 3)
        }
    }

    private fun place(s: PoseSolver, e: End, o: Int) {
        val p = s.pos
        val a = e.j1 * 3
        val b = e.j2 * 3
        var x = p[a] + (p[b] - p[a]) * e.f
        var y = p[a + 1] + (p[b + 1] - p[a + 1]) * e.f
        var z = p[a + 2] + (p[b + 2] - p[a + 2]) * e.f
        when (e.frame) {
            THOR -> {
                x += s.thorAnt[0] * e.ant + s.thorUp[0] * e.up
                y += s.thorAnt[1] * e.ant + s.thorUp[1] * e.up
                z += s.thorAnt[2] * e.ant + s.thorUp[2] * e.up
            }
            PEL -> {
                x += s.pelAnt[0] * e.ant + s.pelUp[0] * e.up
                y += s.pelAnt[1] * e.ant + s.pelUp[1] * e.up
                z += s.pelAnt[2] * e.ant + s.pelUp[2] * e.up
            }
        }
        // The trunk frames never roll, so the lifter's lateral axis is always world x.
        x += e.lat
        ends[o] = x; ends[o + 1] = y; ends[o + 2] = z
    }

    private fun defsFor(muscle: String, primary: Boolean, out: MutableList<Def>) {
        fun seg(a: End, b: End, r: Float) { out += Def(a, b, r, primary) }
        fun at(j: Int, frame: Int = NONE, ant: Float = 0f, up: Float = 0f, lat: Float = 0f) = End(j, j, 0f, frame, ant, up, lat)
        fun along(j1: Int, j2: Int, f: Float, lat: Float = 0f) = End(j1, j2, f, NONE, 0f, 0f, lat)

        when (muscle) {
            "abs" -> seg(at(Joint.PELVIS, PEL, ant = 0.10f, up = 0.07f), at(Joint.CHEST, THOR, ant = 0.10f, up = -0.06f), 0.05f)
            else -> for (s in 0..1) {
                val sg = Joint.sign(s)
                val sh = Joint.shoulder(s)
                val el = Joint.elbow(s)
                val wr = Joint.wrist(s)
                val hip = Joint.hip(s)
                val kn = Joint.knee(s)
                val an = Joint.ankle(s)
                when (muscle) {
                    "chest" -> seg(at(sh, THOR, ant = 0.06f, up = -0.04f, lat = -0.03f * sg), at(Joint.CHEST, THOR, ant = 0.11f, up = 0.04f, lat = 0.04f * sg), 0.05f)
                    "front_delts" -> seg(at(sh, THOR, ant = 0.035f), along(sh, el, 0.3f), 0.036f)
                    "side_delts" -> seg(at(sh, lat = 0.03f * sg), along(sh, el, 0.3f, lat = 0.015f * sg), 0.036f)
                    "rear_delts" -> seg(at(sh, THOR, ant = -0.035f), along(sh, el, 0.3f), 0.034f)
                    "triceps", "biceps" -> seg(along(sh, el, 0.2f), along(sh, el, 0.9f), 0.032f)
                    "forearms" -> seg(along(el, wr, 0.1f), along(el, wr, 0.8f), 0.028f)
                    "lats" -> seg(at(sh, THOR, ant = -0.05f, up = -0.08f, lat = -0.02f * sg), at(Joint.SPINE, PEL, ant = -0.06f, up = 0.02f, lat = 0.06f * sg), 0.05f)
                    "upper_back" -> seg(at(sh, THOR, ant = -0.07f, up = -0.04f, lat = -0.04f * sg), at(Joint.CHEST, THOR, ant = -0.08f, up = 0.02f, lat = 0.02f * sg), 0.045f)
                    "traps" -> seg(at(Joint.NECK, THOR, ant = -0.04f, up = 0.03f, lat = 0.01f * sg), at(sh, THOR, ant = -0.04f, up = 0.02f, lat = -0.02f * sg), 0.036f)
                    "lower_back" -> seg(at(Joint.PELVIS, PEL, ant = -0.07f, up = 0.04f, lat = 0.03f * sg), at(Joint.SPINE, THOR, ant = -0.07f, up = 0.05f, lat = 0.03f * sg), 0.035f)
                    "obliques" -> seg(at(Joint.PELVIS, PEL, ant = 0.06f, up = 0.08f, lat = 0.12f * sg), at(Joint.SPINE, THOR, ant = 0.07f, up = 0.1f, lat = 0.11f * sg), 0.035f)
                    "glutes" -> seg(at(hip, PEL, ant = -0.07f, up = 0.06f), at(hip, PEL, ant = -0.06f, up = -0.06f, lat = 0.02f * sg), 0.06f)
                    "quads" -> seg(along(hip, kn, 0.1f), along(hip, kn, 0.85f), 0.045f)
                    "hamstrings" -> seg(along(hip, kn, 0.15f), along(hip, kn, 0.9f), 0.04f)
                    "adductors" -> seg(along(hip, kn, 0.05f, lat = -0.035f * sg), along(hip, kn, 0.6f, lat = -0.03f * sg), 0.035f)
                    "calves" -> seg(along(kn, an, 0.1f), along(kn, an, 0.6f), 0.038f)
                }
            }
        }
    }

    private companion object {
        const val NONE = 0
        const val THOR = 1
        const val PEL = 2
    }
}
