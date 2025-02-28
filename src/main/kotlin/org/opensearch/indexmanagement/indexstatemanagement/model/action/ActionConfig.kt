/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.indexmanagement.indexstatemanagement.model.action

import org.opensearch.client.Client
import org.opensearch.cluster.service.ClusterService
import org.opensearch.common.io.stream.StreamInput
import org.opensearch.common.io.stream.StreamOutput
import org.opensearch.common.io.stream.Writeable
import org.opensearch.common.settings.Settings
import org.opensearch.common.xcontent.ToXContent
import org.opensearch.common.xcontent.ToXContentFragment
import org.opensearch.common.xcontent.XContentBuilder
import org.opensearch.common.xcontent.XContentParser
import org.opensearch.common.xcontent.XContentParser.Token
import org.opensearch.common.xcontent.XContentParserUtils.ensureExpectedToken
import org.opensearch.indexmanagement.indexstatemanagement.action.Action
import org.opensearch.indexmanagement.indexstatemanagement.model.ManagedIndexMetaData
import org.opensearch.script.ScriptService
import java.io.IOException

abstract class ActionConfig(
    val type: ActionType,
    val actionIndex: Int
) : ToXContentFragment, Writeable {

    var configTimeout: ActionTimeout? = null
    var configRetry: ActionRetry? = null

    override fun toXContent(builder: XContentBuilder, params: ToXContent.Params): XContentBuilder {
        configTimeout?.toXContent(builder, params)
        configRetry?.toXContent(builder, params)
        return builder
    }

    abstract fun toAction(
        clusterService: ClusterService,
        scriptService: ScriptService,
        client: Client,
        settings: Settings,
        managedIndexMetaData: ManagedIndexMetaData
    ): Action

    enum class ActionType(val type: String) {
        DELETE("delete"),
        TRANSITION("transition"),
        ROLLOVER("rollover"),
        CLOSE("close"),
        OPEN("open"),
        READ_ONLY("read_only"),
        READ_WRITE("read_write"),
        REPLICA_COUNT("replica_count"),
        FORCE_MERGE("force_merge"),
        NOTIFICATION("notification"),
        SNAPSHOT("snapshot"),
        INDEX_PRIORITY("index_priority"),
        ALLOCATION("allocation"),
        ROLLUP("rollup");

        override fun toString(): String {
            return type
        }
    }

    @Throws(IOException::class)
    override fun writeTo(out: StreamOutput) {
        out.writeEnum(type)
        out.writeInt(actionIndex)
        out.writeOptionalWriteable(configTimeout)
        out.writeOptionalWriteable(configRetry)
    }

    companion object {
        private const val DEFAULT_RETRIES = 3L

        // TODO clean up for actionIndex
        @JvmStatic
        @Throws(IOException::class)
        @Suppress("ComplexMethod")
        fun fromStreamInput(sin: StreamInput): ActionConfig {
            val type = sin.readEnum(ActionType::class.java)
            val actionIndex = sin.readInt()
            val configTimeout = sin.readOptionalWriteable(::ActionTimeout)
            val configRetry = sin.readOptionalWriteable(::ActionRetry)

            val actionConfig: ActionConfig = when (type.type) {
                ActionType.DELETE.type -> DeleteActionConfig(actionIndex)
                ActionType.OPEN.type -> OpenActionConfig(actionIndex)
                ActionType.CLOSE.type -> CloseActionConfig(actionIndex)
                ActionType.READ_ONLY.type -> ReadOnlyActionConfig(actionIndex)
                ActionType.READ_WRITE.type -> ReadWriteActionConfig(actionIndex)
                ActionType.ROLLOVER.type -> RolloverActionConfig(sin)
                ActionType.REPLICA_COUNT.type -> ReplicaCountActionConfig(sin)
                ActionType.FORCE_MERGE.type -> ForceMergeActionConfig(sin)
                ActionType.NOTIFICATION.type -> NotificationActionConfig(sin)
                ActionType.SNAPSHOT.type -> SnapshotActionConfig(sin)
                ActionType.INDEX_PRIORITY.type -> IndexPriorityActionConfig(sin)
                ActionType.ALLOCATION.type -> AllocationActionConfig(sin)
                ActionType.ROLLUP.type -> RollupActionConfig(sin)
                else -> throw IllegalArgumentException("Invalid field: [${type.type}] found in Action.")
            }

            actionConfig.configTimeout = configTimeout
            actionConfig.configRetry = configRetry

            return actionConfig
        }

        @Suppress("ComplexMethod")
        @JvmStatic
        @Throws(IOException::class)
        fun parse(xcp: XContentParser, index: Int): ActionConfig {
            var actionConfig: ActionConfig? = null
            var timeout: ActionTimeout? = null
            var retry: ActionRetry? = ActionRetry(DEFAULT_RETRIES)

            ensureExpectedToken(Token.START_OBJECT, xcp.currentToken(), xcp)
            while (xcp.nextToken() != Token.END_OBJECT) {
                val fieldName = xcp.currentName()
                xcp.nextToken()

                when (fieldName) {
                    ActionTimeout.TIMEOUT_FIELD -> timeout = ActionTimeout.parse(xcp)
                    ActionRetry.RETRY_FIELD -> retry = ActionRetry.parse(xcp)
                    ActionType.DELETE.type -> actionConfig = DeleteActionConfig.parse(xcp, index)
                    ActionType.ROLLOVER.type -> actionConfig = RolloverActionConfig.parse(xcp, index)
                    ActionType.OPEN.type -> actionConfig = OpenActionConfig.parse(xcp, index)
                    ActionType.CLOSE.type -> actionConfig = CloseActionConfig.parse(xcp, index)
                    ActionType.READ_ONLY.type -> actionConfig = ReadOnlyActionConfig.parse(xcp, index)
                    ActionType.READ_WRITE.type -> actionConfig = ReadWriteActionConfig.parse(xcp, index)
                    ActionType.REPLICA_COUNT.type -> actionConfig = ReplicaCountActionConfig.parse(xcp, index)
                    ActionType.FORCE_MERGE.type -> actionConfig = ForceMergeActionConfig.parse(xcp, index)
                    ActionType.NOTIFICATION.type -> actionConfig = NotificationActionConfig.parse(xcp, index)
                    ActionType.SNAPSHOT.type -> actionConfig = SnapshotActionConfig.parse(xcp, index)
                    ActionType.INDEX_PRIORITY.type -> actionConfig = IndexPriorityActionConfig.parse(xcp, index)
                    ActionType.ALLOCATION.type -> actionConfig = AllocationActionConfig.parse(xcp, index)
                    ActionType.ROLLUP.type -> actionConfig = RollupActionConfig.parse(xcp, index)
                    else -> throw IllegalArgumentException("Invalid field: [$fieldName] found in Action.")
                }
            }

            requireNotNull(actionConfig) { "ActionConfig inside state is null" }

            actionConfig.configTimeout = timeout
            actionConfig.configRetry = retry

            return actionConfig
        }
    }
}
