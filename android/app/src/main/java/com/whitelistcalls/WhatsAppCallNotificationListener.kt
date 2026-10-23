package com.whitelistcalls

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.PendingIntent
import android.app.Person
import android.content.ContentUris
import android.content.pm.PackageManager
import android.os.Build
import android.provider.ContactsContract
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.whitelistcalls.externalcalls.AndroidNotificationSnapshotFactory
import com.whitelistcalls.externalcalls.BlockedCallSessionStore
import com.whitelistcalls.externalcalls.BoundedDiagnostics
import com.whitelistcalls.externalcalls.CallerIdentity
import com.whitelistcalls.externalcalls.CallerIdentityResolver
import com.whitelistcalls.externalcalls.ContactPhoneLookup
import com.whitelistcalls.externalcalls.ContactUriPhoneLookup
import com.whitelistcalls.externalcalls.DirectDeclineSendResult
import com.whitelistcalls.externalcalls.DiagnosticsMetadata
import com.whitelistcalls.externalcalls.NotificationHandlingResult
import com.whitelistcalls.externalcalls.StandardPersonMetadata
import com.whitelistcalls.externalcalls.WhatsAppCallNotificationCoordinator
import com.whitelistcalls.externalcalls.AllowedCallAlertCoordinator
import com.whitelistcalls.externalcalls.WhatsAppCallAlertHandler
import com.whitelistcalls.externalcalls.CallType
import com.whitelistcalls.externalcalls.ExternalCallProvider
import com.whitelistcalls.externalcalls.WeChatCallNotificationHandler
import com.whitelistcalls.externalcalls.WeChatHandlingOutcome
import com.whitelistcalls.externalcalls.WeChatIdentityCandidate
import com.whitelistcalls.externalcalls.WeChatIdentityObservation
import com.whitelistcalls.externalcalls.isIncomingWeChatCall
import com.whitelistcalls.externalcalls.weChatNotificationIdentity
import com.whitelistcalls.externalcalls.providerFor
import com.whitelistcalls.externalcalls.serializeDiagnostics

internal fun confirmedIncomingChannelInfo(
    packageName: String,
    callType: CallType?,
    channelId: String,
    detectedAtMillis: Long,
): ExternalCallChannelInfo? =
    if (providerFor(packageName) != null && callType == CallType.INCOMING && channelId.isNotBlank()) {
        ExternalCallChannelInfo(packageName, channelId, detectedAtMillis)
    } else {
        null
    }

internal data class ObservedChannelDiagnostics(
    val channelId: String?,
    val channelVibrates: Boolean?,
    val channelHasSound: Boolean?,
    val channelImportance: Int?,
    val channelLockscreenVisibility: Int?,
    val silentCallChannelStatus: SilentCallChannelStatus,
)

internal fun observedChannelDiagnostics(
    channelId: String?,
    channelState: ChannelAlertState?,
): ObservedChannelDiagnostics {
    if (channelId.isNullOrBlank() || channelState == null) {
        return ObservedChannelDiagnostics(
            channelId = null,
            channelVibrates = null,
            channelHasSound = null,
            channelImportance = null,
            channelLockscreenVisibility = null,
            silentCallChannelStatus = SilentCallChannelStatus.UNKNOWN,
        )
    }

    return ObservedChannelDiagnostics(
        channelId = channelId,
        channelVibrates = channelState.vibrates,
        channelHasSound = channelState.hasSound,
        channelImportance = channelState.importance,
        channelLockscreenVisibility = channelState.lockscreenVisibility,
        silentCallChannelStatus = silentCallChannelStatus(channelState),
    )
}

internal class WhatsAppAllowedAlertController(
    private val coordinator: AllowedCallAlertCoordinator,
) {
    private val handler = WhatsAppCallAlertHandler(coordinator)
    private var lastAllowedNotificationKey: String? = null

    fun onHandlingResult(notificationKey: String, result: NotificationHandlingResult) {
        when (result) {
            NotificationHandlingResult.ALLOWED -> {
                lastAllowedNotificationKey = notificationKey
                handler.onHandlingResult(notificationKey, result)
            }
            NotificationHandlingResult.IGNORED -> {
                handler.onHandlingResult(notificationKey, result)
                if (lastAllowedNotificationKey == notificationKey) lastAllowedNotificationKey = null
            }
            NotificationHandlingResult.NO_DIRECT_DECLINE,
            NotificationHandlingResult.DUPLICATE,
            NotificationHandlingResult.DECLINED,
            NotificationHandlingResult.DECLINE_FAILED -> stopWhatsAppAlert()
        }
    }

    fun onFilteringDisabled(masterEnabled: Boolean) {
        if (!masterEnabled) {
            coordinator.stopAll()
            lastAllowedNotificationKey = null
        } else {
            stopWhatsAppAlert()
        }
    }

    private fun stopWhatsAppAlert() {
        // The shared coordinator may now own a WeChat alert; stop only our key.
        lastAllowedNotificationKey?.let(coordinator::onNotificationRemoved)
        lastAllowedNotificationKey = null
    }
}

class WhatsAppCallNotificationListener : NotificationListenerService() {
    private lateinit var allowedAlertCoordinator: AllowedCallAlertCoordinator
    private lateinit var allowedAlertHandler: WhatsAppAllowedAlertController
    private lateinit var weChatHandler: WeChatCallNotificationHandler
    private lateinit var weChatObservations: WeChatSettingsStore
    private val observedWeChatCalls = linkedSetOf<String>()
    private val blockedWeChatCalls = linkedSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        allowedAlertCoordinator = AllowedCallAlertCoordinator(AndroidAllowedCallVibrator(this))
        AllowedCallVibrationController.register(allowedAlertCoordinator)
        allowedAlertHandler = WhatsAppAllowedAlertController(allowedAlertCoordinator)
        weChatObservations = WeChatSettingsStore(this)
        weChatHandler = WeChatCallNotificationHandler(
            isAllowedDigest = { digest -> WeChatSettingsStore(this).isAllowedDigest(digest) },
            allowedCallAlertCoordinator = allowedAlertCoordinator,
        )
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        if (::allowedAlertCoordinator.isInitialized) {
            AllowedCallVibrationController.onListenerConnected(allowedAlertCoordinator)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        handleNotificationPosted(sbn, observedChannel = null)
    }

    override fun onNotificationPosted(
        sbn: StatusBarNotification,
        rankingMap: RankingMap,
    ) {
        val ranking = Ranking()
        val observedChannel = if (rankingMap.getRanking(sbn.key, ranking)) {
            ranking.channel
        } else {
            null
        }
        handleNotificationPosted(sbn, observedChannel)
    }

    private fun handleNotificationPosted(
        sbn: StatusBarNotification,
        observedChannel: NotificationChannel?,
    ) {
        val provider = providerFor(sbn.packageName) ?: return
        if (provider == ExternalCallProvider.WECHAT) {
            handleWeChatNotification(sbn)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        val settings = WhatsAppSettingsStore(this)
        val diagnosticsEnabled = settings.isDiagnosticsEnabled()
        val whitelist = WhitelistStore(this)
        val effectiveEnabled = isWhatsAppFilteringEffective(
            masterEnabled = whitelist.isProtectionEnabled(),
            whatsappEnabled = settings.isFilteringEnabled(),
        )
        if (!effectiveEnabled && !diagnosticsEnabled) {
            allowedAlertHandler.onFilteringDisabled(whitelist.isProtectionEnabled())
            return
        }

        val notificationFields = extractCallFields(sbn.notification)
        val snapshot = AndroidNotificationSnapshotFactory(
            apiLevel = Build.VERSION.SDK_INT,
            identityResolver = CallerIdentityResolver(
                ContactPhoneLookup(::findPhoneNumbersByExactDisplayName),
                ContactUriPhoneLookup(::findPhoneNumbersByContactLookupUri),
            ),
        ).from(
            packageName = sbn.packageName,
            callType = notificationFields.callType,
            personMetadata = notificationFields.person?.let { person ->
                StandardPersonMetadata(
                    uri = person.uri,
                    displayName = person.name?.toString(),
                )
            },
            relatedPeopleMetadata = notificationFields.relatedPeople.map { person ->
                StandardPersonMetadata(
                    uri = person.uri,
                    displayName = person.name?.toString(),
                )
            },
            isGroupConversation = notificationFields.isGroupConversation,
            declinePresent = notificationFields.declineIntent != null,
        )
        val isAllowed: (String) -> Boolean = when (settings.whitelistMode()) {
            WhatsAppWhitelistMode.SHARED -> whitelist::isWhitelisted
            WhatsAppWhitelistMode.CUSTOM ->
                settings.customMembership(whitelist::normalize)::contains
        }
        val nowMillis = System.currentTimeMillis()
        val diagnosticsMetadata = if (diagnosticsEnabled) {
            diagnosticsMetadata(sbn, notificationFields, snapshot, observedChannel)
        } else {
            null
        }
        if (diagnosticsMetadata != null) {
            diagnostics.record(
                eventCode = "WA_NOTIFICATION_OBSERVED",
                notificationKey = sbn.key,
                metadata = diagnosticsMetadata,
                timestampMillis = nowMillis,
                callerValue = (snapshot.callerIdentity as? CallerIdentity.Phone)?.number,
            )
        }
        if (!effectiveEnabled) {
            allowedAlertHandler.onFilteringDisabled(whitelist.isProtectionEnabled())
            return
        }
        confirmedIncomingChannelInfo(
            packageName = sbn.packageName,
            callType = snapshot.callType,
            channelId = sbn.notification.channelId,
            detectedAtMillis = nowMillis,
        )?.copy(
            observedAlertState = channelAlertState(observedChannel),
        )?.let(settings::recordCallChannel)
        val result = (if (diagnosticsEnabled) coordinator else privacyCoordinator).onNotificationPosted(
            snapshot = snapshot,
            notificationKey = sbn.key,
            hasCallerMetadata = notificationFields.person != null,
            filteringEnabled = {
                isWhatsAppFilteringEffective(
                    masterEnabled = whitelist.isProtectionEnabled(),
                    whatsappEnabled = settings.isFilteringEnabled(),
                )
            },
            isAllowed = isAllowed,
            nowMillis = nowMillis,
            sendDirectDecline = notificationFields.declineIntent?.let { declineIntent ->
                {
                    try {
                        declineIntent.send()
                        DirectDeclineSendResult.SENT
                    } catch (_: PendingIntent.CanceledException) {
                        DirectDeclineSendResult.CANCELED
                    }
                }
            },
            diagnosticsMetadata = diagnosticsMetadata,
        )

        if (isWhatsAppFilteringEffective(
                masterEnabled = whitelist.isProtectionEnabled(),
                whatsappEnabled = settings.isFilteringEnabled(),
            )
        ) {
            allowedAlertHandler.onHandlingResult(sbn.key, result)
        } else {
            allowedAlertHandler.onFilteringDisabled(whitelist.isProtectionEnabled())
        }

        when {
            result == NotificationHandlingResult.NO_DIRECT_DECLINE -> {
                settings.setDirectDeclineUnavailable(true)
                if (diagnosticsEnabled) {
                    diagnostics.record(
                        eventCode = "WA_DIRECT_DECLINE_UNAVAILABLE",
                        notificationKey = sbn.key,
                        metadata = requireNotNull(diagnosticsMetadata),
                        timestampMillis = nowMillis,
                        callerValue = (snapshot.callerIdentity as? CallerIdentity.Phone)?.number,
                    )
                }
            }
            result != NotificationHandlingResult.IGNORED && snapshot.hasDirectDeclineIntent -> {
                settings.setDirectDeclineUnavailable(false)
            }
        }
    }

    private fun handleWeChatNotification(sbn: StatusBarNotification) {
        val notification = sbn.notification
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            if (notification.category == Notification.CATEGORY_CALL) {
                weChatObservations.recordUnsupportedNotification()
            }
            return
        }
        val fields = extractCallFields(notification)
        val person = fields.person ?: fields.relatedPeople.singleOrNull()
        if (!isIncomingWeChatCall(
                apiLevel = Build.VERSION.SDK_INT,
                callType = fields.rawCallType,
                hasPersonMetadata = fields.person != null,
                hasDeclineIntent = fields.declineIntent != null,
            )
        ) {
            weChatHandler.onNotificationRemoved(sbn.key)
            observedWeChatCalls.remove(sbn.key)
            blockedWeChatCalls.remove(sbn.key)
            if (notification.category == Notification.CATEGORY_CALL && fields.rawCallType == null) {
                weChatObservations.recordUnsupportedNotification()
            }
            return
        }
        val identity = if (fields.isGroupConversation == true ||
            (fields.person == null && fields.relatedPeople.size > 1)
        ) {
            WeChatIdentityCandidate.Missing
        } else {
            weChatNotificationIdentity(
                WeChatIdentityObservation(
                    personUri = person?.uri,
                    personKey = person?.key,
                    shortcutId = notification.shortcutId,
                    displayName = person?.name?.toString()
                        ?: notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                ),
            )
        }
        // Repeated updates for one call are not two controlled enrollment calls.
        if (rememberWeChatCall(observedWeChatCalls, sbn.key)) {
            weChatObservations.recordCandidate(identity, System.currentTimeMillis())
        }
        val filteringEnabled = WhitelistStore(this).isProtectionEnabled() &&
            WeChatSettingsStore(this).isFilteringEnabled()
        val outcome = weChatHandler.onIncoming(sbn.key, identity, filteringEnabled)
        if (outcome != WeChatHandlingOutcome.BLOCKED) return
        if (!rememberWeChatCall(blockedWeChatCalls, sbn.key)) return

        val declineSent = fields.declineIntent?.let { decline ->
            try {
                decline.send()
                true
            } catch (_: PendingIntent.CanceledException) {
                false
            }
        } ?: false
        weChatObservations.setDirectDeclineUnavailable(!declineSent)
        if (!declineSent) cancelNotification(sbn.key)
    }

    private fun rememberWeChatCall(keys: LinkedHashSet<String>, notificationKey: String): Boolean {
        if (!keys.add(notificationKey)) return false
        if (keys.size > MAX_TRACKED_WECHAT_CALLS) keys.remove(keys.first())
        return true
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (providerFor(sbn.packageName) == null) return
        if (providerFor(sbn.packageName) == ExternalCallProvider.WECHAT) {
            weChatHandler.onNotificationRemoved(sbn.key)
            observedWeChatCalls.remove(sbn.key)
            blockedWeChatCalls.remove(sbn.key)
            return
        }
        coordinator.onNotificationRemoved(sbn.packageName, sbn.key)
        allowedAlertCoordinator.onNotificationRemoved(sbn.key)
    }

    override fun onListenerDisconnected() {
        allowedAlertCoordinator.stopAll()
        AllowedCallVibrationController.unregister(allowedAlertCoordinator)
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        if (::allowedAlertCoordinator.isInitialized) {
            allowedAlertCoordinator.stopAll()
            AllowedCallVibrationController.unregister(allowedAlertCoordinator)
        }
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun extractCallFields(notification: Notification): AndroidCallFields {
        val extras = notification.extras
        val callTypePresent = extras.containsKey(Notification.EXTRA_CALL_TYPE)
        val rawCallType = if (callTypePresent) {
            extras.getInt(Notification.EXTRA_CALL_TYPE, PLATFORM_CALL_TYPE_MISSING)
                .takeUnless { it == PLATFORM_CALL_TYPE_MISSING }
        } else {
            null
        }
        return AndroidCallFields(
            callType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                rawCallType
            } else {
                null
            },
            callTypePresent = callTypePresent,
            rawCallType = rawCallType,
            person = extras.getParcelable(Notification.EXTRA_CALL_PERSON) as? Person,
            relatedPeople = extras.getParcelableArrayList<Person>(Notification.EXTRA_PEOPLE_LIST)
                ?: emptyList(),
            declineIntent = extras.getParcelable(Notification.EXTRA_DECLINE_INTENT) as? PendingIntent,
            isGroupConversation = if (extras.containsKey(Notification.EXTRA_IS_GROUP_CONVERSATION)) {
                extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)
            } else {
                null
            },
        )
    }

    private fun diagnosticsMetadata(
        sbn: StatusBarNotification,
        fields: AndroidCallFields,
        snapshot: com.whitelistcalls.externalcalls.NotificationSnapshot,
        observedChannel: NotificationChannel?,
    ): DiagnosticsMetadata {
        val notification = sbn.notification
        val observedDiagnostics = observedChannelDiagnostics(
            channelId = observedChannel?.id,
            channelState = channelAlertState(observedChannel),
        )
        return DiagnosticsMetadata(
            packageName = sbn.packageName,
            category = notification.category,
            extrasKeyNames = notification.extras?.keySet()?.toList()?.sorted(),
            callTypePresent = fields.callTypePresent,
            callTypeValue = fields.rawCallType,
            hasCallerMetadata = fields.person != null,
            hasDeclineMetadata = fields.declineIntent != null,
            actionCount = notification.actions?.size ?: 0,
            sdkInt = Build.VERSION.SDK_INT,
            deviceManufacturer = Build.MANUFACTURER.takeIf(String::isNotBlank),
            deviceModel = Build.MODEL.takeIf(String::isNotBlank),
            whatsAppPackageVersion = whatsAppPackageVersion(sbn.packageName),
            personUriKind = snapshot.personUriKind?.name?.lowercase(),
            contactCandidateCount = snapshot.contactCandidateCount,
            relatedPeopleCount = snapshot.relatedPeopleCount,
            relatedPeoplePhoneCount = snapshot.relatedPeoplePhoneCount,
            callerIdentityKind = when (snapshot.callerIdentity) {
                is CallerIdentity.Phone -> "phone"
                CallerIdentity.Unresolved -> "unresolved"
                CallerIdentity.Ambiguous -> "ambiguous"
                CallerIdentity.Group -> "group"
            },
            channelId = observedDiagnostics.channelId,
            channelVibrates = observedDiagnostics.channelVibrates,
            channelHasSound = observedDiagnostics.channelHasSound,
            channelImportance = observedDiagnostics.channelImportance,
            channelLockscreenVisibility = observedDiagnostics.channelLockscreenVisibility,
            silentCallChannelStatus = observedDiagnostics.silentCallChannelStatus,
        )
    }

    @Suppress("DEPRECATION")
    private fun whatsAppPackageVersion(packageName: String): String? = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull()

    private fun findPhoneNumbersByExactDisplayName(displayName: String): List<String> {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }

        return runCatching {
            val numbers = mutableListOf<String>()
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} = ?",
                arrayOf(displayName),
                null,
            )?.use { cursor ->
                val numberColumn = cursor.getColumnIndexOrThrow(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                )
                while (cursor.moveToNext()) numbers += cursor.getString(numberColumn)
            }
            numbers
        }.getOrDefault(emptyList())
    }

    private fun findPhoneNumbersByContactLookupUri(personUri: String): List<String> {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }

        return runCatching {
            val lookupUri = android.net.Uri.parse(personUri)
            val contactUri = ContactsContract.Contacts.lookupContact(contentResolver, lookupUri)
                ?: return@runCatching emptyList()
            val contactId = ContentUris.parseId(contactUri)
            val numbers = mutableListOf<String>()
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                arrayOf(contactId.toString()),
                null,
            )?.use { cursor ->
                val numberColumn = cursor.getColumnIndexOrThrow(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                )
                while (cursor.moveToNext()) numbers += cursor.getString(numberColumn)
            }
            numbers
        }.getOrDefault(emptyList())
    }

    private data class AndroidCallFields(
        val callType: Int?,
        val callTypePresent: Boolean,
        val rawCallType: Int?,
        val person: Person?,
        val relatedPeople: List<Person>,
        val declineIntent: PendingIntent?,
        val isGroupConversation: Boolean?,
    )

    companion object {
        private const val PLATFORM_CALL_TYPE_MISSING = Int.MIN_VALUE
        private const val MAX_TRACKED_WECHAT_CALLS = 64
        private val sessions = BlockedCallSessionStore()
        private val diagnostics = BoundedDiagnostics(capacity = 100)
        private val coordinator = WhatsAppCallNotificationCoordinator(sessions, diagnostics)
        private val privacyCoordinator = WhatsAppCallNotificationCoordinator(
            sessions,
            BoundedDiagnostics(capacity = 0),
        )

        fun redactedDiagnosticsExport(): String {
            return serializeDiagnostics(diagnostics.entries)
        }
    }
}
