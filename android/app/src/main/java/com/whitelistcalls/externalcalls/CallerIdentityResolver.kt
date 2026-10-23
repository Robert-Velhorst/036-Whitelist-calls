package com.whitelistcalls.externalcalls

import java.net.URI

data class StandardPersonMetadata(
    val uri: String?,
    val displayName: String?,
)

enum class PersonUriKind {
    MISSING,
    TEL,
    CONTACTS_LOOKUP,
    OTHER,
}

data class CallerIdentityResolution(
    val identity: CallerIdentity,
    val personUriKind: PersonUriKind,
    val contactCandidateCount: Int?,
)

fun interface ContactPhoneLookup {
    fun findPhoneNumbersByExactDisplayName(displayName: String): List<String>
}

fun interface ContactUriPhoneLookup {
    fun findPhoneNumbersByContactLookupUri(personUri: String): List<String>
}

class CallerIdentityResolver(
    private val contactPhoneLookup: ContactPhoneLookup = ContactPhoneLookup { emptyList() },
) {
    private var contactUriPhoneLookup: ContactUriPhoneLookup = ContactUriPhoneLookup { emptyList() }

    constructor(
        contactPhoneLookup: ContactPhoneLookup,
        contactUriPhoneLookup: ContactUriPhoneLookup,
    ) : this(contactPhoneLookup) {
        this.contactUriPhoneLookup = contactUriPhoneLookup
    }

    fun resolve(person: StandardPersonMetadata?): CallerIdentity = resolveDetails(person).identity

    fun resolveDetails(person: StandardPersonMetadata?): CallerIdentityResolution {
        if (person == null) {
            return CallerIdentityResolution(
                identity = CallerIdentity.Unresolved,
                personUriKind = PersonUriKind.MISSING,
                contactCandidateCount = null,
            )
        }

        parseTelNumber(person.uri)?.let {
            return CallerIdentityResolution(
                identity = CallerIdentity.Phone(it),
                personUriKind = PersonUriKind.TEL,
                contactCandidateCount = null,
            )
        }

        if (isContactsLookupUri(person.uri)) {
            val contactNumbers =
                runCatching {
                    contactUriPhoneLookup.findPhoneNumbersByContactLookupUri(requireNotNull(person.uri))
                        .filter(String::isNotBlank)
                }.getOrElse { emptyList() }
            return CallerIdentityResolution(
                identity = resolveNumbers(contactNumbers),
                personUriKind = PersonUriKind.CONTACTS_LOOKUP,
                contactCandidateCount = contactNumbers.size,
            )
        }

        val displayName = person.displayName?.trim()?.takeIf(String::isNotEmpty)
            ?: return CallerIdentityResolution(
                identity = CallerIdentity.Unresolved,
                personUriKind = personUriKind(person.uri),
                contactCandidateCount = null,
            )
        val contactNumbers = runCatching {
            contactPhoneLookup.findPhoneNumbersByExactDisplayName(displayName)
                .filter(String::isNotBlank)
        }.getOrElse {
            return CallerIdentityResolution(
                identity = CallerIdentity.Unresolved,
                personUriKind = personUriKind(person.uri),
                contactCandidateCount = null,
            )
        }

        return CallerIdentityResolution(
            identity = resolveNumbers(contactNumbers),
            personUriKind = personUriKind(person.uri),
            contactCandidateCount = contactNumbers.size,
        )
    }

    private fun resolveNumbers(contactNumbers: List<String>): CallerIdentity = when (contactNumbers.size) {
            0 -> CallerIdentity.Unresolved
            1 -> CallerIdentity.Phone(contactNumbers.single())
            else -> CallerIdentity.Ambiguous
        }

    private fun parseTelNumber(personUri: String?): String? {
        if (personUri == null) return null

        val uri = runCatching { URI(personUri) }.getOrNull() ?: return null
        if (!uri.scheme.equals("tel", ignoreCase = true)) return null
        return uri.schemeSpecificPart?.takeIf(String::isNotBlank)
    }

    private fun isContactsLookupUri(personUri: String?): Boolean {
        if (personUri == null) return false

        val uri = runCatching { URI(personUri) }.getOrNull() ?: return false
        return uri.scheme.equals("content", ignoreCase = true) &&
            uri.authority.equals(CONTACTS_AUTHORITY, ignoreCase = true) &&
            uri.path?.startsWith(CONTACTS_LOOKUP_PATH) == true
    }

    private fun personUriKind(personUri: String?): PersonUriKind = when {
        personUri == null -> PersonUriKind.MISSING
        parseTelNumber(personUri) != null -> PersonUriKind.TEL
        isContactsLookupUri(personUri) -> PersonUriKind.CONTACTS_LOOKUP
        else -> PersonUriKind.OTHER
    }

    private companion object {
        const val CONTACTS_AUTHORITY = "com.android.contacts"
        const val CONTACTS_LOOKUP_PATH = "/contacts/lookup/"
    }
}
