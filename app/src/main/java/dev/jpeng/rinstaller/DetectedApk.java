package dev.jpeng.rinstaller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record DetectedApk(
        String sourcePackage,
        String relativePath,
        long size,
        long modifiedMillis
) {
    public static final int FLATTENED_STRIDE = 3;

    private static final int MAX_PACKAGE_LENGTH = 255;
    private static final int MAX_RELATIVE_LENGTH = 1024;
    private static final Pattern PACKAGE_NAME = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+");

    public DetectedApk {
        sourcePackage = requireSourcePackage(sourcePackage);
        relativePath = requireRelativePath(relativePath);
        if (size <= 0) {
            throw new IllegalArgumentException("APK size must be positive");
        }
        if (modifiedMillis < 0) {
            throw new IllegalArgumentException("modified time must not be negative");
        }
    }

    public static List<DetectedApk> decodeSnapshot(
            String sourcePackage,
            String[] flattened
    ) {
        String validatedPackage = requireSourcePackage(sourcePackage);
        if (flattened == null || flattened.length == 0) {
            return Collections.emptyList();
        }
        if (flattened.length % FLATTENED_STRIDE != 0) {
            throw new IllegalArgumentException("malformed monitor snapshot");
        }

        List<DetectedApk> result = new ArrayList<>(flattened.length / FLATTENED_STRIDE);
        for (int index = 0; index < flattened.length; index += FLATTENED_STRIDE) {
            long size;
            long modifiedMillis;
            try {
                size = Long.parseLong(flattened[index + 1]);
                modifiedMillis = Long.parseLong(flattened[index + 2]);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("malformed monitor metadata", exception);
            }
            result.add(new DetectedApk(
                    validatedPackage,
                    flattened[index],
                    size,
                    modifiedMillis));
        }
        return Collections.unmodifiableList(result);
    }

    public static String requireSourcePackage(String value) {
        String packageName = Objects.requireNonNull(value, "sourcePackage");
        if (packageName.length() > MAX_PACKAGE_LENGTH
                || !PACKAGE_NAME.matcher(packageName).matches()) {
            throw new IllegalArgumentException("invalid source package");
        }
        return packageName;
    }

    public static String requireRelativePath(String value) {
        String relativePath = Objects.requireNonNull(value, "relativePath");
        if (relativePath.isEmpty()
                || relativePath.length() > MAX_RELATIVE_LENGTH
                || relativePath.startsWith("/")
                || relativePath.endsWith("/")
                || relativePath.indexOf('\\') >= 0
                || relativePath.indexOf('\0') >= 0
                || relativePath.indexOf('\r') >= 0
                || relativePath.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("invalid relative path");
        }
        String[] segments = relativePath.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("invalid relative path");
            }
        }
        if (!isSupportedFileName(segments[segments.length - 1])) {
            throw new IllegalArgumentException("unsupported package file");
        }
        return relativePath;
    }

    public static boolean isSupportedFileName(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.endsWith(".apk")
                || lower.endsWith(".apk.1")
                || lower.endsWith(".apks")
                || lower.endsWith(".apkm")
                || lower.endsWith(".xapk")
                || lower.endsWith(".zip");
    }
}
