#include "android_host.h"

#include <SDL.h>
#include <cstdio>
#include <filesystem>
#include <jni.h>
#include <mutex>
#include <stdexcept>

void InitializeAndroidGameHost() {
    SDL_SetHint(SDL_HINT_ORIENTATIONS, "LandscapeLeft LandscapeRight");
    const char* root = SDL_AndroidGetExternalStoragePath();
    if (!root || !*root) {
        throw std::runtime_error("Android application data directory is unavailable");
    }
    std::filesystem::current_path(root);
    std::filesystem::create_directories("logs");
    // Android does not preserve a console stream; retain diagnostics beside user data.
    if (!std::freopen("logs/native-stdout.log", "w", stdout) ||
        !std::freopen("logs/native-stderr.log", "w", stderr)) {
        throw std::runtime_error("Cannot open Android runtime logs");
    }
    std::setvbuf(stdout, nullptr, _IOLBF, 0);
    std::setvbuf(stderr, nullptr, _IONBF, 0);
    SDL_Log("TriAevum game data: %s", root);
}

namespace {
std::mutex touchMutex;
AndroidTouchInput touchInput;
std::uint32_t pendingTouchButtons = 0;
}

extern "C" JNIEXPORT void JNICALL
Java_org_triaevum_android_Mm3dTouchControls_nativeSetInput(
    JNIEnv*, jclass, jint buttons, jfloat leftX, jfloat leftY,
    jfloat rightX, jfloat rightY, jboolean reset) {
    const std::lock_guard lock(touchMutex);
    const auto held = static_cast<std::uint32_t>(buttons);
    pendingTouchButtons = reset ? 0U : pendingTouchButtons | (held & ~touchInput.Buttons);
    touchInput = {held, leftX, leftY, rightX, rightY};
}

AndroidTouchInput SampleAndroidTouchInput(bool guestRefresh, bool settingsOpen) {
    const std::lock_guard lock(touchMutex);
    AndroidTouchInput sample = settingsOpen ? AndroidTouchInput{} : touchInput;
    // The settings button is an edge at host cadence. Game inputs retain a
    // short press until a guest refresh observes it.
    sample.Buttons &= ~AndroidTouchMenu;
    sample.Buttons |= pendingTouchButtons & AndroidTouchMenu;
    pendingTouchButtons &= ~AndroidTouchMenu;
    if (guestRefresh && !settingsOpen) sample.Buttons |= pendingTouchButtons;
    if (guestRefresh || settingsOpen) pendingTouchButtons = 0;
    return sample;
}

void UpdateAndroidTouchControls(bool enabled, bool settingsOpen, bool titleScreen) {
    auto* env = static_cast<JNIEnv*>(SDL_AndroidGetJNIEnv());
    if (env == nullptr) return;
    auto activity = static_cast<jobject>(SDL_AndroidGetActivity());
    if (activity == nullptr) return;
    auto type = env->GetObjectClass(activity);
    const auto update = env->GetMethodID(type, "updateMm3dTouchControls", "(ZZZ)V");
    if (update != nullptr) {
        env->CallVoidMethod(activity, update, static_cast<jboolean>(enabled),
                            static_cast<jboolean>(settingsOpen), static_cast<jboolean>(titleScreen));
    }
    env->DeleteLocalRef(type);
    env->DeleteLocalRef(activity);
}
