// Native PTY bridge for Chimera Terminal.
//
// Provides a real pseudo-terminal backed by forkpty(3) so that interactive
// shells (job control, signals, SIGWINCH, tty detection) behave exactly as
// they do in a desktop terminal. All file descriptors returned here are
// process-local and are never shared between sandboxes.

#define _GNU_SOURCE

#include <jni.h>
#include <pty.h>
#include <termios.h>
#include <unistd.h>
#include <fcntl.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <sys/types.h>
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#define LOG_TAG "ChimeraPty"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

char** toCStringArray(JNIEnv* env, jobjectArray array, int* outCount) {
    if (array == nullptr) {
        *outCount = 0;
        return nullptr;
    }
    jsize len = env->GetArrayLength(array);
    auto** result = static_cast<char**>(calloc(len + 1, sizeof(char*)));
    if (result == nullptr) {
        *outCount = 0;
        return nullptr;
    }
    for (jsize i = 0; i < len; ++i) {
        auto jstr = static_cast<jstring>(env->GetObjectArrayElement(array, i));
        if (jstr == nullptr) {
            result[i] = strdup("");
            continue;
        }
        const char* utf = env->GetStringUTFChars(jstr, nullptr);
        result[i] = strdup(utf != nullptr ? utf : "");
        if (utf != nullptr) env->ReleaseStringUTFChars(jstr, utf);
        env->DeleteLocalRef(jstr);
    }
    *outCount = len;
    return result;
}

void freeCStringArray(char** array, int count) {
    if (array == nullptr) return;
    for (int i = 0; i < count; ++i) free(array[i]);
    free(array);
}

int setCloexec(int fd) {
    int flags = fcntl(fd, F_GETFD);
    if (flags < 0) return -1;
    return fcntl(fd, F_SETFD, flags | FD_CLOEXEC);
}

}  // namespace

extern "C" JNIEXPORT jintArray JNICALL
Java_com_chimeraant_terminal_core_PtyProcess_nativeCreateSubprocess(
        JNIEnv* env, jclass /*clazz*/, jobjectArray argvArray,
        jobjectArray envArray, jstring cwdString, jint rows, jint cols) {
    int argc = 0;
    int envc = 0;
    char** argv = toCStringArray(env, argvArray, &argc);
    char** envp = toCStringArray(env, envArray, &envc);

    const char* cwd = nullptr;
    if (cwdString != nullptr) {
        cwd = env->GetStringUTFChars(cwdString, nullptr);
    }

    struct winsize ws{};
    ws.ws_row = static_cast<unsigned short>(rows > 0 ? rows : 24);
    ws.ws_col = static_cast<unsigned short>(cols > 0 ? cols : 80);
    ws.ws_xpixel = 0;
    ws.ws_ypixel = 0;

    int master = -1;
    pid_t pid = forkpty(&master, nullptr, nullptr, &ws);

    jintArray result = env->NewIntArray(2);
    if (result == nullptr) {
        freeCStringArray(argv, argc);
        freeCStringArray(envp, envc);
        if (cwd != nullptr) env->ReleaseStringUTFChars(cwdString, cwd);
        return nullptr;
    }

    if (pid < 0) {
        LOGE("forkpty failed: %s", strerror(errno));
        jint failure[2] = {-1, -1};
        env->SetIntArrayRegion(result, 0, 2, failure);
        freeCStringArray(argv, argc);
        freeCStringArray(envp, envc);
        if (cwd != nullptr) env->ReleaseStringUTFChars(cwdString, cwd);
        return result;
    }

    if (pid == 0) {
        // Child: forkpty already created the session and controlling tty.
        if (cwd != nullptr && cwd[0] != '\0') {
            if (chdir(cwd) != 0) {
                // Fall back to a directory that always exists.
                chdir("/");
            }
        }
        if (argc > 0 && argv[0] != nullptr) {
            if (envp != nullptr && envc > 0) {
                execve(argv[0], argv, envp);
            } else {
                execvp(argv[0], argv);
            }
        }
        // exec failed: report and exit so the parent's read loop ends.
        LOGE("exec failed: %s", strerror(errno));
        _exit(127);
    }

    // Parent.
    setCloexec(master);
    jint payload[2] = {static_cast<jint>(pid), master};
    env->SetIntArrayRegion(result, 0, 2, payload);

    freeCStringArray(argv, argc);
    freeCStringArray(envp, envc);
    if (cwd != nullptr) env->ReleaseStringUTFChars(cwdString, cwd);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_chimeraant_terminal_core_PtyProcess_nativeWaitFor(JNIEnv*, jclass, jint pid) {
    int status = 0;
    pid_t ret;
    do {
        ret = waitpid(static_cast<pid_t>(pid), &status, 0);
    } while (ret < 0 && errno == EINTR);
    if (ret < 0) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_chimeraant_terminal_core_PtyProcess_nativeRead(
        JNIEnv* env, jclass, jint fd, jbyteArray buffer, jint offset, jint length) {
    if (buffer == nullptr || length <= 0) return -1;
    auto* buf = static_cast<jbyte*>(malloc(length));
    if (buf == nullptr) return -1;

    ssize_t n;
    do {
        n = read(fd, buf, static_cast<size_t>(length));
    } while (n < 0 && errno == EINTR);

    if (n > 0) {
        env->SetByteArrayRegion(buffer, offset, static_cast<jsize>(n), buf);
    }
    free(buf);
    if (n < 0) return -errno;
    return static_cast<jint>(n);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_chimeraant_terminal_core_PtyProcess_nativeWrite(
        JNIEnv* env, jclass, jint fd, jbyteArray buffer, jint offset, jint length) {
    if (buffer == nullptr || length <= 0) return 0;
    auto* buf = static_cast<jbyte*>(malloc(length));
    if (buf == nullptr) return -1;
    env->GetByteArrayRegion(buffer, offset, length, buf);

    ssize_t total = 0;
    while (total < length) {
        ssize_t n = write(fd, buf + total, static_cast<size_t>(length - total));
        if (n < 0) {
            if (errno == EINTR) continue;
            free(buf);
            return -errno;
        }
        total += n;
    }
    free(buf);
    return static_cast<jint>(total);
}

extern "C" JNIEXPORT void JNICALL
Java_com_chimeraant_terminal_core_PtyProcess_nativeSetWinSize(
        JNIEnv*, jclass, jint fd, jint rows, jint cols) {
    struct winsize ws{};
    ws.ws_row = static_cast<unsigned short>(rows);
    ws.ws_col = static_cast<unsigned short>(cols);
    ws.ws_xpixel = 0;
    ws.ws_ypixel = 0;
    ioctl(fd, TIOCSWINSZ, &ws);
}

extern "C" JNIEXPORT void JNICALL
Java_com_chimeraant_terminal_core_PtyProcess_nativeClose(JNIEnv*, jclass, jint fd) {
    if (fd >= 0) close(fd);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_chimeraant_terminal_core_PtyProcess_nativeSendSignal(
        JNIEnv*, jclass, jint pid, jint sig) {
    if (pid <= 0) return -1;
    return kill(static_cast<pid_t>(pid), sig);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_chimeraant_terminal_core_PtyProcess_nativeGetForegroundPid(
        JNIEnv*, jclass, jint fd) {
    pid_t pgrp = tcgetpgrp(fd);
    return pgrp < 0 ? -1 : static_cast<jint>(pgrp);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_chimeraant_terminal_core_PtyProcess_nativeIsTerminal(
        JNIEnv*, jclass, jint fd) {
    return isatty(fd) == 1 ? JNI_TRUE : JNI_FALSE;
}
