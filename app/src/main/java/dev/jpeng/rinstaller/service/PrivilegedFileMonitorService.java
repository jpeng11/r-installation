package dev.jpeng.rinstaller.service;

import android.content.Context;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.Log;

import dev.jpeng.rinstaller.DetectedApk;
import dev.jpeng.rinstaller.IPrivilegedFileMonitor;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class PrivilegedFileMonitorService extends IPrivilegedFileMonitor.Stub {
    private static final String TAG = "RInstallerNext";
    private static final int SNAPSHOT_STRIDE = DetectedApk.FLATTENED_STRIDE;
    private static final int MAX_SNAPSHOT_ITEMS = 512;
    private static final int MAX_SCANNED_ENTRIES = 4096;
    private static final int MAX_DIRECTORIES = 256;
    private static final int MAX_DEPTH = 12;
    private static final int MAX_REGISTERED_CANDIDATES = 128;
    private static final int ANDROID_UIDS_PER_USER = 100_000;
    private static final long MAX_CANDIDATE_BYTES = 4L * 1024 * 1024 * 1024;
    private static final long CANDIDATE_TTL_MILLIS = TimeUnit.MINUTES.toMillis(15);
    private static final Pattern OPAQUE_ID = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, Candidate> candidates = new ConcurrentHashMap<>();

    public PrivilegedFileMonitorService() {}

    public PrivilegedFileMonitorService(Context ignored) {}

    @Override
    public String[] snapshot(String sourcePackage) {
        String validatedPackage = DetectedApk.requireSourcePackage(sourcePackage);
        Path root = sourceRoot(validatedPackage, callingUserId());
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            Log.d(TAG, "Source folder is absent for " + validatedPackage);
            return new String[0];
        }

        try {
            requireDirectoryWithoutLinks(root);
            ScanState state = new ScanState();
            scan(root, root, 0, state);
            state.results.sort(Comparator.comparing(SnapshotEntry::relativePath));

            String[] flattened = new String[state.results.size() * SNAPSHOT_STRIDE];
            int output = 0;
            for (SnapshotEntry entry : state.results) {
                flattened[output++] = entry.relativePath;
                flattened[output++] = Long.toString(entry.size);
                flattened[output++] = Long.toString(entry.modifiedMillis);
            }
            return flattened;
        } catch (IOException | RuntimeException exception) {
            Log.w(TAG, "Privileged source scan failed for "
                    + validatedPackage, exception);
            throw new IllegalStateException("unable to snapshot source storage");
        }
    }

    @Override
    public String[] registerCandidate(
            String sourcePackage,
            String relativePath,
            long expectedSize,
            long expectedModifiedMillis
    ) {
        String validatedPackage = DetectedApk.requireSourcePackage(sourcePackage);
        String validatedRelative = DetectedApk.requireRelativePath(relativePath);
        if (expectedSize <= 0 || expectedSize > MAX_CANDIDATE_BYTES
                || expectedModifiedMillis < 0) {
            throw new IllegalArgumentException("invalid expected metadata");
        }

        int callerUserId = callingUserId();
        Path root = sourceRoot(validatedPackage, callerUserId);
        Path file = resolveRelative(root, validatedRelative);
        try {
            BasicFileAttributes attributes = requireRegularFileWithoutLinks(root, file);
            if (attributes.size() != expectedSize
                    || attributes.lastModifiedTime().toMillis() != expectedModifiedMillis) {
                throw new SecurityException("candidate changed after snapshot");
            }

            HashResult hashed = hashStableFile(root, validatedRelative);
            if (hashed.size != expectedSize) {
                throw new SecurityException("candidate changed while registering");
            }

            cleanupExpiredCandidates();
            makeCandidateRoom();
            String id = newOpaqueId();
            candidates.put(id, new Candidate(
                    callerUserId,
                    validatedPackage,
                    validatedRelative,
                    hashed.sha256,
                    hashed.size,
                    SystemClock.elapsedRealtime()));
            return new String[]{id, hashed.sha256};
        } catch (SecurityException exception) {
            throw exception;
        } catch (IOException | ErrnoException | RuntimeException exception) {
            throw new IllegalStateException("unable to register candidate");
        }
    }

    @Override
    public ParcelFileDescriptor openCandidate(String candidateId) {
        if (candidateId == null || !OPAQUE_ID.matcher(candidateId).matches()) {
            throw new SecurityException("unknown or expired candidate");
        }
        cleanupExpiredCandidates();
        Candidate candidate = candidates.get(candidateId);
        if (candidate == null) {
            throw new SecurityException("unknown or expired candidate");
        }
        if (candidate.userId != callingUserId()) {
            candidates.remove(candidateId, candidate);
            throw new SecurityException("candidate belongs to another Android user");
        }

        Path root = sourceRoot(candidate.sourcePackage, candidate.userId);
        Path file = resolveRelative(root, candidate.relativePath);
        FileDescriptor descriptor = null;
        try {
            requireRegularFileWithoutLinks(root, file);
            descriptor = openReadOnlyBeneath(root, candidate.relativePath);
            StructStat before = Os.fstat(descriptor);
            if (!OsConstants.S_ISREG(before.st_mode)
                    || before.st_size != candidate.size
                    || before.st_size <= 0
                    || before.st_size > MAX_CANDIDATE_BYTES) {
                throw new SecurityException("candidate no longer matches registration");
            }

            String sha256 = hashDescriptor(descriptor, candidate.size);
            StructStat after = Os.fstat(descriptor);
            if (!sameFile(before, after) || !candidate.sha256.equals(sha256)) {
                throw new SecurityException("candidate no longer matches registration");
            }

            Os.lseek(descriptor, 0, OsConstants.SEEK_SET);
            ParcelFileDescriptor result = ParcelFileDescriptor.dup(descriptor);
            candidates.remove(candidateId, candidate);
            return result;
        } catch (SecurityException exception) {
            candidates.remove(candidateId, candidate);
            throw exception;
        } catch (IOException | ErrnoException | RuntimeException exception) {
            candidates.remove(candidateId, candidate);
            throw new IllegalStateException("unable to open candidate");
        } finally {
            closeQuietly(descriptor);
        }
    }

    @Override
    public void destroy() {
        candidates.clear();
        System.exit(0);
    }

    private void scan(
            Path root,
            Path directory,
            int depth,
            ScanState state
    ) throws IOException {
        if (state.stopped
                || depth > MAX_DEPTH
                || ++state.directories > MAX_DIRECTORIES) {
            state.stopped = true;
            return;
        }

        List<Path> children = new ArrayList<>();
        boolean entryLimitReached = false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) {
                children.add(child);
                if (++state.scannedEntries >= MAX_SCANNED_ENTRIES) {
                    entryLimitReached = true;
                    break;
                }
            }
        }
        children.sort(Comparator.comparing(path -> path.getFileName().toString()));

        for (Path child : children) {
            if (state.stopped || state.results.size() >= MAX_SNAPSHOT_ITEMS) {
                state.stopped = true;
                return;
            }
            BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(
                        child,
                        BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
            } catch (IOException exception) {
                continue;
            }
            if (attributes.isSymbolicLink()) {
                continue;
            }
            if (attributes.isDirectory()) {
                if (!entryLimitReached) {
                    scan(root, child, depth + 1, state);
                }
            } else if (attributes.isRegularFile()
                    && attributes.size() > 0
                    && attributes.size() <= MAX_CANDIDATE_BYTES
                    && DetectedApk.isSupportedFileName(child.getFileName().toString())) {
                String relative = root.relativize(child).toString();
                try {
                    relative = DetectedApk.requireRelativePath(relative);
                    state.results.add(new SnapshotEntry(
                            relative,
                            attributes.size(),
                            attributes.lastModifiedTime().toMillis()));
                } catch (IllegalArgumentException ignored) {
                    // Skip names that cannot safely cross the capability boundary.
                }
            }
        }
        if (entryLimitReached) {
            state.stopped = true;
        }
    }

    private int callingUserId() {
        int callingUid = Binder.getCallingUid();
        if (callingUid < 0) {
            throw new SecurityException("invalid Binder caller");
        }
        return callingUid / ANDROID_UIDS_PER_USER;
    }

    private Path sourceRoot(String sourcePackage, int userId) {
        if (userId < 0) {
            throw new SecurityException("invalid Android user");
        }
        return Paths.get(
                        "/storage",
                        "emulated",
                        Integer.toString(userId))
                .resolve(Paths.get("Android", "data", sourcePackage))
                .normalize();
    }

    private Path resolveRelative(Path root, String relativePath) {
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            throw new IllegalArgumentException("invalid relative path");
        }
        return resolved;
    }

    private BasicFileAttributes requireRegularFileWithoutLinks(Path root, Path file)
            throws IOException {
        requireDirectoryWithoutLinks(root);
        Path relative = root.relativize(file);
        Path current = root;
        for (int index = 0; index < relative.getNameCount(); index++) {
            current = current.resolve(relative.getName(index));
            BasicFileAttributes attributes = Files.readAttributes(
                    current,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink()) {
                throw new SecurityException("symbolic links are not accepted");
            }
            boolean finalComponent = index == relative.getNameCount() - 1;
            if (finalComponent ? !attributes.isRegularFile() : !attributes.isDirectory()) {
                throw new SecurityException("candidate is not a regular file");
            }
            if (finalComponent) {
                return attributes;
            }
        }
        throw new SecurityException("candidate is not a regular file");
    }

    private void requireDirectoryWithoutLinks(Path directory) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                directory,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
            throw new SecurityException("source storage is not a directory");
        }
    }

    private HashResult hashStableFile(Path root, String relativePath)
            throws IOException, ErrnoException {
        FileDescriptor descriptor = openReadOnlyBeneath(root, relativePath);
        try {
            StructStat before = Os.fstat(descriptor);
            if (!OsConstants.S_ISREG(before.st_mode)
                    || before.st_size <= 0
                    || before.st_size > MAX_CANDIDATE_BYTES) {
                throw new SecurityException("candidate is not an accepted regular file");
            }
            String sha256 = hashDescriptor(descriptor, before.st_size);
            StructStat after = Os.fstat(descriptor);
            if (!sameFile(before, after)) {
                throw new SecurityException("candidate changed while hashing");
            }
            return new HashResult(sha256, before.st_size);
        } finally {
            closeQuietly(descriptor);
        }
    }

    private FileDescriptor openReadOnlyBeneath(
            Path root,
            String relativePath
    ) throws IOException, ErrnoException {
        String validatedRelative = DetectedApk.requireRelativePath(relativePath);
        FileDescriptor current = Os.open(
                root.toString(),
                OsConstants.O_RDONLY
                        | OsConstants.O_CLOEXEC
                        | OsConstants.O_NOFOLLOW,
                0);
        try {
            if (!OsConstants.S_ISDIR(Os.fstat(current).st_mode)) {
                throw new SecurityException("source storage is not a directory");
            }
            String[] segments = validatedRelative.split("/", -1);
            for (int index = 0; index < segments.length; index++) {
                boolean finalComponent = index == segments.length - 1;
                int flags = OsConstants.O_RDONLY
                        | OsConstants.O_CLOEXEC
                        | OsConstants.O_NOFOLLOW;
                ParcelFileDescriptor anchor = ParcelFileDescriptor.dup(current);
                FileDescriptor next;
                try {
                    next = Os.open(
                            "/proc/self/fd/" + anchor.getFd()
                                    + "/" + segments[index],
                            flags,
                            0);
                } finally {
                    anchor.close();
                }
                closeQuietly(current);
                current = next;
                StructStat opened = Os.fstat(current);
                if (finalComponent
                        ? !OsConstants.S_ISREG(opened.st_mode)
                        : !OsConstants.S_ISDIR(opened.st_mode)) {
                    throw new SecurityException(
                            finalComponent
                                    ? "candidate is not a regular file"
                                    : "candidate parent is not a directory");
                }
            }
            FileDescriptor result = current;
            current = null;
            return result;
        } finally {
            closeQuietly(current);
        }
    }

    private String hashDescriptor(FileDescriptor descriptor, long expectedSize)
            throws IOException, ErrnoException {
        FileDescriptor duplicate = Os.dup(descriptor);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception exception) {
            closeQuietly(duplicate);
            throw new IllegalStateException("SHA-256 is unavailable");
        }

        long total = 0;
        try (FileInputStream input = new FileInputStream(duplicate)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_CANDIDATE_BYTES || total > expectedSize) {
                    throw new SecurityException("candidate changed while hashing");
                }
                digest.update(buffer, 0, read);
            }
        }
        if (total != expectedSize) {
            throw new SecurityException("candidate changed while hashing");
        }
        return toHex(digest.digest());
    }

    private boolean sameFile(StructStat first, StructStat second) {
        return first.st_dev == second.st_dev
                && first.st_ino == second.st_ino
                && first.st_mode == second.st_mode
                && first.st_size == second.st_size
                && first.st_mtime == second.st_mtime;
    }

    private String newOpaqueId() {
        byte[] random = new byte[32];
        String candidate;
        do {
            secureRandom.nextBytes(random);
            candidate = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        } while (candidates.containsKey(candidate));
        return candidate;
    }

    private void cleanupExpiredCandidates() {
        long now = SystemClock.elapsedRealtime();
        candidates.entrySet().removeIf(entry ->
                now - entry.getValue().registeredAtMillis > CANDIDATE_TTL_MILLIS);
    }

    private void makeCandidateRoom() {
        while (candidates.size() >= MAX_REGISTERED_CANDIDATES) {
            Map.Entry<String, Candidate> oldest = candidates.entrySet().stream()
                    .min(Comparator.comparingLong(entry ->
                            entry.getValue().registeredAtMillis))
                    .orElse(null);
            if (oldest == null) {
                return;
            }
            candidates.remove(oldest.getKey(), oldest.getValue());
        }
    }

    private String toHex(byte[] value) {
        StringBuilder output = new StringBuilder(value.length * 2);
        for (byte item : value) {
            output.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        }
        return output.toString();
    }

    private void closeQuietly(FileDescriptor descriptor) {
        if (descriptor == null || !descriptor.valid()) {
            return;
        }
        try {
            Os.close(descriptor);
        } catch (ErrnoException ignored) {
            // Best effort.
        }
    }

    private static final class ScanState {
        final List<SnapshotEntry> results = new ArrayList<>();
        int scannedEntries;
        int directories;
        boolean stopped;
    }

    private record SnapshotEntry(
            String relativePath,
            long size,
            long modifiedMillis
    ) {}

    private record HashResult(String sha256, long size) {}

    private record Candidate(
            int userId,
            String sourcePackage,
            String relativePath,
            String sha256,
            long size,
            long registeredAtMillis
    ) {}
}
