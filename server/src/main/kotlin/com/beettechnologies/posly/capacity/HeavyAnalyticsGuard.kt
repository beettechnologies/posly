package com.beettechnologies.posly.capacity

import com.beettechnologies.posly.auth.ErrorResponse
import com.beettechnologies.posly.flags.FeatureFlagService
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.response.header
import io.ktor.server.response.respond

/**
 * The feature-flag key an SRE disables during a capacity incident to shed the server's most
 * expensive on-demand work - full reporting-pipeline runs/backfills and ad-hoc finance report
 * generation - while cheap, already-aggregated reads (GET /reports/sales, /top-products, etc.)
 * keep working. See docs/runbooks/capacity-scale-incidents.md for the incident procedure.
 *
 * Reuses [FeatureFlagService] as a plain global kill switch (`getFlag(...)?.enabled`), not its
 * per-store [FeatureFlagService.evaluate] rollout logic: shedding load is an operator decision for
 * the whole server, not a per-store rollout. Defaults to allowed when the flag hasn't been created
 * yet, so nothing needs provisioning before this ships - an operator only has to create it (once,
 * enabled=true) the first time they want the lever available.
 */
const val HEAVY_ANALYTICS_FLAG_KEY = "heavy_analytics_pipeline"

/**
 * A single shared token bucket for all callers, not a per-client one: these endpoints are
 * expensive to the server regardless of who calls them, so the cap protects the server's
 * aggregate capacity rather than giving each client its own fair share.
 */
val HeavyAnalyticsRateLimit = RateLimitName("heavy-analytics")

/**
 * Checks the kill switch and, if tripped, writes the 503 response itself - callers should
 * `if (call.blockedByHeavyAnalyticsKillSwitch(featureFlagService)) return@post` immediately after.
 */
suspend fun ApplicationCall.blockedByHeavyAnalyticsKillSwitch(featureFlagService: FeatureFlagService): Boolean {
    val allowed = featureFlagService.getFlag(HEAVY_ANALYTICS_FLAG_KEY)?.enabled ?: true
    if (!allowed) {
        response.header(HttpHeaders.RetryAfter, "60")
        respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Heavy analytics is temporarily disabled during a capacity incident - try again shortly"))
    }
    return !allowed
}
