package dev.nimbus.ratelimit;

/**
 * The result of a per-client rate-limit check.
 *
 * @param allowed           whether the request may proceed
 * @param limit             the bucket capacity ({@code X-RateLimit-Limit})
 * @param remaining         whole tokens left after this request ({@code X-RateLimit-Remaining})
 * @param retryAfterSeconds when denied, whole seconds until a token is available (at least 1); otherwise 0
 */
public record RateLimitDecision(boolean allowed, int limit, long remaining, long retryAfterSeconds) {
}
