package com.whitelistcalls.externalcalls

import org.junit.Assert.assertEquals
import org.junit.Test

class CallerIdentityResolverTest {
    @Test
    fun telPersonUriWinsWithoutConsultingContacts() {
        var contactLookups = 0
        val resolver = CallerIdentityResolver {
            contactLookups += 1
            listOf("+31999999999")
        }

        val identity = resolver.resolve(
            StandardPersonMetadata(
                uri = "tel:+31612345678",
                displayName = "Alice",
            ),
        )

        assertEquals(CallerIdentity.Phone("+31612345678"), identity)
        assertEquals(0, contactLookups)
    }

    @Test
    fun uniqueContactNumberResolvesFromStandardPersonDisplayName() {
        val resolver = CallerIdentityResolver { displayName ->
            if (displayName == "Alice") listOf("+31612345678") else emptyList()
        }

        val identity = resolver.resolve(
            StandardPersonMetadata(uri = null, displayName = "Alice"),
        )

        assertEquals(CallerIdentity.Phone("+31612345678"), identity)
    }

    @Test
    fun contactLookupPersonUriResolvesItsUniquePhoneWithoutNameFallback() {
        var nameLookups = 0
        var uriLookups = 0
        val resolver = CallerIdentityResolver(
            ContactPhoneLookup {
                nameLookups += 1
                emptyList()
            },
            ContactUriPhoneLookup { personUri ->
                uriLookups += 1
                if (personUri == "content://com.android.contacts/contacts/lookup/alice/42") {
                    listOf("+31612345678")
                } else {
                    emptyList()
                }
            },
        )

        val identity = resolver.resolve(
            StandardPersonMetadata(
                uri = "content://com.android.contacts/contacts/lookup/alice/42",
                displayName = "Alice",
            ),
        )

        assertEquals(CallerIdentity.Phone("+31612345678"), identity)
        assertEquals(0, nameLookups)
        assertEquals(1, uriLookups)
    }

    @Test
    fun contactLookupPersonUriWithMultiplePhonesRemainsAmbiguous() {
        val resolver = CallerIdentityResolver(
            ContactPhoneLookup { emptyList() },
            ContactUriPhoneLookup {
                listOf("+31612345678", "+31687654321")
            },
        )

        val identity = resolver.resolve(
            StandardPersonMetadata(
                uri = "content://com.android.contacts/contacts/lookup/alice/42",
                displayName = "Alice",
            ),
        )

        assertEquals(CallerIdentity.Ambiguous, identity)
    }

    @Test
    fun contactLookupResolutionReportsOnlySafeUriKindAndCandidateCount() {
        val resolver = CallerIdentityResolver(
            ContactPhoneLookup { emptyList() },
            ContactUriPhoneLookup { listOf("+31612345678") },
        )

        val resolution = resolver.resolveDetails(
            StandardPersonMetadata(
                uri = "content://com.android.contacts/contacts/lookup/alice/42",
                displayName = "Alice",
            ),
        )

        assertEquals(CallerIdentity.Phone("+31612345678"), resolution.identity)
        assertEquals(PersonUriKind.CONTACTS_LOOKUP, resolution.personUriKind)
        assertEquals(1, resolution.contactCandidateCount)
    }

    @Test
    fun multipleContactNumbersAreAmbiguous() {
        val resolver = CallerIdentityResolver {
            listOf("+31612345678", "+31687654321")
        }

        val identity = resolver.resolve(
            StandardPersonMetadata(uri = null, displayName = "Shared name"),
        )

        assertEquals(CallerIdentity.Ambiguous, identity)
    }

    @Test
    fun absentStandardPersonMetadataIsUnresolvedWithoutContactLookup() {
        var contactLookups = 0
        val resolver = CallerIdentityResolver {
            contactLookups += 1
            listOf("+31612345678")
        }

        assertEquals(CallerIdentity.Unresolved, resolver.resolve(null))
        assertEquals(0, contactLookups)
    }
}
