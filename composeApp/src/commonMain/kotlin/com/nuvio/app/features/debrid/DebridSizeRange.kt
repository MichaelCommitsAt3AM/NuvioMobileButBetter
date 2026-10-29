package com.nuvio.app.features.debrid

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive

internal val debridSizePresetsGb = listOf(0.0, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0)
private val maxDebridSizeGb = Long.MAX_VALUE / 1_000_000_000.0

internal fun normalizeDebridSizeGb(value: Double): Double =
    if (value.isFinite()) value.coerceIn(0.0, maxDebridSizeGb) else 0.0

internal fun parseDebridSizeGb(text: String): Double? =
    text.trim().replace(',', '.').toDoubleOrNull()
        ?.takeIf { it.isFinite() && it > 0.0 && it <= maxDebridSizeGb }

internal fun formatDebridSizeGb(value: Double): String =
    if (value > 0.0 && value < 1.0) "${(value * 1000).toString().removeSuffix(".0")} MB"
    else "${value.toString().removeSuffix(".0")} GB"

/** Keep integral bounds readable by older clients while accepting legacy integer JSON. */
internal object DebridSizeGbSerializer : KSerializer<Double> {
    override val descriptor = PrimitiveSerialDescriptor("DebridSizeGb", PrimitiveKind.DOUBLE)
    override fun deserialize(decoder: Decoder): Double = decoder.decodeDouble()
    override fun serialize(encoder: Encoder, value: Double) {
        if (encoder is JsonEncoder && value.isFinite() && value % 1.0 == 0.0 && value < Long.MAX_VALUE.toDouble()) {
            encoder.encodeJsonElement(JsonPrimitive(value.toLong()))
        } else {
            encoder.encodeDouble(value)
        }
    }
}
