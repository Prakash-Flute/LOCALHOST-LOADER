#include <jni.h>
extern "C" JNIEXPORT jint JNICALL
Java_com_laaprak_localhostloader_runtime_NodeBridge_startNode(JNIEnv*, jclass, jobjectArray, jstring, jobjectArray) {
    return -9001; // Node runtime binary was not included in this build.
}
