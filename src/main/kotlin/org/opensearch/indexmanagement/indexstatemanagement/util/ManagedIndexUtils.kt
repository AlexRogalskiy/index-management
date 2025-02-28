/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

@file:Suppress("TooManyFunctions")
@file:JvmName("ManagedIndexUtils")
package org.opensearch.indexmanagement.indexstatemanagement.util

import inet.ipaddr.IPAddressString
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.opensearch.action.DocWriteRequest
import org.opensearch.action.delete.DeleteRequest
import org.opensearch.action.index.IndexRequest
import org.opensearch.action.search.SearchRequest
import org.opensearch.action.support.WriteRequest
import org.opensearch.action.update.UpdateRequest
import org.opensearch.alerting.destination.message.BaseMessage
import org.opensearch.client.Client
import org.opensearch.cluster.metadata.IndexMetadata
import org.opensearch.cluster.service.ClusterService
import org.opensearch.common.settings.Settings
import org.opensearch.common.unit.ByteSizeValue
import org.opensearch.common.unit.TimeValue
import org.opensearch.common.xcontent.ToXContent
import org.opensearch.common.xcontent.XContentFactory
import org.opensearch.index.Index
import org.opensearch.index.query.BoolQueryBuilder
import org.opensearch.index.query.QueryBuilders
import org.opensearch.indexmanagement.IndexManagementPlugin.Companion.INDEX_MANAGEMENT_INDEX
import org.opensearch.indexmanagement.indexstatemanagement.ManagedIndexCoordinator
import org.opensearch.indexmanagement.indexstatemanagement.action.Action
import org.opensearch.indexmanagement.indexstatemanagement.model.ChangePolicy
import org.opensearch.indexmanagement.indexstatemanagement.model.ISMTemplate
import org.opensearch.indexmanagement.indexstatemanagement.model.ManagedIndexConfig
import org.opensearch.indexmanagement.indexstatemanagement.model.ManagedIndexMetaData
import org.opensearch.indexmanagement.indexstatemanagement.model.Policy
import org.opensearch.indexmanagement.indexstatemanagement.model.State
import org.opensearch.indexmanagement.indexstatemanagement.model.Transition
import org.opensearch.indexmanagement.indexstatemanagement.model.action.ActionConfig
import org.opensearch.indexmanagement.indexstatemanagement.model.action.ActionRetry
import org.opensearch.indexmanagement.indexstatemanagement.model.action.RolloverActionConfig
import org.opensearch.indexmanagement.indexstatemanagement.model.action.TransitionsActionConfig
import org.opensearch.indexmanagement.indexstatemanagement.model.coordinator.SweptManagedIndexConfig
import org.opensearch.indexmanagement.indexstatemanagement.model.managedindexmetadata.ActionMetaData
import org.opensearch.indexmanagement.indexstatemanagement.model.managedindexmetadata.PolicyRetryInfoMetaData
import org.opensearch.indexmanagement.indexstatemanagement.model.managedindexmetadata.StateMetaData
import org.opensearch.indexmanagement.indexstatemanagement.settings.ManagedIndexSettings
import org.opensearch.indexmanagement.indexstatemanagement.step.Step
import org.opensearch.indexmanagement.indexstatemanagement.step.delete.AttemptDeleteStep
import org.opensearch.indexmanagement.opensearchapi.optionalISMTemplateField
import org.opensearch.indexmanagement.opensearchapi.optionalTimeField
import org.opensearch.jobscheduler.spi.schedule.IntervalSchedule
import org.opensearch.script.ScriptService
import org.opensearch.search.builder.SearchSourceBuilder
import java.net.InetAddress
import java.time.Instant
import java.time.temporal.ChronoUnit

@Suppress("LongParameterList")
fun managedIndexConfigIndexRequest(
    index: String,
    uuid: String,
    policyID: String,
    jobInterval: Int,
    policy: Policy? = null,
    jobJitter: Double?
): IndexRequest {
    val managedIndexConfig = ManagedIndexConfig(
        jobName = index,
        index = index,
        indexUuid = uuid,
        enabled = true,
        jobSchedule = IntervalSchedule(Instant.now(), jobInterval, ChronoUnit.MINUTES),
        jobLastUpdatedTime = Instant.now(),
        jobEnabledTime = Instant.now(),
        policyID = policyID,
        policy = policy,
        policySeqNo = policy?.seqNo,
        policyPrimaryTerm = policy?.primaryTerm,
        changePolicy = null,
        jobJitter = jobJitter
    )

    return IndexRequest(INDEX_MANAGEMENT_INDEX)
        .id(uuid)
        .create(true)
        .routing(managedIndexConfig.indexUuid)
        .source(managedIndexConfig.toXContent(XContentFactory.jsonBuilder(), ToXContent.EMPTY_PARAMS))
}

fun managedIndexConfigIndexRequest(managedIndexConfig: ManagedIndexConfig): IndexRequest {
    return IndexRequest(INDEX_MANAGEMENT_INDEX)
        .id(managedIndexConfig.indexUuid)
        .setIfPrimaryTerm(managedIndexConfig.primaryTerm)
        .setIfSeqNo(managedIndexConfig.seqNo)
        .routing(managedIndexConfig.indexUuid) // we want job doc and its metadata doc be routed to same shard
        .source(managedIndexConfig.toXContent(XContentFactory.jsonBuilder(), ToXContent.EMPTY_PARAMS))
}

const val METADATA_POST_FIX = "#metadata"

fun managedIndexMetadataID(indexUuid: String) =
    indexUuid + METADATA_POST_FIX

fun revertManagedIndexMetadataID(metadataID: String) =
    metadataID.dropLast(METADATA_POST_FIX.length)

fun managedIndexMetadataIndexRequest(managedIndexMetadata: ManagedIndexMetaData, waitRefresh: Boolean = true, create: Boolean = false): IndexRequest {
    // routing set using managed index's uuid
    // so that metadata doc and managed-index doc are in the same place
    val req = IndexRequest(INDEX_MANAGEMENT_INDEX)
        .id(managedIndexMetadataID(managedIndexMetadata.indexUuid))
        .setIfPrimaryTerm(managedIndexMetadata.primaryTerm)
        .setIfSeqNo(managedIndexMetadata.seqNo)
        .routing(managedIndexMetadata.indexUuid)
        .create(create)
        .source(managedIndexMetadata.toXContent(XContentFactory.jsonBuilder(), ToXContent.EMPTY_PARAMS, true))

    if (waitRefresh)
        return req.setRefreshPolicy(WriteRequest.RefreshPolicy.WAIT_UNTIL)
    return req
}

private fun updateEnabledField(uuid: String, enabled: Boolean, enabledTime: Long?): UpdateRequest {
    val builder = XContentFactory.jsonBuilder()
        .startObject()
        .startObject(ManagedIndexConfig.MANAGED_INDEX_TYPE)
        .optionalTimeField(ManagedIndexConfig.LAST_UPDATED_TIME_FIELD, Instant.now())
        .field(ManagedIndexConfig.ENABLED_FIELD, enabled)
        .field(ManagedIndexConfig.ENABLED_TIME_FIELD, enabledTime)
        .endObject()
        .endObject()
    return UpdateRequest(INDEX_MANAGEMENT_INDEX, uuid).doc(builder)
}

fun updateISMTemplateRequest(policyID: String, ismTemplates: List<ISMTemplate>, seqNo: Long, primaryTerm: Long): UpdateRequest {
    val builder = XContentFactory.jsonBuilder()
        .startObject()
        .startObject(Policy.POLICY_TYPE)
        .optionalISMTemplateField(Policy.ISM_TEMPLATE, ismTemplates)
        .endObject()
        .endObject()
    return UpdateRequest(INDEX_MANAGEMENT_INDEX, policyID).doc(builder)
        .setIfSeqNo(seqNo).setIfPrimaryTerm(primaryTerm)
}

fun updateDisableManagedIndexRequest(uuid: String): UpdateRequest {
    return updateEnabledField(uuid, false, null)
}

fun updateEnableManagedIndexRequest(uuid: String): UpdateRequest {
    return updateEnabledField(uuid, true, Instant.now().toEpochMilli())
}

fun deleteManagedIndexRequest(uuid: String): DeleteRequest {
    return DeleteRequest(INDEX_MANAGEMENT_INDEX, uuid)
}

fun deleteManagedIndexMetadataRequest(uuid: String): DeleteRequest {
    return DeleteRequest(INDEX_MANAGEMENT_INDEX, managedIndexMetadataID(uuid))
}

fun updateManagedIndexRequest(sweptManagedIndexConfig: SweptManagedIndexConfig): UpdateRequest {
    return UpdateRequest(INDEX_MANAGEMENT_INDEX, sweptManagedIndexConfig.uuid)
        .setIfPrimaryTerm(sweptManagedIndexConfig.primaryTerm)
        .setIfSeqNo(sweptManagedIndexConfig.seqNo)
        .doc(getPartialChangePolicyBuilder(sweptManagedIndexConfig.changePolicy))
}

/**
 * Creates DeleteRequests for [ManagedIndexConfig].
 *
 * Finds ManagedIndices that exist in [INDEX_MANAGEMENT_INDEX] that do not exist in the cluster state
 * anymore which means we need to delete the [ManagedIndexConfig].
 *
 * @param currentIndices List of current [IndexMetadata] in cluster state.
 * @param currentManagedIndexConfigs map of IndexUuid to [SweptManagedIndexConfig].
 * @return list of [DocWriteRequest].
 */
fun getDeleteManagedIndexRequests(
    currentIndices: List<IndexMetadata>,
    currentManagedIndexConfigs: Map<String, SweptManagedIndexConfig>
): List<DocWriteRequest<*>> {
    return currentManagedIndexConfigs.filter { currentManagedIndex ->
        !currentIndices.map { it.index.uuid }.contains(currentManagedIndex.key)
    }.map { deleteManagedIndexRequest(it.value.uuid) }
}

// if managed index exist but the index is not existing any more
// then we should delete this managed index
fun getManagedIndicesToDelete(
    currentIndices: List<IndexMetadata>,
    currentManagedIndexConfigs: Map<String, SweptManagedIndexConfig>
): List<Index> {
    val currentIndicesSet = currentIndices.map { it.index }.toSet()
    val managedIndicesSet = currentManagedIndexConfigs.values.map { Index(it.index, it.uuid) }.toSet()
    return (managedIndicesSet - currentIndicesSet).toList()
}

fun getSweptManagedIndexSearchRequest(): SearchRequest {
    val boolQueryBuilder = BoolQueryBuilder().filter(QueryBuilders.existsQuery(ManagedIndexConfig.MANAGED_INDEX_TYPE))
    return SearchRequest()
        .indices(INDEX_MANAGEMENT_INDEX)
        .source(
            SearchSourceBuilder.searchSource()
                // TODO: Get all ManagedIndices at once or split into searchAfter queries?
                .size(ManagedIndexCoordinator.MAX_HITS)
                .seqNoAndPrimaryTerm(true)
                .fetchSource(
                    arrayOf(
                        "${ManagedIndexConfig.MANAGED_INDEX_TYPE}.${ManagedIndexConfig.INDEX_FIELD}",
                        "${ManagedIndexConfig.MANAGED_INDEX_TYPE}.${ManagedIndexConfig.INDEX_UUID_FIELD}",
                        "${ManagedIndexConfig.MANAGED_INDEX_TYPE}.${ManagedIndexConfig.POLICY_ID_FIELD}",
                        "${ManagedIndexConfig.MANAGED_INDEX_TYPE}.${ManagedIndexConfig.CHANGE_POLICY_FIELD}"
                    ),
                    emptyArray()
                )
                .query(boolQueryBuilder)
        )
}

@Suppress("ReturnCount")
fun Transition.evaluateConditions(
    indexCreationDate: Instant,
    numDocs: Long?,
    indexSize: ByteSizeValue?,
    transitionStartTime: Instant,
    rolloverDate: Instant?,
): Boolean {
    // If there are no conditions, treat as always true
    if (this.conditions == null) return true

    if (this.conditions.docCount != null && numDocs != null) {
        return this.conditions.docCount <= numDocs
    }

    if (this.conditions.indexAge != null) {
        val indexCreationDateMilli = indexCreationDate.toEpochMilli()
        if (indexCreationDateMilli == -1L) return false // transitions cannot currently be ORd like rollover, so we must return here
        val elapsedTime = Instant.now().toEpochMilli() - indexCreationDateMilli
        return this.conditions.indexAge.millis <= elapsedTime
    }

    if (this.conditions.size != null && indexSize != null) {
        return this.conditions.size <= indexSize
    }

    if (this.conditions.cron != null) {
        // If a cron pattern matches the time between the start of "attempt_transition" to now then we consider it meeting the condition
        return this.conditions.cron.getNextExecutionTime(transitionStartTime) <= Instant.now()
    }

    if (this.conditions.rolloverAge != null) {
        val rolloverDateMilli = rolloverDate?.toEpochMilli() ?: return false
        val elapsedTime = Instant.now().toEpochMilli() - rolloverDateMilli
        return this.conditions.rolloverAge.millis <= elapsedTime
    }

    // We should never reach this
    return false
}

fun Transition.hasStatsConditions(): Boolean = this.conditions?.docCount != null || this.conditions?.size != null

@Suppress("ReturnCount", "ComplexMethod", "ComplexCondition")
fun RolloverActionConfig.evaluateConditions(
    indexAgeTimeValue: TimeValue,
    numDocs: Long,
    indexSize: ByteSizeValue,
    primaryShardSize: ByteSizeValue
): Boolean {
    if (this.minDocs == null &&
        this.minAge == null &&
        this.minSize == null &&
        this.minPrimaryShardSize == null
    ) {
        // If no conditions specified we default to true
        return true
    }

    if (this.minDocs != null) {
        if (this.minDocs <= numDocs) return true
    }

    if (this.minAge != null) {
        if (this.minAge.millis <= indexAgeTimeValue.millis) return true
    }

    if (this.minSize != null) {
        if (this.minSize <= indexSize) return true
    }

    if (this.minPrimaryShardSize != null) {
        if (this.minPrimaryShardSize <= primaryShardSize) return true
    }

    // return false if none of the conditions were true.
    return false
}

fun Policy.getStateToExecute(managedIndexMetaData: ManagedIndexMetaData): State? {
    if (managedIndexMetaData.transitionTo != null) {
        return this.states.find { it.name == managedIndexMetaData.transitionTo }
    }
    return this.states.find { managedIndexMetaData.stateMetaData != null && it.name == managedIndexMetaData.stateMetaData.name }
}

fun State.getActionToExecute(
    clusterService: ClusterService,
    scriptService: ScriptService,
    client: Client,
    settings: Settings,
    managedIndexMetaData: ManagedIndexMetaData
): Action? {
    var actionConfig: ActionConfig?

    // If we are transitioning to this state get the first action in the state
    // If the action/actionIndex are null it means we just initialized and should get the first action from the state
    if (managedIndexMetaData.transitionTo != null || managedIndexMetaData.actionMetaData == null) {
        actionConfig = this.actions.firstOrNull() ?: TransitionsActionConfig(this.transitions)
    } else if (managedIndexMetaData.actionMetaData.name == ActionConfig.ActionType.TRANSITION.type) {
        // If the current action is transition and we do not have a transitionTo set then we should be in Transition
        actionConfig = TransitionsActionConfig(this.transitions)
    } else {
        // Get the current actionConfig that is in the ManagedIndexMetaData
        actionConfig = this.actions.filterIndexed { index, config ->
            index == managedIndexMetaData.actionMetaData.index && config.type.type == managedIndexMetaData.actionMetaData.name
        }.firstOrNull()
        if (actionConfig == null) return null

        // TODO: Refactor so we can get isLastStep from somewhere besides an instantiated Action class so we can simplify this to a when block
        // If stepCompleted is true and this is the last step of the action then we should get the next action
        if (managedIndexMetaData.stepMetaData != null && managedIndexMetaData.stepMetaData.stepStatus == Step.StepStatus.COMPLETED) {
            val action = actionConfig.toAction(clusterService, scriptService, client, settings, managedIndexMetaData)
            if (action.isLastStep(managedIndexMetaData.stepMetaData.name)) {
                actionConfig = this.actions.getOrNull(managedIndexMetaData.actionMetaData.index + 1) ?: TransitionsActionConfig(this.transitions)
            }
        }
    }

    return actionConfig.toAction(clusterService, scriptService, client, settings, managedIndexMetaData)
}

fun State.getUpdatedStateMetaData(managedIndexMetaData: ManagedIndexMetaData): StateMetaData {
    // If the current ManagedIndexMetaData state does not match this state, it means we transitioned and need to update the startStartTime
    val stateMetaData = managedIndexMetaData.stateMetaData
    return when {
        stateMetaData == null -> StateMetaData(this.name, Instant.now().toEpochMilli())
        stateMetaData.name != this.name -> StateMetaData(this.name, Instant.now().toEpochMilli())
        else -> stateMetaData
    }
}

fun Action.getUpdatedActionMetaData(managedIndexMetaData: ManagedIndexMetaData, state: State): ActionMetaData {
    val stateMetaData = managedIndexMetaData.stateMetaData
    val actionMetaData = managedIndexMetaData.actionMetaData

    return when {
        // start a new action
        stateMetaData?.name != state.name ->
            ActionMetaData(this.type.type, Instant.now().toEpochMilli(), this.config.actionIndex, false, 0, 0, null)
        actionMetaData?.index != this.config.actionIndex ->
            ActionMetaData(this.type.type, Instant.now().toEpochMilli(), this.config.actionIndex, false, 0, 0, null)
        // RetryAPI will reset startTime to null for actionMetaData and we'll reset it to "now" here
        else -> actionMetaData.copy(startTime = actionMetaData.startTime ?: Instant.now().toEpochMilli())
    }
}

fun Action.shouldBackoff(actionMetaData: ActionMetaData?, actionRetry: ActionRetry?): Pair<Boolean, Long?>? {
    return this.config.configRetry?.backoff?.shouldBackoff(actionMetaData, actionRetry)
}

@Suppress("ReturnCount")
fun Action.hasTimedOut(actionMetaData: ActionMetaData?): Boolean {
    if (actionMetaData?.startTime == null) return false
    val configTimeout = this.config.configTimeout
    if (configTimeout == null) return false
    return (Instant.now().toEpochMilli() - actionMetaData.startTime) > configTimeout.timeout.millis
}

@Suppress("ReturnCount")
fun ManagedIndexMetaData.getStartingManagedIndexMetaData(
    state: State?,
    action: Action?,
    step: Step?
): ManagedIndexMetaData {
    // State can be null if the transition_to state or the current metadata state is not found in the policy
    if (state == null) {
        return this.copy(
            policyRetryInfo = PolicyRetryInfoMetaData(true, 0),
            info = mapOf("message" to "Failed to find state=${this.transitionTo ?: this.stateMetaData} in policy=${this.policyID}")
        )
    }

    // Action can only be null if the metadata action type/actionIndex do not match in state.actions
    // Step can only be null if Action is null
    if (action == null || step == null) {
        return this.copy(
            policyRetryInfo = PolicyRetryInfoMetaData(true, 0),
            info = mapOf("message" to "Failed to find action=${this.actionMetaData} in state=${this.stateMetaData}")
        )
    }

    val updatedStateMetaData = state.getUpdatedStateMetaData(this)
    val updatedActionMetaData = action.getUpdatedActionMetaData(this, state)
    val updatedStepMetaData = step.getStartingStepMetaData()

    return this.copy(
        stateMetaData = updatedStateMetaData,
        actionMetaData = updatedActionMetaData,
        stepMetaData = updatedStepMetaData,
        info = mapOf("message" to "Starting action ${action.type} and working on ${step.name}")
    )
}

@Suppress("ReturnCount")
fun ManagedIndexMetaData.getCompletedManagedIndexMetaData(
    action: Action,
    step: Step
): ManagedIndexMetaData {
    val updatedStepMetaData = step.getUpdatedManagedIndexMetaData(this)
    val actionMetaData = updatedStepMetaData.actionMetaData ?: return this.copy(
        policyRetryInfo = PolicyRetryInfoMetaData(true, 0),
        info = mapOf("message" to "Failed due to ActionMetaData being null")
    )

    val updatedActionMetaData = if (updatedStepMetaData.stepMetaData?.stepStatus == Step.StepStatus.FAILED) {
        when {
            action.config.configRetry == null -> actionMetaData.copy(failed = true)
            actionMetaData.consumedRetries >= action.config.configRetry!!.count -> actionMetaData.copy(failed = true)
            else -> actionMetaData.copy(
                failed = false,
                consumedRetries = actionMetaData.consumedRetries + 1,
                lastRetryTime = Instant.now().toEpochMilli()
            )
        }
    } else {
        actionMetaData
    }

    return this.copy(
        policyCompleted = updatedStepMetaData.policyCompleted,
        rolledOver = updatedStepMetaData.rolledOver,
        actionMetaData = updatedActionMetaData,
        stepMetaData = updatedStepMetaData.stepMetaData,
        transitionTo = updatedStepMetaData.transitionTo,
        policyRetryInfo = updatedStepMetaData.policyRetryInfo,
        info = updatedStepMetaData.info
    )
}

val ManagedIndexMetaData.isSuccessfulDelete: Boolean
    get() = (this.actionMetaData?.name == ActionConfig.ActionType.DELETE.type && !this.actionMetaData.failed) &&
        (this.stepMetaData?.name == AttemptDeleteStep.name && this.stepMetaData.stepStatus == Step.StepStatus.COMPLETED) &&
        (this.policyRetryInfo?.failed != true)

val ManagedIndexMetaData.isFailed: Boolean
    get() {
        // If PolicyRetryInfo is failed then the ManagedIndex has failed.
        if (this.policyRetryInfo?.failed == true) return true
        // If ActionMetaData is not null and some action is failed. Then the ManagedIndex has failed.
        if (this.actionMetaData?.failed == true) return true
        return false
    }

// Adding predicate extension to allow cleaner checks since policyCompleted is nullable
val ManagedIndexMetaData.isPolicyCompleted: Boolean
    get() = this.policyCompleted == true

/**
 * We will change the policy if a change policy exists and if we are currently in
 * a Transitions action (which means we're safely at the end of a state). If a
 * transitionTo exists on the [ManagedIndexMetaData] it should still be fine to
 * change policy as we have not actually transitioned yet. If the next action is Transition
 * or if the rest API determined it was "safe", meaning the new policy has the same structure
 * of the current state, it should be safe to immediately change (even in the middle of the state).
 *
 * @param managedIndexMetaData current [ManagedIndexMetaData]
 * @return {@code true} if we should change policy, {@code false} if not
 */
@Suppress("ReturnCount")
fun ManagedIndexConfig.shouldChangePolicy(managedIndexMetaData: ManagedIndexMetaData, actionToExecute: Action?): Boolean {
    if (this.changePolicy == null) {
        return false
    }

    if (this.changePolicy.isSafe) {
        return true
    }

    // we need this in so that we can change policy before the first transition happens so policy doesnt get completed
    // before we have a chance to change policy
    if (actionToExecute?.type == ActionConfig.ActionType.TRANSITION) {
        return true
    }

    if (managedIndexMetaData.actionMetaData?.name != ActionConfig.ActionType.TRANSITION.type) {
        return false
    }

    return true
}

fun ManagedIndexMetaData.hasVersionConflict(managedIndexConfig: ManagedIndexConfig): Boolean =
    this.policySeqNo != managedIndexConfig.policySeqNo || this.policyPrimaryTerm != managedIndexConfig.policyPrimaryTerm

fun ManagedIndexConfig.hasDifferentJobInterval(jobInterval: Int): Boolean {
    val schedule = this.schedule
    when (schedule) {
        is IntervalSchedule -> {
            return schedule.interval != jobInterval
        }
    }
    return false
}

/**
 * A policy is safe to change to a new policy when each policy has the current state
 * the [ManagedIndexConfig] is in and that state has the same actions in the same order.
 * This allows simple things like configuration updates to happen which won't break the execution/contract
 * between [ManagedIndexMetaData] and [ManagedIndexConfig] as the metadata only knows about the current state.
 * We never consider a policy safe to immediately change if the ChangePolicy contains a state to transition to
 * as this could transition a user into a different state from the middle of the current state which we do not
 * want to allow.
 *
 * @param stateName the name of the state the [ManagedIndexConfig] is currently in
 * @param newPolicy the new (actual data model) policy we will eventually try to change to
 * @param changePolicy the change policy to change to
 * @return if its safe to change
 */
@Suppress("ReturnCount")
fun Policy.isSafeToChange(stateName: String?, newPolicy: Policy, changePolicy: ChangePolicy): Boolean {
    // if stateName is null it means we either have not initialized the job (no metadata to pull stateName from)
    // or we failed to load the initial policy, both cases its safe to change the policy
    if (stateName == null) return true
    if (changePolicy.state != null) return false
    val currentState = this.states.find { it.name == stateName }
    val newState = newPolicy.states.find { it.name == stateName }
    if (currentState == null || newState == null) {
        return false
    }

    if (currentState.actions.size != newState.actions.size) {
        return false
    }

    currentState.actions.forEachIndexed { index, action ->
        val newStateAction = newState.actions[index]
        if (action.type != newStateAction.type) return@isSafeToChange false
    }

    return true
}

/**
 * Disallowed actions are ones that are not specified in the [ManagedIndexSettings.ALLOW_LIST] setting.
 */
fun Policy.getDisallowedActions(allowList: List<String>): List<String> {
    val allowListSet = allowList.toSet()
    val disallowedActions = mutableListOf<String>()
    this.states.forEach { state ->
        state.actions.forEach { actionConfig ->
            if (!allowListSet.contains(actionConfig.type.type)) {
                disallowedActions.add(actionConfig.type.type)
            }
        }
    }
    return disallowedActions.distinct()
}

/**
 * Allowed actions are ones that are specified in the [ManagedIndexSettings.ALLOW_LIST] setting.
 */
fun Action.isAllowed(allowList: List<String>): Boolean = allowList.contains(this.type.type)

/**
 * Check if cluster state metadata has been moved to config index
 *
 * log warning if remaining cluster state metadata has newer last_updated_time
 */
fun isMetadataMoved(
    clusterStateMetadata: ManagedIndexMetaData?,
    configIndexMetadata: Any?,
    logger: Logger
): Boolean {
    if (clusterStateMetadata != null) {
        if (configIndexMetadata == null) return false

        // compare last updated time between 2 metadatas
        val t1 = clusterStateMetadata.stepMetaData?.startTime
        val t2 = when (configIndexMetadata) {
            is ManagedIndexMetaData? -> configIndexMetadata.stepMetaData?.startTime
            is Map<*, *>? -> {
                val stepMetadata = configIndexMetadata["step"] as Map<String, Any>?
                stepMetadata?.get("start_time")
            }
            else -> null
        } as Long?
        if (t1 != null && t2 != null && t1 > t2) {
            logger.warn("Cluster state metadata get updates after moved for [${clusterStateMetadata.index}]")
        }
    }
    return true
}

private val baseMessageLogger = LogManager.getLogger(BaseMessage::class.java)

fun BaseMessage.isHostInDenylist(networks: List<String>): Boolean {
    val ipStr = IPAddressString(this.uri.host)
    for (network in networks) {
        val netStr = IPAddressString(network)
        if (netStr.contains(ipStr)) {
            baseMessageLogger.error("Host: {} resolves to: {} which is in denylist: {}.", uri.host, InetAddress.getByName(uri.host), netStr)
            return true
        }
    }
    return false
}
