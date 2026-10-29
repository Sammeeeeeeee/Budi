package io.github.sammeeeeeeee.budi;

interface IShell {
    void destroy() = 16777114; // called by Shizuku when the service is removed

    void run(String script, in ParcelFileDescriptor output) = 1;
}
