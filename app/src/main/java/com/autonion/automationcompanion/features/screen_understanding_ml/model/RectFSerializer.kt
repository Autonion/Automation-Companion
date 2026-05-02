package com.autonion.automationcompanion.features.screen_understanding_ml.model

import android.graphics.RectF
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure

object RectFSerializer : KSerializer<RectF> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("android.graphics.RectF") {
        element<Float>("left")
        element<Float>("top")
        element<Float>("right")
        element<Float>("bottom")
    }

    override fun serialize(encoder: Encoder, value: RectF) {
        encoder.encodeStructure(descriptor) {
            encodeFloatElement(descriptor, 0, value.left)
            encodeFloatElement(descriptor, 1, value.top)
            encodeFloatElement(descriptor, 2, value.right)
            encodeFloatElement(descriptor, 3, value.bottom)
        }
    }

    override fun deserialize(decoder: Decoder): RectF {
        return decoder.decodeStructure(descriptor) {
            var left = 0f
            var top = 0f
            var right = 0f
            var bottom = 0f
            loop@ while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    0 -> left = decodeFloatElement(descriptor, 0)
                    1 -> top = decodeFloatElement(descriptor, 1)
                    2 -> right = decodeFloatElement(descriptor, 2)
                    3 -> bottom = decodeFloatElement(descriptor, 3)
                    kotlinx.serialization.encoding.CompositeDecoder.DECODE_DONE -> break@loop
                    else -> throw kotlinx.serialization.SerializationException("Unexpected index '$index'")
                }
            }
            RectF(left, top, right, bottom)
        }
    }
}
