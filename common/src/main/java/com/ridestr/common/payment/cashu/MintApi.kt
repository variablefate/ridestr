package com.ridestr.common.payment.cashu

/**
 * HTTP-only interface for mint API calls.
 * Separates raw HTTP transport from CashuBackend orchestration logic.
 *
 * Production: OkHttpMintApi (uses OkHttpClient)
 * Tests: FakeMintApi (returns canned responses)
 *
 * Only covers endpoints used in HTLC flows:
 * - /v1/swap - HTLC creation, claiming, refunding
 * - /v1/checkstate - Proof verification (NUT-07)
 *
 * Other endpoints (NUT-04/05/17) remain in CashuBackend as they have
 * different error handling needs.
 */
interface MintApi {
    /**
     * Result of an HTTP call to the mint.
     * Distinguishes transport failures from HTTP errors for proper error wiring.
     */
    sealed class Result {
        /** Successful HTTP response (2xx) */
        data class Success(val body: String) : Result()

        /** HTTP error response (4xx/5xx) */
        data class HttpError(val code: Int, val body: String?) : Result()

        /** Transport failure (IOException, timeout, DNS failure) */
        data class TransportFailure(val cause: String) : Result()
    }

    /**
     * POST /v1/swap - Convert proofs using P2PK signatures.
     * Used for HTLC creation, claiming, and refunding.
     *
     * @param mintUrl The mint base URL (e.g., "https://mint.example.com")
     * @param requestBody JSON request body
     * @return Result distinguishing success, HTTP error, or transport failure
     */
    suspend fun postSwap(mintUrl: String, requestBody: String): Result

    /**
     * POST /v1/checkstate - Verify proof states (NUT-07).
     * Used to check if proofs are UNSPENT, SPENT, or PENDING.
     *
     * @param mintUrl The mint base URL
     * @param requestBody JSON request body with proof secrets
     * @return Result distinguishing success, HTTP error, or transport failure
     */
    suspend fun postCheckState(mintUrl: String, requestBody: String): Result
}
