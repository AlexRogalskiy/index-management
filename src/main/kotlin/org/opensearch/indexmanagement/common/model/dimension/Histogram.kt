/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.indexmanagement.common.model.dimension

import org.opensearch.common.io.stream.StreamInput
import org.opensearch.common.io.stream.StreamOutput
import org.opensearch.common.xcontent.ToXContent
import org.opensearch.common.xcontent.XContentBuilder
import org.opensearch.common.xcontent.XContentParser
import org.opensearch.common.xcontent.XContentParser.Token
import org.opensearch.common.xcontent.XContentParserUtils.ensureExpectedToken
import org.opensearch.index.mapper.NumberFieldMapper
import org.opensearch.indexmanagement.util.IndexUtils.Companion.getFieldFromMappings
import org.opensearch.search.aggregations.AggregatorFactories
import org.opensearch.search.aggregations.bucket.composite.CompositeValuesSourceBuilder
import org.opensearch.search.aggregations.bucket.composite.HistogramValuesSourceBuilder
import org.opensearch.search.aggregations.bucket.histogram.HistogramAggregationBuilder
import java.io.IOException

// TODO: Verify if offset, missing value, min_doc_count, extended_bounds are usable in Composite histogram source
data class Histogram(
    override val sourceField: String,
    override val targetField: String,
    val interval: Double
) : Dimension(Type.HISTOGRAM, sourceField, targetField) {

    init {
        require(sourceField.isNotEmpty() && targetField.isNotEmpty()) { "Source and target field must not be empty" }
        require(interval > 0.0) { "Interval must be a positive decimal" }
    }

    @Throws(IOException::class)
    constructor(sin: StreamInput) : this(
        sourceField = sin.readString(),
        targetField = sin.readString(),
        interval = sin.readDouble()
    )

    override fun toXContent(builder: XContentBuilder, params: ToXContent.Params): XContentBuilder {
        return builder.startObject()
            .startObject(type.type)
            .field(DIMENSION_SOURCE_FIELD_FIELD, sourceField)
            .field(DIMENSION_TARGET_FIELD_FIELD, targetField)
            .field(HISTOGRAM_INTERVAL_FIELD, interval)
            .endObject()
            .endObject()
    }

    override fun writeTo(out: StreamOutput) {
        out.writeString(sourceField)
        out.writeString(targetField)
        out.writeDouble(interval)
    }

    override fun toSourceBuilder(appendType: Boolean): CompositeValuesSourceBuilder<*> {
        val name = if (appendType) "${this.targetField}.${Type.HISTOGRAM.type}" else this.targetField
        return HistogramValuesSourceBuilder(name)
            .missingBucket(true)
            .field(this.sourceField)
            .interval(this.interval)
    }

    override fun canBeRealizedInMappings(mappings: Map<String, Any>): Boolean {
        val fieldType = getFieldFromMappings(sourceField, mappings)?.get("type") ?: return false

        val numberTypes = mutableSetOf<String>()
        NumberFieldMapper.NumberType.values().forEach {
            numberTypes.add(it.typeName())
        }

        return fieldType in numberTypes
    }

    fun getRewrittenAggregation(
        aggregationBuilder: HistogramAggregationBuilder,
        subAggregations: AggregatorFactories.Builder
    ): HistogramAggregationBuilder =
        HistogramAggregationBuilder(aggregationBuilder.name)
            .interval(aggregationBuilder.interval())
            .also {
                if (aggregationBuilder.minBound().isFinite() && aggregationBuilder.maxBound().isFinite()) {
                    it.extendedBounds(aggregationBuilder.minBound(), aggregationBuilder.maxBound())
                }
            }
            .keyed(aggregationBuilder.keyed())
            .also {
                if (aggregationBuilder.minDocCount() >= 0) {
                    it.minDocCount(aggregationBuilder.minDocCount())
                }
            }
            .offset(aggregationBuilder.offset())
            .also { aggregationBuilder.order()?.apply { it.order(this) } }
            .field(this.targetField + ".histogram")
            .subAggregations(subAggregations)

    companion object {
        const val HISTOGRAM_INTERVAL_FIELD = "interval"

        @Suppress("ComplexMethod", "LongMethod")
        @JvmStatic
        @Throws(IOException::class)
        fun parse(xcp: XContentParser): Histogram {
            var sourceField: String? = null
            var targetField: String? = null
            var interval: Double? = null

            ensureExpectedToken(Token.START_OBJECT, xcp.currentToken(), xcp)
            while (xcp.nextToken() != Token.END_OBJECT) {
                val fieldName = xcp.currentName()
                xcp.nextToken()

                when (fieldName) {
                    DIMENSION_SOURCE_FIELD_FIELD -> sourceField = xcp.text()
                    DIMENSION_TARGET_FIELD_FIELD -> targetField = xcp.text()
                    HISTOGRAM_INTERVAL_FIELD -> interval = xcp.doubleValue()
                    else -> throw IllegalArgumentException("Invalid field [$fieldName] found in histogram dimension.")
                }
            }
            if (targetField == null) targetField = sourceField
            return Histogram(
                requireNotNull(sourceField) { "Source field must not be null" },
                requireNotNull(targetField) { "Target field must not be null" },
                requireNotNull(interval) { "Interval field must not be null" }
            )
        }

        @JvmStatic
        @Throws(IOException::class)
        fun readFrom(sin: StreamInput) = Histogram(sin)
    }
}
