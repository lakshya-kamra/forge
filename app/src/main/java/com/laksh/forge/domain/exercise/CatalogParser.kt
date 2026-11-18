package com.laksh.forge.domain.exercise

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.doubleOrNull

/** Everything read from assets/exercises/catalog.json. */
class ParsedCatalog(
    val exercises: List<ExerciseInfo>,
    val motions: Map<String, ExerciseMotion>,
    val extras: Map<String, ExerciseExtras>,
    /** Video author name -> GVS tier, for every author that GVS ranked. */
    val authorTiers: Map<String, String>,
    val creators: List<Creator>,
    val licence: CatalogLicence
)

/**
 * Hand-rolled parsing over JsonElement rather than @Serializable classes: the catalog is read
 * once, the shape is ours, and this keeps the models free of serializer plumbing (and safe
 * from R8 stripping generated serializers in release builds).
 */
object CatalogParser {

    fun parse(text: String): ParsedCatalog {
        val root = Json.parseToJsonElement(text).obj()
        val list = ArrayList<ExerciseInfo>()
        val motions = HashMap<String, ExerciseMotion>()
        val extras = HashMap<String, ExerciseExtras>()
        val tiers = HashMap<String, String>()

        for (el in root.arr("exercises")) {
            val e = el.obj()
            val id = e.str("id")
            val videos = e.arr("videos").map { v ->
                val o = v.obj()
                val author = o.str("author")
                o.strOrNull("tier")?.let { tiers[author] = it }
                TutorialVideo(
                    videoId = o.str("videoId"),
                    title = o.str("title"),
                    author = author,
                    why = o.str("why")
                )
            }
            val motion = e["motion"]?.takeIf { it !is JsonNull }?.let { parseMotion(it.obj()) }
            if (motion != null) motions[id] = motion
            list += ExerciseInfo(
                id = id,
                name = e.str("name"),
                aliases = e.strings("aliases"),
                primaryMuscles = e.strings("primaryMuscles"),
                secondaryMuscles = e.strings("secondaryMuscles"),
                equipment = e.strings("equipment"),
                cues = e.strings("cues"),
                mistakes = e.strings("mistakes"),
                videos = videos,
                images = e.strings("images"),
                hasMotion = motion != null
            )
            extras[id] = ExerciseExtras(
                motionSources = e.arr("motionSources").map {
                    val o = it.obj()
                    SourceRef(o.str("cite"), o.strOrNull("url"))
                },
                angleBasis = e.strOrNull("angleBasis"),
                imageSource = e["imageSource"]?.takeIf { it !is JsonNull }?.obj()?.let {
                    ImageSource(
                        dataset = it.str("dataset"),
                        entryId = it.strOrNull("entryId"),
                        match = it.strOrNull("match"),
                        note = it.strOrNull("note").orEmpty()
                    )
                }
            )
        }

        val creators = root.arr("creators").map {
            val o = it.obj()
            Creator(
                name = o.str("name"),
                channel = o.str("channel"),
                channelUrl = o.str("channelUrl"),
                tier = o.strOrNull("tier")
            )
        }
        val lic = root["licence"]!!.obj()
        val licence = CatalogLicence(
            images = lic.str("images"),
            imagesUrl = lic.str("imagesUrl"),
            videos = lic.str("videos"),
            gvs = lic.str("gvs"),
            animation = lic.str("animation")
        )
        return ParsedCatalog(list, motions, extras, tiers, creators, licence)
    }

    private fun parseMotion(m: JsonObject): ExerciseMotion {
        val legs = m.strings("legs").map { enumOr(it, LegContact.FREE) }
        val cable = m["cable"]?.takeIf { it !is JsonNull }?.obj()?.let { c ->
            CableSpec(
                pulleys = c.arr("pulleys").map { p -> p.jsonArr().floats(3) },
                attach = enumOr(c.str("attach"), CableAttach.MID),
                handle = enumOr(c.str("handle"), CableHandle.SINGLE)
            )
        }
        val view = m["view"]!!.obj()
        val keyframes = m.arr("keyframes").map { parseKeyframe(it.obj()) }
        return ExerciseMotion(
            orientation = m.str("orientation"),
            implement = m.str("implement"),
            anchor = enumOr(m.str("anchor"), MotionAnchor.FEET),
            legs = arrayOf(legs.getOrElse(0) { LegContact.FREE }, legs.getOrElse(1) { LegContact.FREE }),
            arms = enumOr(m.str("arms"), ArmMode.FREE),
            barMount = m.strOrNull("bar")?.let { enumOrNull<BarMount>(it) },
            props = m.strings("props").mapNotNull { enumOrNull<MotionProp>(it) }.toSet(),
            cable = cable,
            dumbbellAxis = enumOr(m.strOrNull("dumbbellAxis") ?: "LATERAL", DumbbellAxis.LATERAL),
            farmerHandles = m.bool("farmer"),
            mirrorAlternate = m.bool("mirrorAlternate"),
            stanceWidth = m.floatOrNull("stanceWidth"),
            gripWidth = m.floatOrNull("gripWidth"),
            turnoutDeg = m.floatOrNull("turnout") ?: 8f,
            seatHeight = m.floatOrNull("seatHeight"),
            boxHeight = m.floatOrNull("boxHeight"),
            blockHeight = m.floatOrNull("blockHeight"),
            viewYaw = view.floatOrNull("yaw") ?: -60f,
            viewPitch = view.floatOrNull("pitch") ?: 12f,
            durationS = m.floatOrNull("durationS") ?: 3f,
            tempoKind = enumOr(m.strOrNull("tempoKind") ?: "REPS", TempoKind.REPS),
            tempoNote = m.strOrNull("tempoNote").orEmpty(),
            phases = m.arr("phases").map {
                val o = it.obj()
                MotionPhase(
                    kind = enumOr(o.str("kind"), PhaseKind.MOVE),
                    start = o.floatOrNull("start") ?: 0f,
                    end = o.floatOrNull("end") ?: 1f,
                    seconds = o["seconds"]?.jsonPrim()?.doubleOrNull ?: 0.0
                )
            },
            keyframes = keyframes
        )
    }

    private fun parseKeyframe(k: JsonObject): MotionKeyframe {
        fun pair(key: String) = k[key]!!.jsonArr().floats(2)
        fun vec2(key: String): FloatArray {
            val a = k[key]!!.jsonArr()
            val out = FloatArray(6)
            for (s in 0..1) {
                val v = a[s].jsonArr()
                for (i in 0..2) out[s * 3 + i] = v[i].jsonPrim().floatOrNull ?: 0f
            }
            return out
        }
        val scap = k["scap"]?.takeIf { it !is JsonNull }?.jsonArr()?.floats(2) ?: FloatArray(2)
        return MotionKeyframe(
            t = k.floatOrNull("t") ?: 0f,
            trunk = k.floatOrNull("trunk") ?: 0f,
            spine = k.floatOrNull("spine") ?: 0f,
            hip = pair("hip"),
            knee = pair("knee"),
            ankle = pair("ankle"),
            abduction = pair("abd"),
            upperArm = vec2("u"),
            hand = vec2("hand"),
            scapRetraction = scap[0],
            scapElevation = scap[1],
            grip = k.floatOrNull("grip") ?: Float.NaN,
            label = k.strOrNull("label").orEmpty()
        )
    }

    // ------------------------------------------------------------------ small JSON helpers

    private fun JsonElement.obj(): JsonObject = this as JsonObject
    private fun JsonElement.jsonArr(): JsonArray = this as JsonArray
    private fun JsonElement.jsonPrim(): JsonPrimitive = this as JsonPrimitive

    private fun JsonObject.arr(key: String): JsonArray =
        (this[key] as? JsonArray) ?: JsonArray(emptyList())

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull
            ?: throw IllegalStateException("Exercise catalog is missing \"$key\"")

    private fun JsonObject.strOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.floatOrNull(key: String): Float? =
        (this[key] as? JsonPrimitive)?.floatOrNull

    private fun JsonObject.bool(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

    private fun JsonObject.strings(key: String): List<String> =
        arr(key).mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    private fun JsonArray.floats(n: Int): FloatArray =
        FloatArray(n) { i -> (getOrNull(i) as? JsonPrimitive)?.floatOrNull ?: 0f }

    private inline fun <reified E : Enum<E>> enumOr(name: String, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback

    private inline fun <reified E : Enum<E>> enumOrNull(name: String): E? =
        enumValues<E>().firstOrNull { it.name == name }
}
