package org.gbamax.emu;

import android.graphics.Bitmap;

public class Native {
    static { System.loadLibrary("gbamax"); }
    static native boolean init(String core, String dir);
    static native boolean load(byte[] rom, String path);
    static native void unload();
    static native void run();
    static native void draw(Bitmap b);
    static native int audio(short[] out);
    static native void input(int mask);
    static native void setSkip(boolean skip);
    static native double rate();
    static native boolean saveSram(String path);
    static native void loadSram(String path);
    static native boolean saveState(String path);
    static native boolean loadState(String path);
    static native void reset();
    static native void cheatReset();
    static native void cheatSet(int index, boolean on, String code);
}
