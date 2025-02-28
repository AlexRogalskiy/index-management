/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.indexmanagement.indexstatemanagement.step

import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.doAnswer
import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.whenever
import kotlinx.coroutines.runBlocking
import org.opensearch.action.ActionListener
import org.opensearch.action.support.master.AcknowledgedResponse
import org.opensearch.client.AdminClient
import org.opensearch.client.Client
import org.opensearch.client.IndicesAdminClient
import org.opensearch.cluster.service.ClusterService
import org.opensearch.indexmanagement.indexstatemanagement.model.ManagedIndexMetaData
import org.opensearch.indexmanagement.indexstatemanagement.model.action.IndexPriorityActionConfig
import org.opensearch.indexmanagement.indexstatemanagement.step.indexpriority.AttemptSetIndexPriorityStep
import org.opensearch.test.OpenSearchTestCase
import org.opensearch.transport.RemoteTransportException
import kotlin.IllegalArgumentException

class AttemptSetIndexPriorityStepTests : OpenSearchTestCase() {

    private val clusterService: ClusterService = mock()

    fun `test set priority step sets step status to completed when successful`() {
        val acknowledgedResponse = AcknowledgedResponse(true)
        val client = getClient(getAdminClient(getIndicesAdminClient(acknowledgedResponse, null)))

        runBlocking {
            val indexPriorityActionConfig = IndexPriorityActionConfig(50, 0)
            val managedIndexMetaData = ManagedIndexMetaData("test", "indexUuid", "policy_id", null, null, null, null, null, null, null, null, null, null)
            val attemptSetPriorityStep = AttemptSetIndexPriorityStep(clusterService, client, indexPriorityActionConfig, managedIndexMetaData)
            attemptSetPriorityStep.execute()
            val updatedManagedIndexMetaData = attemptSetPriorityStep.getUpdatedManagedIndexMetaData(managedIndexMetaData)
            assertEquals("Step status is not COMPLETED", Step.StepStatus.COMPLETED, updatedManagedIndexMetaData.stepMetaData?.stepStatus)
        }
    }

    fun `test set priority step sets step status to failed when not acknowledged`() {
        val acknowledgedResponse = AcknowledgedResponse(false)
        val client = getClient(getAdminClient(getIndicesAdminClient(acknowledgedResponse, null)))

        runBlocking {
            val indexPriorityActionConfig = IndexPriorityActionConfig(50, 0)
            val managedIndexMetaData = ManagedIndexMetaData("test", "indexUuid", "policy_id", null, null, null, null, null, null, null, null, null, null)
            val attemptSetPriorityStep = AttemptSetIndexPriorityStep(clusterService, client, indexPriorityActionConfig, managedIndexMetaData)
            attemptSetPriorityStep.execute()
            val updatedManagedIndexMetaData = attemptSetPriorityStep.getUpdatedManagedIndexMetaData(managedIndexMetaData)
            assertEquals("Step status is not FAILED", Step.StepStatus.FAILED, updatedManagedIndexMetaData.stepMetaData?.stepStatus)
        }
    }

    fun `test set priority step sets step status to failed when error thrown`() {
        val exception = IllegalArgumentException("example")
        val client = getClient(getAdminClient(getIndicesAdminClient(null, exception)))

        runBlocking {
            val indexPriorityActionConfig = IndexPriorityActionConfig(50, 0)
            val managedIndexMetaData = ManagedIndexMetaData("test", "indexUuid", "policy_id", null, null, null, null, null, null, null, null, null, null)
            val attemptSetPriorityStep = AttemptSetIndexPriorityStep(clusterService, client, indexPriorityActionConfig, managedIndexMetaData)
            attemptSetPriorityStep.execute()
            val updatedManagedIndexMetaData = attemptSetPriorityStep.getUpdatedManagedIndexMetaData(managedIndexMetaData)
            logger.info(updatedManagedIndexMetaData)
            assertEquals("Step status is not FAILED", Step.StepStatus.FAILED, updatedManagedIndexMetaData.stepMetaData?.stepStatus)
        }
    }

    fun `test set priority step sets step status to failed when remote transport error thrown`() {
        val exception = RemoteTransportException("rte", IllegalArgumentException("nested"))
        val client = getClient(getAdminClient(getIndicesAdminClient(null, exception)))

        runBlocking {
            val indexPriorityActionConfig = IndexPriorityActionConfig(50, 0)
            val managedIndexMetaData = ManagedIndexMetaData("test", "indexUuid", "policy_id", null, null, null, null, null, null, null, null, null, null)
            val attemptSetPriorityStep = AttemptSetIndexPriorityStep(clusterService, client, indexPriorityActionConfig, managedIndexMetaData)
            attemptSetPriorityStep.execute()
            val updatedManagedIndexMetaData = attemptSetPriorityStep.getUpdatedManagedIndexMetaData(managedIndexMetaData)
            logger.info(updatedManagedIndexMetaData)
            assertEquals("Step status is not FAILED", Step.StepStatus.FAILED, updatedManagedIndexMetaData.stepMetaData?.stepStatus)
            assertEquals("Did not get cause from nested exception", "nested", updatedManagedIndexMetaData.info!!["cause"])
        }
    }

    private fun getClient(adminClient: AdminClient): Client = mock { on { admin() } doReturn adminClient }
    private fun getAdminClient(indicesAdminClient: IndicesAdminClient): AdminClient = mock { on { indices() } doReturn indicesAdminClient }
    private fun getIndicesAdminClient(acknowledgedResponse: AcknowledgedResponse?, exception: Exception?): IndicesAdminClient {
        assertTrue("Must provide one and only one response or exception", (acknowledgedResponse != null).xor(exception != null))
        return mock {
            doAnswer { invocationOnMock ->
                val listener = invocationOnMock.getArgument<ActionListener<AcknowledgedResponse>>(1)
                if (acknowledgedResponse != null) listener.onResponse(acknowledgedResponse)
                else listener.onFailure(exception)
            }.whenever(this.mock).updateSettings(any(), any())
        }
    }
}
