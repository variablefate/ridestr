package com.ridestr.common.state

/**
 * Result of an action execution.
 */
sealed class ActionResult {
    /** Action completed successfully */
    data class Success(val updatedContext: RideContext) : ActionResult()

    /** Action failed with an error */
    data class Failure(val error: String, val recoverable: Boolean = true) : ActionResult()

    /** Action requires async work - returns immediately, completion notified later */
    data class Async(val operationId: String) : ActionResult()
}

/**
 * Type alias for action functions.
 *
 * Actions are side effects executed when a transition occurs.
 * They receive the context and event, and return an ActionResult.
 *
 * Actions can:
 * - Update the context (return Success with new context)
 * - Fail (return Failure)
 * - Trigger async work (return Async)
 */
typealias Action = suspend (context: RideContext, event: RideEvent) -> ActionResult

/**
 * Named action functions for the ride state machine.
 *
 * Actions are side effects that occur during state transitions.
 * They follow the AtoB pattern of named actions that can be
 * referenced in the transition table.
 *
 * IMPORTANT: Actions in this class are PURE context updates.
 * Actual side effects (publishing events, wallet operations) are
 * handled by the ViewModel via the ActionHandler interface.
 *
 * Benefits:
 * - Testable in isolation
 * - Clear separation of state logic from I/O
 * - ViewModels provide concrete implementations
 */
object RideActions {

    /**
     * Interface for ViewModel to provide concrete action implementations.
     * The state machine calls these to perform actual I/O operations.
     */
    interface ActionHandler {
        /** Called when driver is assigned to a ride */
        suspend fun onDriverAssigned(context: RideContext, event: RideEvent.Accept)

        /** Called when escrow should be locked */
        suspend fun onLockEscrow(context: RideContext, event: RideEvent.Confirm): ActionResult

        /** Called when ride is cancelled */
        suspend fun onCancellation(context: RideContext, event: RideEvent.Cancel)

        /** Called when timeout occurred */
        suspend fun onTimeout(context: RideContext, event: RideEvent)

        /** Called after PIN verification succeeds */
        suspend fun onPinVerified(context: RideContext, event: RideEvent.VerifyPin)

        /** Called when PIN brute force limit is reached */
        suspend fun onPinBruteForce(context: RideContext, event: RideEvent.VerifyPin)

        /** Called when payment should be settled */
        suspend fun onSettlePayment(context: RideContext, event: RideEvent.Complete): ActionResult
    }

    /**
     * Registry of action implementations.
     * These return context updates; ViewModels handle actual I/O.
     */
    private val contextUpdaters: Map<String, suspend (RideContext, RideEvent) -> RideContext> = mapOf(
        "assignDriver" to ::assignDriverContext,
        "lockEscrow" to ::lockEscrowContext,
        "startRideAfterPin" to ::startRideAfterPinContext,
        "settlePayment" to ::settlePaymentContext
    )

    /**
     * Update context when driver is assigned.
     */
    private suspend fun assignDriverContext(context: RideContext, event: RideEvent): RideContext {
        return when (event) {
            is RideEvent.Accept -> context.withDriver(
                driverPubkey = event.driverPubkey,
                driverWalletPubkey = event.walletPubkey,
                driverMintUrl = event.mintUrl
            )
            else -> context
        }
    }

    /**
     * Update context when escrow is locked.
     */
    private suspend fun lockEscrowContext(context: RideContext, event: RideEvent): RideContext {
        return when (event) {
            is RideEvent.Confirm -> context.withConfirmation(
                confirmationEventId = event.confirmationEventId,
                precisePickup = event.precisePickup,
                escrowLocked = event.escrowToken != null,
                escrowToken = event.escrowToken,
                paymentHash = event.paymentHash
            )
            else -> context
        }
    }

    /**
     * Update context after PIN verification.
     */
    private suspend fun startRideAfterPinContext(context: RideContext, event: RideEvent): RideContext {
        return when (event) {
            is RideEvent.VerifyPin -> context.withPinAttempt(verified = true)
            else -> context
        }
    }

    /**
     * Update context after payment settlement.
     */
    private suspend fun settlePaymentContext(context: RideContext, event: RideEvent): RideContext {
        // Context updates for settlement handled by handler
        return context
    }

    /**
     * Execute an action by name, updating the context.
     *
     * @param actionName The name of the action to execute
     * @param context Current ride context
     * @param event The triggering event
     * @param handler Optional handler for I/O operations
     * @return Updated context after action execution
     */
    suspend fun execute(
        actionName: String?,
        context: RideContext,
        event: RideEvent,
        handler: ActionHandler? = null
    ): ActionResult {
        if (actionName == null) {
            return ActionResult.Success(context)
        }

        // First, update context
        val updatedContext = contextUpdaters[actionName]?.invoke(context, event) ?: context

        // Then, call handler for I/O (if provided)
        return when (actionName) {
            "assignDriver" -> {
                if (handler != null && event is RideEvent.Accept) {
                    handler.onDriverAssigned(updatedContext, event)
                }
                ActionResult.Success(updatedContext)
            }

            "lockEscrow" -> {
                if (handler != null && event is RideEvent.Confirm) {
                    handler.onLockEscrow(updatedContext, event)
                } else {
                    ActionResult.Success(updatedContext)
                }
            }

            "notifyCancellation" -> {
                if (handler != null && event is RideEvent.Cancel) {
                    handler.onCancellation(updatedContext, event)
                }
                ActionResult.Success(updatedContext)
            }

            "notifyTimeout" -> {
                handler?.onTimeout(updatedContext, event)
                ActionResult.Success(updatedContext)
            }

            "startRideAfterPin" -> {
                if (handler != null && event is RideEvent.VerifyPin) {
                    handler.onPinVerified(updatedContext, event)
                }
                ActionResult.Success(updatedContext)
            }

            "notifyPinBruteForce" -> {
                if (handler != null && event is RideEvent.VerifyPin) {
                    handler.onPinBruteForce(updatedContext, event)
                }
                ActionResult.Success(updatedContext)
            }

            "settlePayment" -> {
                if (handler != null && event is RideEvent.Complete) {
                    handler.onSettlePayment(updatedContext, event)
                } else {
                    ActionResult.Success(updatedContext)
                }
            }

            else -> {
                // Unknown action - log warning but don't fail
                ActionResult.Success(updatedContext)
            }
        }
    }

    /**
     * Get a human-readable description of an action.
     */
    fun describe(actionName: String): String {
        return when (actionName) {
            "assignDriver" -> "Assign driver to the ride"
            "lockEscrow" -> "Lock HTLC escrow payment"
            "notifyCancellation" -> "Notify parties of cancellation"
            "notifyTimeout" -> "Notify parties of timeout"
            "startRideAfterPin" -> "Start ride after PIN verification"
            "notifyPinBruteForce" -> "Handle PIN brute force attempt"
            "settlePayment" -> "Settle payment with escrow"
            else -> "Unknown action: $actionName"
        }
    }
}
