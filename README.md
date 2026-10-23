# Whitelist Calls

Whitelist Calls is a root-required Android call firewall that allows incoming calls only from identities and channels explicitly approved by the user. Every other incoming call is rejected or suppressed before it can ring, vibrate, display a heads-up notification, open a full-screen call interface, or alert a connected device.

The product covers both conventional cellular calls and supported communication apps such as WhatsApp, WeChat, Telegram, Signal, and Facebook Messenger. It is not a conventional Play Store application. It is a deeply integrated Android security product built around a privileged policy engine and version-specific channel adapters.

## Implementation status

This repository currently contains an initial, non-root Android implementation under [`android/`](android/). It screens cellular calls with the platform `CallScreeningService` and observes WhatsApp and WeChat call notifications through notification access. The root-based privileged layer, versioned channel adapters, and fail-closed integrity model described below are not implemented yet.

### Build

Requirements: JDK 17 or later (built and tested with JDK 22) and an Android SDK with API 35. Point the build at your SDK through `ANDROID_HOME` or by creating `android/local.properties` with `sdk.dir=<path>`.

```sh
cd android
./gradlew assembleDebug        # APK: android/app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # unit tests
```

## Product principles

- Deny by default.
- Allow only exact, explicit, channel-specific matches.
- Fail closed whenever caller identity or adapter compatibility is uncertain.
- Cause no user-visible or audible interruption for blocked calls.
- Keep policy decisions, contact data, and logs local by default.
- Never weaken outgoing emergency calling or Android system emergency alerts.
- Be honest about supported devices, Android builds, root frameworks, ROMs, and communication-app versions.
- Prefer a small, inspectable privileged codebase with isolated adapters.

## Scope and non-goals

Whitelist Calls is a standalone product. It has its own data model, release cycle, security model, update system, tests, and compatibility matrix.

The product does not promise universal compatibility with every Android device or communication app. Selective allowlisting is available only when a channel adapter can reliably identify the actual caller and suppress or reject the call before any user disturbance. Unsupported call-capable apps are treated as untrusted channels and have all incoming call events suppressed.

The project does not aim to:

- intercept message content;
- defeat end-to-end encryption;
- provide a remote shell or arbitrary remote-code facility;
- silently install itself on devices;
- bypass account authentication or impersonate users;
- hide rooting or evade security checks made by banking, payment, DRM, identity, or enterprise apps;
- interfere with outgoing emergency calls or system emergency warnings.

## 1. Rooted Android product

Whitelist Calls consists of:

- a minimal privileged or root daemon;
- system-level hooks for call, audio, vibration, notification, full-screen intent, and connected-device paths;
- a normal companion application for user settings and diagnostics;
- an encrypted local identity and allowlist database;
- a central policy engine;
- a cellular telephony adapter;
- separate adapters for supported communication apps;
- a watchdog, integrity monitor, and compatibility checker;
- signed update and rollback mechanisms;
- safe-mode and recovery procedures.

Root access is a hard requirement. Supported Android versions, devices, ROMs, root frameworks, and app versions must be explicitly certified and published. The companion app must never claim that protection is active solely because it can open or has been granted ordinary Android permissions.

## 2. One identity, multiple call channels

The user creates allowed people or service identities. Each identity may contain:

- one or more telephone numbers;
- normalized E.164 representations and verified local aliases;
- WhatsApp identities;
- WeChat identities;
- Telegram identities;
- Signal identities;
- Facebook Messenger identities;
- other adapter-specific account identifiers;
- business and personal numbers;
- SIM or Android-user context where relevant.

Permission is channel-specific and identifier-specific. Allowing one telephone number does not automatically allow accounts that merely display the same name or photo. Contacts can be imported as candidates, but importing contacts never automatically grants permission.

## 3. Absolute default policy

For every incoming call event, the system must:

1. identify the channel and active adapter;
2. extract the strongest locally available sender identifier;
3. normalize the identifier without using fuzzy name matching;
4. verify the adapter and hook integrity state;
5. look for an exact, active, channel-specific allow rule;
6. apply available authenticity or conflict signals;
7. allow the call only when all required checks pass;
8. otherwise reject or suppress the call before user disturbance;
9. record the decision locally when logging is enabled.

Private numbers, hidden numbers, empty identities, unreadable identities, ambiguous group callers, unknown formats, and conflicting authenticity signals are blocked.

There are no temporary open periods, repeat-caller exceptions, emergency-callback exceptions, challenges, or bypass codes. The onboarding flow must explicitly warn that an incoming callback from an unknown hospital, emergency service, delivery driver, employer, or other expected organization will also be blocked.

Outgoing emergency calls and system emergency notifications must remain unaffected.

## 4. Fail-closed behavior

Each channel has an independently evaluated protection state. If a supported app updates, an adapter signature changes, caller extraction fails, hook integrity is lost, or the decision cannot be made before the app alerts the user:

- the channel enters fail-closed mode;
- all incoming calls from that channel are rejected or fully suppressed;
- selective allowlisting is disabled until compatibility is restored;
- a non-call-time critical warning is shown in the companion app;
- the Coverage Dashboard explains the exact reason;
- no uncertain caller is allowed through.

Protection state must survive reboot. A stale green status is prohibited.

## 5. Cellular telephony adapter

The telephony adapter must support:

- calls arriving through the Android telephony stack;
- dual-SIM devices within the certified matrix;
- E.164 and carefully validated local-number normalization;
- hidden, private, missing, and malformed caller ID;
- call forwarding information where the platform exposes it;
- blocking before the normal incoming-call user interface;
- local decision logging;
- available carrier or Android caller-authenticity signals;
- conflict rules that can reject a nominal number match when authenticity is unreliable.

Caller-ID matching must not be marketed as cryptographic proof. Spoofing resistance is limited by the signals exposed by the device, carrier, region, and Android build.

## 6. Communication-app adapters

Every adapter is an isolated, versioned component with an explicit compatibility contract. An adapter defines:

- supported package identities, signatures, and versions;
- supported Android versions, ROMs, devices, and root frameworks;
- how the actual caller or group initiator is identified;
- how that identity maps to the central allowlist;
- how an incoming call is rejected or suppressed;
- which audio, vibration, notification, full-screen, wearable, automotive, and other output paths must be controlled;
- behavior for voice, video, and group calls;
- startup, runtime, and post-update self-tests;
- error and integrity detection;
- fail-closed behavior;
- rollback compatibility;
- privacy boundaries and data accessed.

A generic adapter must never claim selective caller support based only on a displayed name, profile picture, notification text fragment, or other ambiguous presentation data.

Initial full adapters are required for:

- WhatsApp;
- WeChat;
- Telegram;
- Signal;
- Facebook Messenger.

An app is marked supported only after both allowed and blocked call paths pass the published acceptance tests on a certified configuration.

## 7. Group calls

Group calls follow the strictest interpretation:

- the call may be allowed only when the actual initiator is explicitly permitted and reliably identified;
- if only a group name or group-level notification is available, the call is blocked;
- allowing one group member does not allow the group;
- a group identifier can be allowed only as a separate, explicit rule where an adapter can securely and consistently enforce it;
- ambiguous or changing initiator information causes fail-closed blocking.

## 8. Allowlist management

The companion app allows the user to:

- create, edit, merge, and delete identities;
- select contacts without automatically approving them;
- add and confirm numbers and app accounts separately;
- review normalized identifiers before approval;
- revoke permission immediately;
- inspect and undo recent policy changes;
- associate a previously blocked caller with an identity;
- detect likely duplicates without granting implicit trust;
- export and restore an encrypted allowlist backup;
- review a local configuration audit log.

Every policy mutation must be atomic. A corrupt or partially written policy database must not default to allowing calls.

## 9. Blocked-call log

When enabled, the local blocked-call log may record:

- timestamp;
- channel;
- normalized identifier, if available;
- decision and reason code;
- adapter and compatibility-rule version;
- authentication or conflict state;
- active SIM or account context when relevant;
- protection-health state at decision time.

Blocked calls must not generate sounds, vibration, banners, lock-screen cards, full-screen interfaces, smartwatch alerts, Android Auto alerts, or other visible interruptions. The user opens the log deliberately after the event.

Logging is local by default, can be disabled, and has configurable retention. Exports must be explicit and encrypted.

## 10. Coverage Dashboard

The Coverage Dashboard reports the real protection state per channel:

- Protected and tested;
- Protected, adapter verification required;
- Fail-closed after incompatible update;
- App not installed;
- Unsupported: all incoming calls suppressed;
- System integrity lost;
- Root access unavailable;
- Hooks inactive;
- Self-test overdue or failed.

A channel may be shown as healthy only after a current self-test has passed on the running package version and configuration. The dashboard must distinguish full selective allowlisting from blanket suppression.

## 11. Security and privacy

Because Whitelist Calls has root privileges, its security bar is substantially higher than that of an ordinary application.

Required controls include:

- the smallest practical privileged codebase;
- open and auditable source code;
- signed releases and signed compatibility rules;
- reproducible builds where feasible;
- encrypted local policy storage;
- no mandatory cloud account;
- no advertising use or sale of contact data;
- telemetry disabled by default and stripped of contact identifiers;
- least-privilege separation between the daemon, companion app, and adapters;
- adapter isolation and bounded interfaces;
- authenticated updates and rollback protection;
- an access and policy-change audit trail;
- no remote shell;
- no execution of arbitrary server-delivered code;
- clear disclosure of every privileged data source used.

Threat modeling must cover malicious apps, forged inter-process messages, compromised adapters, stale compatibility data, corrupted databases, caller-ID spoofing, log leakage, update-channel compromise, boot-time races, and attempts to bypass filtering through connected devices.

## 12. Recovery and reliability

Absolute filtering must not make the device permanently unusable. The product requires:

- boot-loop protection;
- removal or disabling through recovery or the supported root manager;
- a watchdog for the policy engine;
- atomic configuration and adapter updates;
- automatic rollback to the last known working adapter set;
- encrypted allowlist backup and verified restoration;
- integrity checks after every reboot;
- safe handling of a corrupt allowlist;
- controlled restart of crashed components;
- explicit critical warnings whenever enforcement is degraded.

Safe mode may prioritize device recovery, but it must clearly state that call protection may not be enforced. The system must never silently present degraded operation as normal protection.

## 13. Root and device-integrity consequences

Onboarding and documentation must clearly explain that:

- rooting changes the Android device security model;
- unlocking a bootloader commonly erases device data;
- banking, payment, DRM, identity, government, and enterprise apps may refuse to work;
- Android, ROM, root-framework, or communication-app updates can break protection;
- a faulty privileged module can cause crashes or boot problems;
- not every phone or ROM can be supported;
- maintaining selective app adapters requires continuous version-specific engineering and testing;
- users remain responsible for device backups and for understanding the consequences of rooting.

Whitelist Calls must not market root as risk-free or guarantee compatibility outside the certified matrix.

## 14. Minimum Credible Product

The first public release is not a one-device proof of concept. It must deliver a credible central call firewall.

### System coverage

- Rooted Android is mandatory.
- At least two commonly used root methods or frameworks are supported.
- A documented set of certified devices, Android versions, and ROMs is published.
- Conventional cellular calling is supported.
- Dual-SIM behavior is tested.
- VoIP calls and full-screen call interfaces are covered.
- Ringing, vibration, notifications, call screens, and connected-device alerts are centrally controlled.
- System integrity is automatically checked after reboot and system updates.

### Selective communication-app coverage

Full adapters are required for WhatsApp, WeChat, Telegram, Signal, and Facebook Messenger.

An adapter qualifies only when it can:

- reliably identify the actual caller;
- map the identifier to the central allowlist;
- allow explicitly approved calls;
- reject or suppress all other calls before user disturbance;
- handle group calls safely;
- run automated checks after app updates;
- enter fail-closed mode when identification or suppression is no longer reliable.

### Generic shield for all other call-capable apps

For apps without a full adapter:

- incoming calls are fully suppressed at system level;
- no calls from the unsupported channel are allowed through;
- selective allowlisting becomes available only after a reliable adapter is delivered;
- the dashboard states: All calls blocked; selective allowlisting unavailable.

This prevents an unsupported communication app from becoming a bypass around the central policy.

### Central identity and allowlist

The release includes:

- people with multiple numbers and app accounts;
- exact channel-specific permission;
- international number normalization;
- contact import without automatic permission;
- duplicate detection;
- immediate revocation;
- default blocking of hidden, empty, malformed, and unreadable identities;
- encrypted local backup and restore;
- no mandatory cloud account.

### Absolute blocked-call experience

A blocked call must cause:

- no ringtone;
- no vibration;
- no heads-up notification;
- no full-screen call interface;
- no smartwatch alert;
- no Android Auto alert;
- no alert on another connected output path;
- no visible interruption of the current activity.

The event may appear only in the optional local blocked-call log.

### Spoofing and uncertainty controls

- Use caller-authenticity information where available.
- Block conflicting or unreliable number identity.
- Always block private and hidden numbers.
- Never use fuzzy name matching.
- Never allow based only on a profile picture or display name.
- Resolve every uncertain enforcement decision by blocking.

### Maintenance and self-testing

- Run daily and update-triggered adapter self-tests.
- Detect changed app packages and versions.
- Use signed adapter compatibility rules.
- Roll back to the last working compatible component where safe.
- Display a current protection status for every channel.
- Provide a user-operated test mode for each installed channel.
- Warn before known system or app updates that can break certified protection, when update information is available in time.

### Recovery and operational reliability

- Boot-loop protection;
- recovery removal or disablement;
- watchdog supervision;
- atomic updates;
- configuration recovery;
- privacy-preserving crash reporting without contact identifiers;
- corrupt-allowlist protection;
- safe policy-engine restart;
- critical warning when root, hooks, or adapters are inactive.

### Release acceptance gate

The first release is considered usable only when all of the following are demonstrated on every published certified configuration:

1. Cellular telephony and all five named communication apps have passed their compatibility suites.
2. Non-allowed calls cause no user disturbance on any covered output path.
3. Allowed calls arrive normally and retain expected answer, decline, audio, and video behavior.
4. Unknown, hidden, malformed, and unreadable identities are always blocked.
5. An incompatible app update automatically activates fail-closed behavior.
6. Unsupported call-capable apps are fully suppressed.
7. Dual-SIM and connected devices cannot bypass the filter.
8. The device can recover safely from module and adapter failures.
9. Policy decisions are recorded locally and remain auditable when logging is enabled.
10. Outgoing emergency calls and Android system emergency alerts remain unaffected.

## Architecture

The selected architecture is a central policy engine with separate adapters.

### Privileged layer

The privileged layer owns enforcement, verified policy loading, hook health, channel state, suppression, audit events, and fail-closed decisions. It exposes a narrow authenticated interface to the companion app and adapters.

### Policy engine

The policy engine is deterministic and channel-independent. Inputs include channel, normalized identifier, authenticity state, group context, adapter health, policy version, and enforcement readiness. Its output is one of:

- Allow;
- Block;
- Suppress channel;
- Fail closed;
- Integrity fault.

No adapter may independently invent a permissive fallback.

### Adapter layer

Adapters translate app- or telephony-specific call events into normalized policy inputs and execute the resulting enforcement action. Adapter failures are isolated from the policy database and other channels.

### Companion application

The unprivileged companion app provides onboarding, identity management, logs, diagnostics, backups, compatibility status, self-tests, and recovery guidance. It does not contain a second, divergent policy engine.

## Suggested domain model

Core entities include:

- Identity;
- ChannelAccount;
- NormalizedIdentifier;
- AllowRule;
- Channel;
- Adapter;
- CertifiedConfiguration;
- CompatibilityRule;
- ProtectionState;
- IncomingCallEvent;
- PolicyDecision;
- AuthenticitySignal;
- SelfTestResult;
- AuditEvent;
- EncryptedBackup.

Every decision should be reproducible from versioned policy input and health state without retaining unnecessary message or call content.

## Testing strategy

The project requires:

- deterministic policy-engine unit tests;
- property-based tests for normalization and deny-by-default behavior;
- adapter contract tests;
- device and ROM integration tests;
- allowed-call and blocked-call end-to-end tests;
- audio, vibration, UI, wearable, automotive, and connected-device disturbance tests;
- reboot, crash, recovery, rollback, and corrupt-state tests;
- app-update and signature-change simulations;
- dual-SIM and group-call test matrices;
- privacy and log-retention tests;
- security review of privileged interfaces and update verification;
- explicit emergency-call regression tests.

Testing must use dedicated accounts, devices, and consented test callers. Green status requires current evidence, not only a successful historical test.

## Delivery phases

1. Build and formally test the standalone policy engine and encrypted identity store.
2. Implement recovery, watchdog, integrity reporting, and the companion app foundation.
3. Certify cellular telephony across the first supported device matrix.
4. Build and certify the five required communication-app adapters.
5. Implement the generic shield for unsupported call-capable apps.
6. Validate every output path, including wearables and Android Auto.
7. Complete update, rollback, backup, recovery, privacy, and security review.
8. Publish only after the Minimum Credible Product acceptance gate passes.

## Definition of done

Whitelist Calls is complete for a release only when:

- the build is reproducible or the remaining reproducibility gap is documented;
- every published configuration has fresh compatibility evidence;
- the central allowlist behaves consistently across all supported channels;
- all uncertainty and adapter failure paths deny access;
- blocked calls produce no user disturbance;
- unsupported call apps cannot bypass protection;
- recovery has been tested from realistic failure states;
- emergency functionality is proven unaffected;
- the Coverage Dashboard never overstates protection;
- privacy, root risks, limitations, and maintenance obligations are documented.

## Legal and ethical use

This software is intended for installation by informed device owners on devices they are authorized to modify. Contributors and distributors must comply with applicable law, platform terms, open-source licenses, telecommunications rules, privacy obligations, and communication-app conditions. The project must not be used to surveil other people, access private communications, or modify devices without informed authorization.

## License

No license has been selected yet. Until a license file is added, no permission is granted to copy, modify, or distribute the source code.