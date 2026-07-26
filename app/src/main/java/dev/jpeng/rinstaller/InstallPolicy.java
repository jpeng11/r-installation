package dev.jpeng.rinstaller;

final class InstallPolicy {
    private InstallPolicy() {}

    static boolean mayInstallSilently(
            boolean identityVerified,
            boolean packageAllowlisted,
            boolean signingCertificateMatches,
            boolean shizukuReady
    ) {
        return identityVerified
                && packageAllowlisted
                && signingCertificateMatches
                && shizukuReady;
    }
}
