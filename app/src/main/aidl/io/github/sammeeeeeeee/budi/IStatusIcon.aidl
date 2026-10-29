package io.github.sammeeeeeeee.budi;

/** Budi's icon in the status bar, as the follower drives it. */
oneway interface IStatusIcon {
    /** The mode, and whether the app on screen asked for dark status bar icons. */
    void show(int mode, boolean dark);
}
