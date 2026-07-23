package org.zhavoronkov.tokenpulse.settings;

import com.intellij.credentialStore.CredentialAttributes;
import org.jetbrains.annotations.NotNull;

/**
 * Tiny Java shim to construct {@link CredentialAttributes} via its JVM-level
 * {@code (String serviceName, String userName)} constructor.
 *
 * <p>Rationale: calling {@code CredentialAttributes(serviceName, userName)} from Kotlin
 * routes through Kotlin's default-argument synthetic constructor
 * {@code (String, String, Class, int, DefaultConstructorMarker)}, which was marked
 * {@code @Deprecated(level = ERROR)} in the 2026.1 platform (build 261) in favor of
 * the plain {@code (serviceName [, userName])} JVM overloads. Emitting the call from
 * Java forces the compiler to bind to the non-deprecated JVM ctor descriptor
 * {@code <init>(Ljava/lang/String;Ljava/lang/String;)V} directly, so the JetBrains
 * Plugin Verifier reports zero deprecated API usage against 2026.1+ while still
 * compiling cleanly against 2024.2 (sinceBuild=242).
 */
final class CredentialAttributesFactory {
    private CredentialAttributesFactory() {}

    static @NotNull CredentialAttributes create(@NotNull String serviceName, @NotNull String userName) {
        return new CredentialAttributes(serviceName, userName);
    }
}
