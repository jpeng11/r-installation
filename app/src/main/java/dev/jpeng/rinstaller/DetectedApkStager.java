package dev.jpeng.rinstaller;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

final class DetectedApkStager {
    private static final long MAX_APK_BYTES = 4L * 1024 * 1024 * 1024;

    record Result(
            PayloadPreparer.PreparedPayload payload,
            String fileName,
            String packageName,
            String version,
            String signerSha256,
            String fileSha256,
            long size
    ) {}

    private DetectedApkStager() {
    }

    static Result stage(
            Context context,
            ParcelFileDescriptor sourceDescriptor,
            String displayName,
            long expectedSize,
            String expectedSha256
    ) throws IOException {
        if (sourceDescriptor == null
                || expectedSize <= 0
                || expectedSize > MAX_APK_BYTES
                || expectedSha256 == null
                || expectedSha256.length() != 64) {
            closeQuietly(sourceDescriptor);
            throw new IOException("Invalid detected APK metadata.");
        }

        File directory = new File(
                context.getCacheDir(),
                "payload-detected-" + UUID.randomUUID());
        if (!directory.mkdirs()) {
            closeQuietly(sourceDescriptor);
            throw new IOException("Unable to create a private staging directory.");
        }
        String fileName = PayloadPreparer.directPayloadName(displayName);
        File stagedFile = new File(directory, fileName);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long copied = 0;
            try (InputStream input =
                         new ParcelFileDescriptor.AutoCloseInputStream(sourceDescriptor);
                 FileOutputStream output = new FileOutputStream(stagedFile)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    copied += read;
                    if (copied > expectedSize || copied > MAX_APK_BYTES) {
                        throw new IOException("Detected APK size changed while staging.");
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
                output.getFD().sync();
            }
            if (copied != expectedSize) {
                throw new IOException("Detected APK size changed while staging.");
            }
            String fileSha256 = hex(digest.digest());
            if (!constantTimeHexEquals(expectedSha256, fileSha256)) {
                throw new IOException("Detected APK contents changed while staging.");
            }

            PackageInfo archive = context.getPackageManager().getPackageArchiveInfo(
                    stagedFile.getAbsolutePath(),
                    PackageManager.GET_SIGNING_CERTIFICATES);
            if (archive == null
                    || archive.packageName == null
                    || archive.packageName.isBlank()) {
                throw new IOException("The detected file is not a valid APK.");
            }
            String signer = signerDigest(archive.signingInfo);
            if (signer == null) {
                throw new IOException("The detected APK has no verifiable signer.");
            }
            String versionName = archive.versionName == null
                    ? Long.toString(archive.getLongVersionCode())
                    : archive.versionName + " (" + archive.getLongVersionCode() + ")";
            ParcelFileDescriptor stagedDescriptor = ParcelFileDescriptor.open(
                    stagedFile,
                    ParcelFileDescriptor.MODE_READ_ONLY);
            PayloadPreparer.PreparedPayload payload =
                    new PayloadPreparer.PreparedPayload(
                            List.of(new PayloadPreparer.Part(
                                    fileName,
                                    stagedDescriptor,
                                    copied)),
                            directory);
            return new Result(
                    payload,
                    fileName,
                    archive.packageName,
                    versionName,
                    signer,
                    fileSha256,
                    copied);
        } catch (Exception exception) {
            closeQuietly(sourceDescriptor);
            deleteRecursively(directory);
            if (exception instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException(exception);
        }
    }

    static Result reopen(Result current) throws IOException {
        if (current == null
                || current.payload() == null
                || current.payload().temporaryDirectory == null) {
            throw new IOException("The private staged APK is unavailable.");
        }
        for (PayloadPreparer.Part part : current.payload().parts) {
            closeQuietly(part.descriptor());
        }
        File file = new File(
                current.payload().temporaryDirectory,
                current.fileName());
        if (!file.isFile() || file.length() != current.size()) {
            throw new IOException("The private staged APK changed.");
        }
        ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_READ_ONLY);
        PayloadPreparer.PreparedPayload payload =
                new PayloadPreparer.PreparedPayload(
                        List.of(new PayloadPreparer.Part(
                                current.fileName(),
                                descriptor,
                                current.size())),
                        current.payload().temporaryDirectory);
        return new Result(
                payload,
                current.fileName(),
                current.packageName(),
                current.version(),
                current.signerSha256(),
                current.fileSha256(),
                current.size());
    }

    private static String signerDigest(SigningInfo signingInfo) throws Exception {
        if (signingInfo == null) {
            return null;
        }
        Signature[] signatures = signingInfo.getApkContentsSigners();
        if (signatures == null || signatures.length == 0) {
            return null;
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return hex(digest.digest(signatures[0].toByteArray()));
    }

    private static boolean constantTimeHexEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.toLowerCase(Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                actual.toLowerCase(Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static String hex(byte[] bytes) {
        StringBuilder output = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            output.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return output.toString();
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        if (descriptor == null) {
            return;
        }
        try {
            descriptor.close();
        } catch (IOException ignored) {
            // Best effort.
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
