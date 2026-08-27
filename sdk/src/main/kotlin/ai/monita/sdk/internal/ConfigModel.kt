// Copyright RNA Digital PTY LTD
package ai.monita.sdk.internal

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

/**
 * Remote config wire model. Key names mirror the served JSON exactly,
 * including the legacy misspelled wire keys "eventParamter" and
 * "execludeParameters"; internal property names are spelled correctly.
 * Unknown fields are ignored, never fatal.
 */

internal val configJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = false
}

@Serializable
internal data class RemoteConfig(
    val monitoringVersion: String = "",
    val allowManualMonitoring: Boolean = false,
    val monitoringStatus: String? = null,
    val vendors: List<VendorConfig> = emptyList(),
)

@Serializable
internal data class VendorConfig(
    val vendorName: String,
    val urlPatternMatches: List<String> = emptyList(),
    @SerialName("eventParamter")
    val eventParameter: String? = null,
    @SerialName("execludeParameters")
    val excludeParameters: List<String> = emptyList(),
    val filters: List<WireFilter> = emptyList(),
    val filterGroups: List<WireFilterGroup> = emptyList(),
)

@Serializable
internal data class WireFilter(
    val key: String,
    val op: String? = null,
    @SerialName("val")
    @Serializable(with = StringOrStringListSerializer::class)
    val values: List<String>? = null,
)

@Serializable
internal data class WireFilterGroup(
    val op: String = "all",
    val filters: List<WireFilter> = emptyList(),
)

/**
 * Accepts a filter "val" as either a single string or an array of strings
 * (the wire always sends an array; a bare string is tolerated for safety).
 * Non string primitives decode via their JSON content.
 */
internal object StringOrStringListSerializer : KSerializer<List<String>?> {
    private val delegate = ListSerializer(String.serializer())

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): List<String>? {
        val input = decoder as? JsonDecoder
            ?: return delegate.deserialize(decoder)
        return when (val element = input.decodeJsonElement()) {
            is JsonArray -> element.map { (it as? JsonPrimitive)?.content ?: it.toString() }
            is JsonPrimitive -> listOf(element.content)
            else -> null
        }
    }

    override fun serialize(encoder: Encoder, value: List<String>?) {
        delegate.serialize(encoder, value ?: emptyList())
    }
}

internal fun decodeRemoteConfig(text: String): RemoteConfig =
    configJson.decodeFromString(RemoteConfig.serializer(), text)
