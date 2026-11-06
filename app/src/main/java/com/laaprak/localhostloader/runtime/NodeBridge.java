package com.laaprak.localhostloader.runtime;

/** JNI bridge to the Node.js for Mobile Apps libnode runtime. */
public final class NodeBridge {
    private static final boolean AVAILABLE;
    private static final String LOAD_ERROR;
    static {
        boolean ok = false;
        String error = "";
        try {
            System.loadLibrary("node");
            System.loadLibrary("nodebridge");
            ok = true;
        } catch (Throwable t) {
            error = t.toString();
        }
        AVAILABLE = ok;
        LOAD_ERROR = error;
    }
    private NodeBridge() { }
    public static boolean isAvailable() { return AVAILABLE; }
    public static String loadError() { return LOAD_ERROR; }
    public static native int startNode(String[] args, String workingDirectory, String[] environment);
}
