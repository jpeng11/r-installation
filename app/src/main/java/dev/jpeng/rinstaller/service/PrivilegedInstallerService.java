package dev.jpeng.rinstaller.service;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import dev.jpeng.rinstaller.IPrivilegedInstaller;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;

public final class PrivilegedInstallerService extends IPrivilegedInstaller.Stub {
    private static final String TAG = "RInstallerNext";
    private static final long COMMAND_TIMEOUT_SECONDS = 180;
    public static final int FLAG_REPLACE = 1;
    public static final int FLAG_ALLOW_DOWNGRADE = 1 << 1;
    public static final int FLAG_ALLOW_TEST_ONLY = 1 << 2;

    private static final Pattern SESSION_ID = Pattern.compile("\\[(\\d+)]");

    public PrivilegedInstallerService() {}

    public PrivilegedInstallerService(Context ignored) {}

    @Override
    public String install(
            ParcelFileDescriptor[] files,
            String[] names,
            long[] sizes,
            String sourcePackage,
            int flags
    ) {
        if (files == null || names == null || sizes == null
                || files.length == 0
                || files.length != names.length
                || files.length != sizes.length) {
            return "Failure [invalid APK payload]";
        }
        for (long size : sizes) {
            if (size <= 0) {
                return "Failure [invalid APK size]";
            }
        }

        try {
            return files.length == 1
                    ? installSingle(files[0], sizes[0], sourcePackage, flags)
                    : installSplitSet(files, names, sizes, sourcePackage, flags);
        } catch (Exception exception) {
            return "Failure [" + exception.getClass().getSimpleName() + ": "
                    + safeMessage(exception) + "]";
        } finally {
            closeAll(files);
        }
    }

    private String installSingle(
            ParcelFileDescriptor file,
            long size,
            String sourcePackage,
            int flags
    ) throws IOException, InterruptedException {
        List<String> command = baseInstallCommand("install", sourcePackage, flags);
        command.add("-S");
        command.add(Long.toString(size));
        // Samsung's Android 16 package-manager help advertises PATH|- here, but
        // its parser rejects a literal "-" as an unknown option. Supplying -S
        // with no path selects stdin on both AOSP and that implementation.
        return runWithInput(command, file).output();
    }

    private String installSplitSet(
            ParcelFileDescriptor[] files,
            String[] names,
            long[] sizes,
            String sourcePackage,
            int flags
    ) throws IOException, InterruptedException {
        long total = Arrays.stream(sizes).sum();
        List<String> create = baseInstallCommand("install-create", sourcePackage, flags);
        create.add("-S");
        create.add(Long.toString(total));
        CommandResult created = run(create);
        if (!created.success()) {
            return created.output();
        }
        Matcher matcher = SESSION_ID.matcher(created.output());
        if (!matcher.find()) {
            return "Failure [unable to parse install session: " + created.output() + "]";
        }
        String sessionId = matcher.group(1);
        try {
            for (int index = 0; index < files.length; index++) {
                List<String> write = new ArrayList<>();
                write.add("/system/bin/pm");
                write.add("install-write");
                write.add("-S");
                write.add(Long.toString(sizes[index]));
                write.add(sessionId);
                write.add(sanitizeSplitName(names[index], index));
                // See installSingle(): -S with no path selects stdin and avoids
                // the Samsung Android 16 parser incompatibility.
                CommandResult written = runWithInput(write, files[index]);
                if (!written.success()) {
                    run(List.of("/system/bin/pm", "install-abandon", sessionId));
                    return written.output();
                }
            }
            return run(List.of("/system/bin/pm", "install-commit", sessionId)).output();
        } catch (Exception exception) {
            run(List.of("/system/bin/pm", "install-abandon", sessionId));
            throw exception;
        }
    }

    private List<String> baseInstallCommand(String operation, String sourcePackage, int flags) {
        List<String> command = new ArrayList<>();
        command.add("/system/bin/pm");
        command.add(operation);
        if ((flags & FLAG_REPLACE) != 0) {
            command.add("-r");
        }
        if ((flags & FLAG_ALLOW_DOWNGRADE) != 0) {
            command.add("-d");
        }
        if ((flags & FLAG_ALLOW_TEST_ONLY) != 0) {
            command.add("-t");
        }
        command.add("--user");
        command.add("current");
        if (sourcePackage != null && !sourcePackage.isBlank()) {
            command.add("-i");
            command.add(sourcePackage);
        }
        return command;
    }

    private CommandResult runWithInput(List<String> command, ParcelFileDescriptor descriptor)
            throws IOException, InterruptedException {
        Log.i(TAG, "Starting package-manager stream: " + operationName(command));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        long bytes;
        try (InputStream source = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
             OutputStream target = process.getOutputStream()) {
            bytes = copy(source, target);
        }
        if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            Log.e(TAG, "Package-manager command timed out: " + operationName(command));
            return new CommandResult(-1, "Failure [package manager timed out]");
        }
        String output = readOutput(process);
        int exitCode = process.exitValue();
        Log.i(TAG, operationName(command) + " streamed " + bytes
                + " bytes and exited " + exitCode + ": " + normalize(output));
        return new CommandResult(exitCode, normalize(output));
    }

    private CommandResult run(List<String> command) throws IOException, InterruptedException {
        Log.i(TAG, "Starting package-manager command: " + operationName(command));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getOutputStream().close();
        if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            Log.e(TAG, "Package-manager command timed out: " + operationName(command));
            return new CommandResult(-1, "Failure [package manager timed out]");
        }
        String output = readOutput(process);
        int exitCode = process.exitValue();
        Log.i(TAG, operationName(command) + " exited " + exitCode + ": " + normalize(output));
        return new CommandResult(exitCode, normalize(output));
    }

    private String operationName(List<String> command) {
        return command.size() > 1 ? command.get(1) : "pm";
    }

    private String readOutput(Process process) throws IOException {
        try (InputStream input = process.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            copy(input, output);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private long copy(InputStream input, OutputStream output) throws IOException {
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
            total += read;
        }
        return total;
    }

    private String normalize(String output) {
        String value = output == null ? "" : output.trim();
        return value.isEmpty() ? "Failure [package manager returned no output]" : value;
    }

    private String sanitizeSplitName(String name, int index) {
        String value = name == null ? "split-" + index + ".apk" : name;
        value = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return value.toLowerCase(Locale.ROOT).endsWith(".apk") ? value : value + ".apk";
    }

    private String safeMessage(Exception exception) {
        return exception.getMessage() == null ? "no details" : exception.getMessage();
    }

    private void closeAll(ParcelFileDescriptor[] files) {
        if (files == null) {
            return;
        }
        for (ParcelFileDescriptor file : files) {
            if (file == null) {
                continue;
            }
            try {
                file.close();
            } catch (IOException ignored) {
                // Best effort.
            }
        }
    }

    @Override
    public void destroy() {
        System.exit(0);
    }

    private record CommandResult(int exitCode, String output) {
        boolean success() {
            return exitCode == 0 && output.startsWith("Success");
        }
    }
}
