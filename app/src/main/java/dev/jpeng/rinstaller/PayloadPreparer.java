package dev.jpeng.rinstaller;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class PayloadPreparer {
    private static final long MAX_ARCHIVE_BYTES = 4L * 1024 * 1024 * 1024;
    private static final int MAX_APK_ENTRIES = 200;

    record Part(String name, ParcelFileDescriptor descriptor, long size) {}

    static final class PreparedPayload implements Closeable {
        final List<Part> parts;
        final File temporaryDirectory;

        PreparedPayload(List<Part> parts, File temporaryDirectory) {
            this.parts = parts;
            this.temporaryDirectory = temporaryDirectory;
        }

        ParcelFileDescriptor[] descriptors() {
            return parts.stream().map(Part::descriptor).toArray(ParcelFileDescriptor[]::new);
        }

        String[] names() {
            return parts.stream().map(Part::name).toArray(String[]::new);
        }

        long[] sizes() {
            return parts.stream().mapToLong(Part::size).toArray();
        }

        @Override
        public void close() {
            for (Part part : parts) {
                try {
                    part.descriptor.close();
                } catch (IOException ignored) {
                    // Best effort.
                }
            }
            deleteRecursively(temporaryDirectory);
        }
    }

    private PayloadPreparer() {}

    static List<Uri> extractUris(Intent intent) {
        Set<Uri> uris = new LinkedHashSet<>();
        if (intent.getData() != null) {
            uris.add(intent.getData());
        }
        ClipData clipData = intent.getClipData();
        if (clipData != null) {
            for (int index = 0; index < clipData.getItemCount(); index++) {
                Uri uri = clipData.getItemAt(index).getUri();
                if (uri != null) {
                    uris.add(uri);
                }
            }
        }
        if (expectsMultipleStreams(intent.getAction())) {
            ArrayList<Uri> streams = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri.class)
                    : intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (streams != null) {
                uris.addAll(streams);
            }
        } else {
            Uri stream = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class)
                    : intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream != null) {
                uris.add(stream);
            }
        }
        return new ArrayList<>(uris);
    }

    static boolean expectsMultipleStreams(String action) {
        return Intent.ACTION_SEND_MULTIPLE.equals(action);
    }

    static PreparedPayload prepare(Context context, List<Uri> uris) throws IOException {
        if (uris.isEmpty()) {
            throw new IOException("No APK or bundle URI was supplied.");
        }
        File temporaryDirectory = new File(context.getCacheDir(), "payload-" + UUID.randomUUID());
        if (!temporaryDirectory.mkdirs()) {
            throw new IOException("Unable to create temporary payload directory.");
        }

        List<File> files = new ArrayList<>();
        try {
            for (Uri uri : uris) {
                String displayName = displayName(context, uri);
                String mimeType = context.getContentResolver().getType(uri);
                if (isArchive(displayName, mimeType)) {
                    extractArchive(context, uri, temporaryDirectory, files);
                } else {
                    String directName = directPayloadName(displayName);
                    File destination = new File(
                            temporaryDirectory, uniqueName(files, directName));
                    copyUri(context, uri, destination);
                    files.add(destination);
                }
            }

            if (files.isEmpty()) {
                throw new IOException("No APK entries were found.");
            }

            List<Part> parts = new ArrayList<>();
            for (File file : files) {
                if (!file.getName().toLowerCase(Locale.ROOT).endsWith(".apk")) {
                    throw new IOException("Unsupported payload: " + file.getName());
                }
                parts.add(new Part(file.getName(),
                        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY),
                        file.length()));
            }
            return new PreparedPayload(parts, temporaryDirectory);
        } catch (Exception exception) {
            deleteRecursively(temporaryDirectory);
            if (exception instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException(exception);
        }
    }

    private static void extractArchive(Context context, Uri uri, File directory, List<File> output)
            throws IOException {
        long total = 0;
        int count = 0;
        try (InputStream raw = context.getContentResolver().openInputStream(uri);
             ZipInputStream zip = new ZipInputStream(requireInput(raw))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()
                        || !entry.getName().toLowerCase(Locale.ROOT).endsWith(".apk")) {
                    continue;
                }
                if (++count > MAX_APK_ENTRIES) {
                    throw new IOException("Bundle contains too many APK entries.");
                }
                String baseName = new File(entry.getName()).getName();
                File destination = new File(directory, uniqueName(output, sanitize(baseName, "split.apk")));
                long written = copyLimited(zip, destination, MAX_ARCHIVE_BYTES - total);
                total += written;
                if (total > MAX_ARCHIVE_BYTES) {
                    throw new IOException("Bundle exceeds the 4 GiB safety limit.");
                }
                output.add(destination);
            }
        }
    }

    private static void copyUri(Context context, Uri uri, File destination) throws IOException {
        InputStream input;
        if ("file".equals(uri.getScheme())) {
            input = new java.io.FileInputStream(new File(uri.getPath()));
        } else {
            input = context.getContentResolver().openInputStream(uri);
        }
        try (InputStream source = requireInput(input);
             FileOutputStream target = new FileOutputStream(destination)) {
            copy(source, target);
        }
    }

    private static long copy(InputStream source, FileOutputStream target) throws IOException {
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = source.read(buffer)) != -1) {
            target.write(buffer, 0, read);
            total += read;
        }
        return total;
    }

    private static long copyLimited(InputStream input, File destination, long remaining)
            throws IOException {
        if (remaining <= 0) {
            throw new IOException("Bundle exceeds the 4 GiB safety limit.");
        }
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (FileOutputStream output = new FileOutputStream(destination)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > remaining) {
                    throw new IOException("Bundle exceeds the 4 GiB safety limit.");
                }
                output.write(buffer, 0, read);
            }
        }
        return total;
    }

    private static String displayName(Context context, Uri uri) {
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = context.getContentResolver().query(
                    uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    String value = cursor.getString(0);
                    if (value != null && !value.isBlank()) {
                        return value;
                    }
                }
            } catch (RuntimeException ignored) {
                // Fall back to the URI segment.
            }
        }
        String segment = uri.getLastPathSegment();
        return segment == null ? "base.apk" : segment;
    }

    static boolean isArchive(String name, String ignoredMimeType) {
        // APKs are ZIP files and several app stores report them as application/zip.
        // Only an explicit container suffix is reliable enough to trigger extraction.
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".apks")
                || lower.endsWith(".apkm")
                || lower.endsWith(".xapk")
                || lower.endsWith(".zip");
    }

    static String directPayloadName(String displayName) {
        String sanitized = sanitize(displayName, "base.apk");
        return sanitized.toLowerCase(Locale.ROOT).endsWith(".apk")
                ? sanitized
                : "base.apk";
    }

    private static String sanitize(String value, String fallback) {
        String name = value == null ? fallback : new File(value).getName();
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return name.isBlank() ? fallback : name;
    }

    private static String uniqueName(List<File> existing, String desired) {
        String candidate = desired;
        int suffix = 1;
        while (containsName(existing, candidate)) {
            int dot = desired.lastIndexOf('.');
            candidate = dot > 0
                    ? desired.substring(0, dot) + "-" + suffix + desired.substring(dot)
                    : desired + "-" + suffix;
            suffix++;
        }
        return candidate;
    }

    private static boolean containsName(List<File> existing, String name) {
        for (File file : existing) {
            if (file.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static InputStream requireInput(InputStream input) throws IOException {
        if (input == null) {
            throw new IOException("The selected file cannot be opened.");
        }
        return input;
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
