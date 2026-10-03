#include <jni.h>
#include <stdlib.h>
#include <stdio.h>
#include <time.h>
#include <locale.h>
#include <atomic>

#include <mpv/client.h>

#include <pthread.h>

extern "C" {
    #include <libavcodec/jni.h>
}

#include "log.h"
#include "jni_utils.h"
#include "event.h"

extern "C" {
    jni_func(void, create, jobject appctx);
    jni_func(void, init);
    jni_func(void, destroy);

    jni_func(void, command, jobjectArray jarray);
};

JavaVM *g_vm;
mpv_handle *g_mpv;
std::atomic<bool> g_event_thread_request_exit(false);

static pthread_t event_thread_id;
static jobject global_appctx;

static void throwJavaException(JNIEnv *env, const char *type, const char *message) {
    if (env->ExceptionCheck()) return;
    jclass exception = env->FindClass(type);
    if (!exception) return;
    env->ThrowNew(exception, message);
    env->DeleteLocalRef(exception);
}

// Keep local references and UTF buffers paired even when acquisition fails
// midway through a command. No heap allocation is needed for the argument list.
struct CommandArguments {
    JNIEnv *env;
    jstring strings[64] = {};
    const char *values[64] = {};

    explicit CommandArguments(JNIEnv *environment) : env(environment) {}
    ~CommandArguments() {
        for (int i = 0; i < 64; ++i) {
            if (values[i]) env->ReleaseStringUTFChars(strings[i], values[i]);
            if (strings[i]) env->DeleteLocalRef(strings[i]);
        }
    }
};

static void prepare_environment(JNIEnv *env, jobject appctx) {
    setlocale(LC_NUMERIC, "C");

    g_vm = NULL;
    env->GetJavaVM(&g_vm);
    if (!g_vm)
        die("failed to get jvm");
    av_jni_set_java_vm(g_vm, NULL);

    if (global_appctx)
        env->DeleteGlobalRef(global_appctx);
    global_appctx = env->NewGlobalRef(appctx);
    if (global_appctx)
        av_jni_set_android_app_ctx(global_appctx, NULL);

    init_methods_cache(env);
}

jni_func(void, create, jobject appctx) {
    if (g_mpv)
        die("mpv is already initialized");

    prepare_environment(env, appctx);

    g_mpv = mpv_create();
    if (!g_mpv)
        die("context init failed");

    // use terminal log level but request verbose messages
    // this way --msg-level can be used to adjust later
    mpv_request_log_messages(g_mpv, "terminal-default");
    mpv_set_option_string(g_mpv, "msg-level", "all=v");
}

jni_func(void, init) {
    if (!g_mpv)
        die("mpv is not created");

    if (mpv_initialize(g_mpv) < 0)
        die("mpv init failed");

    g_event_thread_request_exit = false;
    if (pthread_create(&event_thread_id, NULL, event_thread, NULL) != 0)
        die("thread create failed");
    pthread_setname_np(event_thread_id, "event_thread");
}

jni_func(void, destroy) {
    if (!g_mpv) {
        ALOGV("mpv destroy called but it's already destroyed");
        return;
    }

    // poke event thread and wait for it to exit
    g_event_thread_request_exit = true;
    mpv_wakeup(g_mpv);
    pthread_join(event_thread_id, NULL);

    mpv_terminate_destroy(g_mpv);
    g_mpv = NULL;
}

jni_func(void, command, jobjectArray jarray) {
    if (!jarray) {
        throwJavaException(env, "java/lang/IllegalArgumentException", "command array must not be null");
        return;
    }
    const jsize len = env->GetArrayLength(jarray);
    if (env->ExceptionCheck()) return;
    if (len < 1 || len > 63) {
        throwJavaException(env, "java/lang/IllegalArgumentException", "command requires 1 to 63 arguments");
        return;
    }

    CommandArguments arguments(env);
    for (jsize i = 0; i < len; ++i) {
        arguments.strings[i] = static_cast<jstring>(env->GetObjectArrayElement(jarray, i));
        if (env->ExceptionCheck()) return;
        if (!arguments.strings[i]) {
            throwJavaException(env, "java/lang/IllegalArgumentException", "command arguments must not be null");
            return;
        }
        arguments.values[i] = env->GetStringUTFChars(arguments.strings[i], nullptr);
        if (!arguments.values[i]) return; // Preserve the JVM allocation exception.
    }

    if (!g_mpv) {
        throwJavaException(env, "java/lang/IllegalStateException", "libmpv is not initialized");
        return;
    }
    const int result = mpv_command(g_mpv, arguments.values);
    if (result < 0)
        throwJavaException(env, "java/lang/IllegalStateException", mpv_error_string(result));
}
