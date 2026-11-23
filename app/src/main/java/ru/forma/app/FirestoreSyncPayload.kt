package ru.forma.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** Maps the Room snapshot to a Firestore document and back without relying on field order. */
internal object FirestoreSyncPayload {
    private val json = Json { encodeDefaults = true }

    fun document(kind: String, snapshot: String): Map<String, Any?> =
        canonical(kind, json.parseToJsonElement(snapshot)).mapValues { (_, value) -> value.toFirestore() }

    fun snapshot(kind: String, document: Map<*, *>): String =
        canonical(kind, document.toJson()).toString()

    private fun canonical(kind: String, payload: JsonElement): JsonObject {
        val encoded = when (kind) {
            "profile" -> json.encodeToJsonElement(json.decodeFromJsonElement<CloudProfilePayload>(payload))
            "measurement" -> {
                val measurement = json.decodeFromJsonElement<CloudMeasurementPayload>(payload)
                json.encodeToJsonElement(measurement.copy(values = measurement.values.toSortedMap()))
            }
            "workout" -> json.encodeToJsonElement(json.decodeFromJsonElement<CloudWorkoutPayload>(payload))
            else -> error("Unknown sync record kind")
        }.jsonObject
        require(payload.sameShape(encoded)) { "Incomplete or mistyped sync payload" }
        return encoded
    }

    private fun JsonElement.sameShape(other: JsonElement): Boolean = when (this) {
        JsonNull -> other === JsonNull
        is JsonObject -> other is JsonObject && keys == other.keys &&
            all { (key, value) -> value.sameShape(other.getValue(key)) }
        is JsonArray -> other is JsonArray && size == other.size &&
            indices.all { this[it].sameShape(other[it]) }
        is JsonPrimitive -> other is JsonPrimitive && other !== JsonNull &&
            isString == other.isString && (booleanOrNull != null) == (other.booleanOrNull != null)
    }

    private fun JsonElement.toFirestore(): Any? = when (this) {
        JsonNull -> null
        is JsonObject -> mapValues { (_, value) -> value.toFirestore() }
        is JsonArray -> map { it.toFirestore() }
        is JsonPrimitive -> when {
            isString -> content
            else -> booleanOrNull ?: longOrNull ?: doubleOrNull?.takeIf(Double::isFinite)
                ?: error("Unsupported sync number")
        }
    }

    private fun Any?.toJson(): JsonElement = when (this) {
        null -> JsonNull
        is String -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(this)
        is Long -> JsonPrimitive(this)
        is Int -> JsonPrimitive(this)
        is Double -> {
            require(isFinite()) { "Invalid sync number" }
            JsonPrimitive(this)
        }
        is Map<*, *> -> JsonObject(entries.associate { (key, value) ->
            require(key is String) { "Invalid sync field" }
            key to value.toJson()
        })
        is List<*> -> JsonArray(map { it.toJson() })
        else -> error("Unsupported Firestore value")
    }
}
