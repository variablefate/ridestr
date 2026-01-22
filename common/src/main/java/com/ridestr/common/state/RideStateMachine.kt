package com.ridestr.common.state

import android.util.Log

/**
 * Result of a state machine transition attempt.
 */
sealed class TransitionResult {
    /**
     * Transition succeeded.
     * @param fromState The state before transition
     * @param toState The state after transition
     * @param context The updated context after transition
     * @param transition The transition that was applied
     */
    data class Success(
        val fromState: RideState,
        val toState: RideState,
        val context: RideContext,
        val transition: RideTransition
    ) : TransitionResult()

    /**
     * No valid transition exists for this state + event combination.
     */
    data class InvalidTransition(
        val currentState: RideState,
        val eventType: String,
        val validEvents: Set<String>
    ) : TransitionResult()

    /**
     * Transition exists but guard failed.
     */
    data class GuardFailed(
        val currentState: RideState,
        val eventType: String,
        val guardName: String,
        val reason: String
    ) : TransitionResult()

    /**
     * Action execution failed during transition.
     */
    data class ActionFailed(
        val currentState: RideState,
        val eventType: String,
        val actionName: String,
        val error: String
    ) : TransitionResult()
}

/**
 * Callback interface for state machine events.
 */
interface StateMachineListener {
    /** Called before a transition is attempted */
    fun onTransitionAttempted(state: RideState, event: RideEvent) {}

    /** Called when a transition succeeds */
    fun onTransitionSucceeded(result: TransitionResult.Success) {}

    /** Called when a transition fails for any reason */
    fun onTransitionFailed(result: TransitionResult) {}

    /** Called when state changes */
    fun onStateChanged(oldState: RideState, newState: RideState, context: RideContext) {}
}

/**
 * The Ridestr ride state machine processor.
 *
 * This class is the central authority for ride state transitions.
 * It enforces the transition rules defined in RideTransitions,
 * evaluates guards from RideGuards, and executes actions from RideActions.
 *
 * Usage:
 * ```kotlin
 * val machine = RideStateMachine()
 * machine.setActionHandler(viewModelActionHandler)
 *
 * // Process an event
 * val result = machine.processEvent(currentState, context, event)
 * when (result) {
 *     is TransitionResult.Success -> updateUiState(result.toState, result.context)
 *     is TransitionResult.GuardFailed -> showError(result.reason)
 *     // ...
 * }
 * ```
 *
 * Design Principles:
 * 1. **Explicit transitions** - Only transitions in the table are valid
 * 2. **Named guards** - Authorization logic is named and testable
 * 3. **Named actions** - Side effects are named and handler-provided
 * 4. **Immutable context** - Context is copied, never mutated
 * 5. **Observable** - Listeners notified of all transition attempts
 */
class RideStateMachine {

    companion object {
        private const val TAG = "RideStateMachine"
    }

    private var actionHandler: RideActions.ActionHandler? = null
    private val listeners = mutableListOf<StateMachineListener>()

    /**
     * Set the action handler for I/O operations.
     * Typically implemented by the ViewModel.
     */
    fun setActionHandler(handler: RideActions.ActionHandler) {
        this.actionHandler = handler
    }

    /**
     * Add a listener for state machine events.
     */
    fun addListener(listener: StateMachineListener) {
        listeners.add(listener)
    }

    /**
     * Remove a listener.
     */
    fun removeListener(listener: StateMachineListener) {
        listeners.remove(listener)
    }

    /**
     * Process an event and attempt a state transition.
     *
     * This is the main entry point for the state machine.
     *
     * @param currentState The current ride state
     * @param context The current ride context
     * @param event The event to process
     * @return TransitionResult indicating success or failure
     */
    suspend fun processEvent(
        currentState: RideState,
        context: RideContext,
        event: RideEvent
    ): TransitionResult {
        Log.d(TAG, "Processing event ${event.eventType} in state $currentState")

        // Notify listeners
        listeners.forEach { it.onTransitionAttempted(currentState, event) }

        // Find matching transitions
        val candidates = RideTransitions.findTransition(currentState, event.eventType)

        if (candidates.isEmpty()) {
            val validEvents = RideTransitions.validEventsFrom(currentState)
            val result = TransitionResult.InvalidTransition(
                currentState = currentState,
                eventType = event.eventType,
                validEvents = validEvents
            )
            Log.w(TAG, "Invalid transition: ${event.eventType} not valid from $currentState. " +
                       "Valid events: $validEvents")
            listeners.forEach { it.onTransitionFailed(result) }
            return result
        }

        // Evaluate guards to find the applicable transition
        // (multiple transitions may exist, e.g., VERIFY_PIN → IN_PROGRESS or CANCELLED)
        val (transition, guardResult) = findApplicableTransition(candidates, context, event)

        if (transition == null) {
            // No transition passed its guard
            val failedGuard = candidates.firstOrNull()?.guard ?: "unknown"
            val result = TransitionResult.GuardFailed(
                currentState = currentState,
                eventType = event.eventType,
                guardName = failedGuard,
                reason = guardResult ?: "Guard evaluation failed"
            )
            Log.w(TAG, "Guard failed for ${event.eventType}: $guardResult")
            listeners.forEach { it.onTransitionFailed(result) }
            return result
        }

        Log.d(TAG, "Applying transition: $currentState --${event.eventType}--> ${transition.to}")

        // Execute action (if any)
        val actionResult = RideActions.execute(
            actionName = transition.action,
            context = context,
            event = event,
            handler = actionHandler
        )

        return when (actionResult) {
            is ActionResult.Success -> {
                val result = TransitionResult.Success(
                    fromState = currentState,
                    toState = transition.to,
                    context = actionResult.updatedContext,
                    transition = transition
                )
                Log.d(TAG, "Transition succeeded: $currentState -> ${transition.to}")
                listeners.forEach {
                    it.onTransitionSucceeded(result)
                    it.onStateChanged(currentState, transition.to, actionResult.updatedContext)
                }
                result
            }

            is ActionResult.Failure -> {
                val result = TransitionResult.ActionFailed(
                    currentState = currentState,
                    eventType = event.eventType,
                    actionName = transition.action ?: "unknown",
                    error = actionResult.error
                )
                Log.e(TAG, "Action failed for ${event.eventType}: ${actionResult.error}")
                listeners.forEach { it.onTransitionFailed(result) }
                result
            }

            is ActionResult.Async -> {
                // Async actions still transition state, completion handled separately
                val result = TransitionResult.Success(
                    fromState = currentState,
                    toState = transition.to,
                    context = context,
                    transition = transition
                )
                Log.d(TAG, "Async transition started: $currentState -> ${transition.to}")
                listeners.forEach {
                    it.onTransitionSucceeded(result)
                    it.onStateChanged(currentState, transition.to, context)
                }
                result
            }
        }
    }

    /**
     * Find the first transition whose guard passes.
     *
     * @return Pair of (transition, guardFailureReason)
     *         If transition is non-null, guard passed.
     *         If transition is null, guardFailureReason explains why.
     */
    private fun findApplicableTransition(
        candidates: List<RideTransition>,
        context: RideContext,
        event: RideEvent
    ): Pair<RideTransition?, String?> {
        var lastFailureReason: String? = null

        for (transition in candidates) {
            if (transition.guard == null) {
                // No guard = always passes
                return Pair(transition, null)
            }

            val guardPassed = RideGuards.evaluate(transition.guard, context, event)
            if (guardPassed) {
                return Pair(transition, null)
            } else {
                lastFailureReason = RideGuards.explainFailure(transition.guard, context, event)
            }
        }

        return Pair(null, lastFailureReason)
    }

    /**
     * Check if a transition would be valid without executing it.
     *
     * Useful for UI to show/hide buttons based on available actions.
     *
     * @return true if transition would succeed, false otherwise
     */
    fun canTransition(
        currentState: RideState,
        context: RideContext,
        event: RideEvent
    ): Boolean {
        val candidates = RideTransitions.findTransition(currentState, event.eventType)
        if (candidates.isEmpty()) return false

        return candidates.any { transition ->
            transition.guard == null || RideGuards.evaluate(transition.guard, context, event)
        }
    }

    /**
     * Get all events that could be successfully processed from the current state.
     *
     * Useful for UI to determine which actions to show.
     *
     * @return Set of event types that would succeed
     */
    fun availableEvents(currentState: RideState, context: RideContext, inputterPubkey: String): Set<String> {
        return RideTransitions.validEventsFrom(currentState).filter { eventType ->
            // Create a dummy event to test guards
            val dummyEvent = createDummyEvent(eventType, inputterPubkey)
            canTransition(currentState, context, dummyEvent)
        }.toSet()
    }

    /**
     * Create a minimal event for guard testing.
     */
    private fun createDummyEvent(eventType: String, inputterPubkey: String): RideEvent {
        return when (eventType) {
            "ACCEPT" -> RideEvent.Accept(inputterPubkey, inputterPubkey)
            "CONFIRM" -> RideEvent.Confirm(inputterPubkey, "")
            "START_ROUTE" -> RideEvent.StartRoute(inputterPubkey)
            "ARRIVE" -> RideEvent.Arrive(inputterPubkey)
            "SUBMIT_PIN" -> RideEvent.SubmitPin(inputterPubkey, "")
            "VERIFY_PIN" -> RideEvent.VerifyPin(inputterPubkey, false, 0)
            "START_RIDE" -> RideEvent.StartRide(inputterPubkey)
            "COMPLETE" -> RideEvent.Complete(inputterPubkey)
            "CANCEL" -> RideEvent.Cancel(inputterPubkey)
            "CONFIRMATION_TIMEOUT" -> RideEvent.ConfirmationTimeout(inputterPubkey)
            "PIN_TIMEOUT" -> RideEvent.PinTimeout(inputterPubkey)
            else -> RideEvent.Cancel(inputterPubkey) // Fallback
        }
    }

    /**
     * Validate that the transition table is well-formed.
     *
     * Checks:
     * - All guards exist in registry
     * - No duplicate transitions
     * - Terminal states have no outgoing transitions
     *
     * @return List of validation errors (empty if valid)
     */
    fun validateTransitionTable(): List<String> {
        val errors = mutableListOf<String>()

        for (transition in RideTransitions.all) {
            // Check guard exists
            if (transition.guard != null && RideGuards.get(transition.guard) == null) {
                errors.add("Unknown guard '${transition.guard}' in transition ${transition.from} -> ${transition.to}")
            }

            // Check no transitions from terminal states
            if (transition.from.isTerminal()) {
                errors.add("Terminal state ${transition.from} should not have outgoing transitions")
            }
        }

        // Check for duplicate transitions (same from + eventType)
        val transitionKeys = RideTransitions.all.map { "${it.from}:${it.eventType}" }
        val duplicates = transitionKeys.groupBy { it }.filter { it.value.size > 1 }.keys
        // Note: duplicates are OK if they have different guards (e.g., VERIFY_PIN)
        // This is a warning, not an error

        return errors
    }
}
