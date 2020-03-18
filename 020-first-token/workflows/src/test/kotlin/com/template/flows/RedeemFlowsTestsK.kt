package com.template.flows

import com.google.common.collect.ImmutableList
import com.template.states.TokenStateK
import net.corda.core.utilities.getOrThrow
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNetworkNotarySpec
import net.corda.testing.node.MockNetworkParameters
import net.corda.testing.node.TestCordapp
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RedeemFlowsTestsK {
    private val network = MockNetwork(MockNetworkParameters()
            .withNotarySpecs(ImmutableList.of(MockNetworkNotarySpec(Constants.desiredNotary)))
            .withCordappsForAllNodes(listOf(
                    TestCordapp.findCordapp("com.template.contracts"),
                    TestCordapp.findCordapp("com.template.flows"))))
    private val alice = network.createNode()
    private val bob = network.createNode()
    private val carly = network.createNode()
    private val dan = network.createNode()

    init {
        listOf(alice, bob, carly).forEach {
            it.registerInitiatedFlow(IssueFlowsK.Responder::class.java)
        }
    }

    @Before
    fun setup() = network.runNetwork()

    @After
    fun tearDown() = network.stopNodes()

    @Test
    fun `SignedTransaction returned by the flow is signed by both the issuer and the holder`() {
        val tokens = alice.issueTokens(network, listOf(NodeHolding(bob, 10L)))

        val flow = RedeemFlowsK.Initiator(tokens)
        val future = bob.startFlow(flow)
        network.runNetwork()
        val signedTx = future.getOrThrow()
        signedTx.verifySignaturesExcept(bob.info.legalIdentities[0].owningKey)
        signedTx.verifySignaturesExcept(alice.info.legalIdentities[0].owningKey)
    }

    @Test
    fun `SignedTransaction returned by the flow is signed by both issuers and the holder`() {
        val tokens = alice.issueTokens(network, listOf(NodeHolding(bob, 10L)))
                .plus(carly.issueTokens(network, listOf(NodeHolding(bob, 20L))))

        val flow = RedeemFlowsK.Initiator(tokens)
        val future = bob.startFlow(flow)
        network.runNetwork()
        val signedTx = future.getOrThrow()
        signedTx.verifySignaturesExcept(listOf(bob.info.singleIdentity().owningKey, carly.info.singleIdentity().owningKey))
        signedTx.verifySignaturesExcept(listOf(alice.info.singleIdentity().owningKey, carly.info.singleIdentity().owningKey))
        signedTx.verifySignaturesExcept(listOf(alice.info.singleIdentity().owningKey, bob.info.singleIdentity().owningKey))
    }

    @Test
    fun `flow records a transaction in issuer and holder transaction storages only`() {
        val tokens = alice.issueTokens(network, listOf(NodeHolding(bob, 10L)))

        val flow = RedeemFlowsK.Initiator(tokens)
        val future = bob.startFlow(flow)
        network.runNetwork()
        val signedTx = future.getOrThrow()

        // We check the recorded transaction in both transaction storages.
        for (node in listOf(alice, bob)) {
            assertEquals(signedTx, node.services.validatedTransactions.getTransaction(signedTx.id))
        }
        for (node in listOf(carly, dan)) {
            assertNull(node.services.validatedTransactions.getTransaction(signedTx.id))
        }
    }

    @Test
    fun `flow records a transaction in both issuers and holder transaction storages only`() {
        val tokens = alice.issueTokens(network, listOf(NodeHolding(bob, 10L)))
                .plus(carly.issueTokens(network, listOf(NodeHolding(bob, 20L))))

        val flow = RedeemFlowsK.Initiator(tokens)
        val future = bob.startFlow(flow)
        network.runNetwork()
        val signedTx = future.getOrThrow()

        // We check the recorded transaction in both transaction storages.
        for (node in listOf(alice, bob, carly)) {
            assertEquals(signedTx, node.services.validatedTransactions.getTransaction(signedTx.id))
        }
        for (node in listOf(dan)) {
            assertNull(node.services.validatedTransactions.getTransaction(signedTx.id))
        }
    }

    @Test
    fun `flow records a transaction in issuer and both holder transaction storages`() {
        val tokens = alice.issueTokens(network, listOf(
                NodeHolding(bob, 10L),
                NodeHolding(carly, 20L)
        ))

        val flow = RedeemFlowsK.Initiator(tokens)
        val future = bob.startFlow(flow)
        network.runNetwork()
        val signedTx = future.getOrThrow()

        // We check the recorded transaction in transaction storages.
        for (node in listOf(alice, bob, carly)) {
            assertEquals(signedTx, node.services.validatedTransactions.getTransaction(signedTx.id))
        }
        assertNull(dan.services.validatedTransactions.getTransaction(signedTx.id))
    }

    @Test
    fun `recorded transaction has a single input, the token state, and no outputs`() {
        val expected = createFrom(alice, bob, 10L)
        val tokens = alice.issueTokens(network, listOf(NodeHolding(bob, 10L)))

        val flow = RedeemFlowsK.Initiator(tokens)
        val future = bob.startFlow(flow)
        network.runNetwork()
        val signedTx = future.getOrThrow()

        // We check the recorded transaction in both vaults.
        for (node in listOf(alice, bob)) {
            val recordedTx = node.services.validatedTransactions.getTransaction(signedTx.id)!!
            val txInputs = recordedTx.tx.inputs
            assertEquals(1, txInputs.size)
            assertEquals(expected, node.services.toStateAndRef<TokenStateK>(txInputs[0]).state.data)
            assertTrue(recordedTx.tx.outputs.isEmpty())
        }
    }

    @Test
    fun `there is no recorded state after redeem`() {
        val expected = createFrom(alice, bob, 10L)
        val tokens = alice.issueTokens(network, listOf(NodeHolding(bob, 10L)))

        val flow = RedeemFlowsK.Initiator(tokens)
        val future = bob.startFlow(flow)
        network.runNetwork()
        future.getOrThrow()

        // We check the state was consumed in both vaults.
        alice.assertHasStatesInVault(listOf())
        bob.assertHasStatesInVault(listOf())
    }

    @Test
    fun `recorded transaction has many inputs, the token states, and no outputs`() {
        val expected1 = createFrom(alice, bob, 10L)
        val expected2 = createFrom(alice, carly, 20L)
        val tokens = alice.issueTokens(network, listOf(
                NodeHolding(bob, 10L),
                NodeHolding(carly, 20L)))

        val flow = RedeemFlowsK.Initiator(tokens)
        val future = bob.startFlow(flow)
        network.runNetwork()
        val signedTx = future.getOrThrow()

        // We check the recorded transaction in the 3 vaults.
        for (node in listOf(alice, bob, carly)) {
            val recordedTx = node.services.validatedTransactions.getTransaction(signedTx.id)!!
            val txInputs = recordedTx.tx.inputs
            assertEquals(2, txInputs.size)
            assertEquals(expected1, node.services.toStateAndRef<TokenStateK>(txInputs[0]).state.data)
            assertEquals(expected2, node.services.toStateAndRef<TokenStateK>(txInputs[1]).state.data)
            assertTrue(recordedTx.tx.outputs.isEmpty())
        }
    }

    @Test
    fun `there are no recorded states after redeem`() {
        val tokens = alice.issueTokens(network, listOf(
                NodeHolding(bob, 10L),
                NodeHolding(carly, 20L)))

        val flow = RedeemFlowsK.Initiator(tokens)
        val future = bob.startFlow(flow)
        network.runNetwork()
        future.get()

        // We check the recorded state in the 4 vaults.
        alice.assertHasStatesInVault(listOf())
        bob.assertHasStatesInVault(listOf())
        carly.assertHasStatesInVault(listOf())
        dan.assertHasStatesInVault(listOf())
    }

}