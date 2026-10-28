package com.germanium.shortcuts;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

interface IRemoteShell {
    void start(String token, String command, long timeoutMillis) = 0;
    Bundle poll(String token) = 1;
    void cancel(String token) = 2;
    void install(String token, in ParcelFileDescriptor apk, long size, long timeoutMillis) = 3;
    void destroy() = 16777114;
}
