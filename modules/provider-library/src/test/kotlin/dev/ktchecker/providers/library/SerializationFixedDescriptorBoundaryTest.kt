@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.ktchecker.providers.library

import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.PairSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * Prospective real-library contract oracles for the existing planned rule
 * ktc.serialization.fixed-descriptor-index-out-of-range, NOT a detector or provider test.
 *
 * The root build declares serialization 1.11.0; the catalog observes 1.12.0. Neither declaration
 * establishes resolved API compatibility or qualification. These controls must be compiled/run
 * against admitted artifacts before any runtime conclusion; no version-wide claim follows.
 *
 * The manual strategies below call actual Json/descriptor/encoder APIs. They do not copy range
 * validation or infer descriptor size from Kotlin fields. Fixed CLASS indices and collection
 * logical indices intentionally use different contracts. Only this Json oracle is asserted;
 * no universal format exception type or library-rule coverage is asserted.
 */
class SerializationFixedDescriptorBoundaryTest {
    private val pairCodec = PairSerializer(Int.serializer(), Int.serializer())
    private val listCodec = ListSerializer(Int.serializer())
    private val mapCodec = MapSerializer(String.serializer(), Int.serializer())

    @Test fun fixed_class_first_and_last_indices_encode_and_round_trip() {
        fixedPairControl()
    }

    @Test fun fixed_class_index_equal_to_elements_count_is_rejected_by_json() {
        // Verify the unchanged setup succeeds before changing only the second element index.
        val value = fixedPairControl()
        assertFails {
            encodeFixedPair(value, pairCodec.descriptor.elementsCount)
        }
    }

    @Test fun fixed_class_negative_index_is_rejected_by_json() {
        val value = fixedPairControl()
        assertFails {
            encodeFixedPair(value, -1)
        }
    }

    @Test fun list_logical_indices_beyond_descriptor_elements_count_encode_and_round_trip() {
        val descriptor = listCodec.descriptor
        assertEquals(StructureKind.LIST, descriptor.kind)
        assertEquals(1, descriptor.elementsCount)
        val value = listOf(17, 29, 43, 61)
        assertTrue(value.lastIndex >= descriptor.elementsCount)

        val strategy = object : SerializationStrategy<List<Int>> {
            override val descriptor: SerialDescriptor = listCodec.descriptor

            override fun serialize(encoder: Encoder, value: List<Int>) {
                val composite = encoder.beginCollection(descriptor, value.size)
                // Full logical sequence 0..3, including index == elementsCount and greater.
                value.forEachIndexed { index, item ->
                    composite.encodeIntElement(descriptor, index, item)
                }
                composite.endStructure(descriptor)
            }
        }
        val encoded = Json.encodeToString(strategy, value)
        assertEquals("[17,29,43,61]", encoded)
        assertEquals(value, Json.decodeFromString(listCodec, encoded))
    }

    @Test fun map_logical_indices_beyond_descriptor_elements_count_encode_and_round_trip() {
        val descriptor = mapCodec.descriptor
        assertEquals(StructureKind.MAP, descriptor.kind)
        assertEquals(2, descriptor.elementsCount)
        val value = linkedMapOf("alpha" to 17, "beta" to 29, "gamma" to 43)
        assertTrue(value.size * 2 - 1 >= descriptor.elementsCount)

        val strategy = object : SerializationStrategy<Map<String, Int>> {
            override val descriptor: SerialDescriptor = mapCodec.descriptor

            override fun serialize(encoder: Encoder, value: Map<String, Int>) {
                val composite = encoder.beginCollection(descriptor, value.size)
                var logicalIndex = 0
                // Full alternating key/value sequence 0..5, not a jump over required elements.
                value.forEach { (key, item) ->
                    composite.encodeStringElement(descriptor, logicalIndex++, key)
                    composite.encodeIntElement(descriptor, logicalIndex++, item)
                }
                composite.endStructure(descriptor)
            }
        }
        val encoded = Json.encodeToString(strategy, value)
        assertEquals("{\"alpha\":17,\"beta\":29,\"gamma\":43}", encoded)
        assertEquals(value, Json.decodeFromString(mapCodec, encoded))
    }

    /** Two distinct legal boundaries: first == 0 and last == elementsCount - 1 == 1. */
    private fun fixedPairControl(): Pair<Int, Int> {
        val descriptor = pairCodec.descriptor
        assertEquals(StructureKind.CLASS, descriptor.kind)
        assertEquals(2, descriptor.elementsCount)
        val value = 17 to 29
        val encoded = encodeFixedPair(value, descriptor.elementsCount - 1)
        assertEquals("{\"first\":17,\"second\":29}", encoded)
        // The built-in decoder uses the same descriptor delegated to by our manual strategy.
        assertEquals(value, Json.decodeFromString(pairCodec, encoded))
        return value
    }

    private fun encodeFixedPair(value: Pair<Int, Int>, secondIndex: Int): String {
        val strategy = object : SerializationStrategy<Pair<Int, Int>> {
            override val descriptor: SerialDescriptor = pairCodec.descriptor

            override fun serialize(encoder: Encoder, value: Pair<Int, Int>) {
                val composite = encoder.beginStructure(descriptor)
                composite.encodeIntElement(descriptor, 0, value.first)
                composite.encodeIntElement(descriptor, secondIndex, value.second)
                composite.endStructure(descriptor)
            }
        }
        return Json.encodeToString(strategy, value)
    }
}
