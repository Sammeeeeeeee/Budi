package io.github.sammeeeeeeee.budi;

import io.github.sammeeeeeeee.budi.IStatusIcon;

interface IFollower {
    void destroy() = 16777114; // called by Shizuku when the service is removed

    void follow(in String[] plugins, String receiver) = 1;

    int mode() = 2;

    String trace() = 3;

    void watch(IStatusIcon icon) = 4;
}
