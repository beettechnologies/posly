package com.beettechnologies.posly.auth

import com.beettechnologies.posly.model.Role
import java.time.Instant

/** Maps one external IdP group/claim value to a local [Role]. */
data class SsoRoleMapping(val externalGroup: String, val role: Role)

data class SsoConfiguration(
    val providerName: String,
    val enabled: Boolean,
    val roleMappings: List<SsoRoleMapping>,
    /** Applied when a login's external groups match none of [roleMappings] - empty means "no access". */
    val defaultRoles: Set<Role>,
    val configuredAt: Instant
)

/**
 * What a real SAML assertion or OIDC id_token would have handed the application after the
 * identity provider validated the login and the protocol layer (SAML response parsing/signature
 * check, or OIDC token verification) confirmed it - there is no real SAML/OIDC library wired up
 * here, so a caller supplies this directly, standing in for "the provider already vouched for
 * this identity." [externalId] is the provider's stable subject identifier (SAML NameID / OIDC
 * `sub`); [externalGroups] are whatever group/role claims the provider asserts.
 */
data class SsoAssertion(
    val externalId: String,
    val email: String,
    val externalGroups: List<String>
)

/**
 * Pure configuration storage for the (simulated) SSO provider - who administers role mapping, kept
 * separate from [AuthService], which owns the login orchestration that reads it. Mirrors how
 * TaxProfileService is a config store that CartService/StoreService consult rather than own.
 */
class SsoConfigService(private val nowProvider: () -> Instant = { Instant.now() }) {

    @Volatile
    private var configuration: SsoConfiguration? = null

    fun configure(providerName: String, roleMappings: List<SsoRoleMapping>, defaultRoles: Set<Role>, enabled: Boolean = true): SsoConfiguration {
        val config = SsoConfiguration(
            providerName = providerName,
            enabled = enabled,
            roleMappings = roleMappings,
            defaultRoles = defaultRoles,
            configuredAt = nowProvider()
        )
        configuration = config
        return config
    }

    fun getConfiguration(): SsoConfiguration? = configuration
}
