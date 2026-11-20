#pragma once

void InitializeAndroidGameHost();

#include <cstdint>

struct AndroidTouchInput {
    std::uint32_t Buttons = 0;
    float LeftX = 0, LeftY = 0;
    float RightX = 0, RightY = 0;
};

inline constexpr auto AndroidTouchItems = 1U << 16U;
inline constexpr auto AndroidTouchMap = 1U << 17U;
inline constexpr auto AndroidTouchNotebook = 1U << 18U;
inline constexpr auto AndroidTouchMenu = 1U << 19U;
inline constexpr auto AndroidTouchSkipIntro = 1U << 20U;

AndroidTouchInput SampleAndroidTouchInput(bool guestRefresh, bool settingsOpen);
void UpdateAndroidTouchControls(bool enabled, bool settingsOpen, bool titleScreen);
