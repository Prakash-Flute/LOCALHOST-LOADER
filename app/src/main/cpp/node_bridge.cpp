#include <jni.h>
#include <node.h>
#include <unistd.h>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
#include <cstring>

static std::string toUtf8(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result = chars == nullptr ? std::string() : std::string(chars);
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_laaprak_localhostloader_runtime_NodeBridge_startNode(
        JNIEnv* env, jclass, jobjectArray javaArgs, jstring javaCwd, jobjectArray javaEnv) {
    const std::string cwd = toUtf8(env, javaCwd);
    if (!cwd.empty() && chdir(cwd.c_str()) != 0) return 126;

    if (javaEnv != nullptr) {
        const jsize envCount = env->GetArrayLength(javaEnv);
        for (jsize i = 0; i < envCount; ++i) {
            auto item = static_cast<jstring>(env->GetObjectArrayElement(javaEnv, i));
            std::string entry = toUtf8(env, item);
            if (item != nullptr) env->DeleteLocalRef(item);
            const size_t equals = entry.find('=');
            if (equals != std::string::npos && equals > 0) {
                setenv(entry.substr(0, equals).c_str(), entry.substr(equals + 1).c_str(), 1);
            }
        }
    }

    if (javaArgs == nullptr) return 2;
    const jsize count = env->GetArrayLength(javaArgs);
    if (count < 2) return 2;

    std::vector<std::string> values;
    values.reserve(static_cast<size_t>(count));
    size_t bufferSize = 0;
    for (jsize i = 0; i < count; ++i) {
        auto item = static_cast<jstring>(env->GetObjectArrayElement(javaArgs, i));
        values.push_back(toUtf8(env, item));
        if (item != nullptr) env->DeleteLocalRef(item);
        bufferSize += values.back().size() + 1;
    }

    // libuv expects argv strings to live in one contiguous writable allocation.
    std::vector<char> argumentBuffer(bufferSize == 0 ? 1 : bufferSize);
    std::vector<char*> argv;
    argv.reserve(static_cast<size_t>(count));
    size_t offset = 0;
    for (const auto& value : values) {
        char* current = argumentBuffer.data() + offset;
        std::memcpy(current, value.c_str(), value.size() + 1);
        argv.push_back(current);
        offset += value.size() + 1;
    }
    return node::Start(static_cast<int>(argv.size()), argv.data());
}
