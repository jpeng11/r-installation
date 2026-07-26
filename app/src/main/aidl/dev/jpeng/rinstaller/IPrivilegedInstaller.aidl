package dev.jpeng.rinstaller;

import android.os.ParcelFileDescriptor;

interface IPrivilegedInstaller {
    String install(
        in ParcelFileDescriptor[] files,
        in String[] names,
        in long[] sizes,
        String sourcePackage,
        int flags
    );

    void destroy();
}
