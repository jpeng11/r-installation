package dev.jpeng.rinstaller;

import android.os.ParcelFileDescriptor;

/**
 * Read-only capability service for APK files created inside an approved app's
 * external-data directory.
 *
 * Snapshot results are flattened groups of:
 * relative path, byte size, last-modified milliseconds.
 *
 * Candidate registration results are:
 * opaque candidate ID, lowercase SHA-256.
 */
interface IPrivilegedFileMonitor {
    String[] snapshot(String sourcePackage);

    String[] registerCandidate(
            String sourcePackage,
            String relativePath,
            long expectedSize,
            long expectedModifiedMillis);

    ParcelFileDescriptor openCandidate(String candidateId);

    void destroy();
}
