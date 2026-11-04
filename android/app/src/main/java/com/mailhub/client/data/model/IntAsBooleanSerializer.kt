package com.mailhub.client.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 后端用 SQLite 的 INTEGER 0/1 表示布尔值（例如 accounts.enabled），
 * 而 JSON 里是数字而不是布尔值。解码时同时接受 0/1 与 true/false，
 * 编码时统一输出布尔值，接口语义保持一致。
 */
object IntAsBooleanSerializer : KSerializer<Boolean> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.mailhub.client.IntAsBoolean", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean {
        if (decoder !is JsonDecoder) {
            return decoder.decodeBoolean()
        }
        val primitive = decoder.decodeJsonElement() as? JsonPrimitive
            ?: throw SerializationException("布尔字段只能是数字或布尔值")
        primitive.booleanOrNull?.let { return it }
        primitive.intOrNull?.let { return it != 0 }
        throw SerializationException("布尔字段只能是数字或布尔值")
    }

    override fun serialize(encoder: Encoder, value: Boolean) {
        if (encoder is JsonEncoder) {
            encoder.encodeJsonElement(JsonPrimitive(value))
        } else {
            encoder.encodeBoolean(value)
        }
    }
}
