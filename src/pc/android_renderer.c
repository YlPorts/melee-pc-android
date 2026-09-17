/* SPDX-License-Identifier: GPL-3.0-or-later */

#if defined(__ANDROID__)
#include <stdlib.h>

/*
 * Dawn's Vulkan path performs poorly on a number of mid-range Android GPUs.
 * Aurora also ships an OpenGL ES backend on Android, so prefer it here unless
 * the caller explicitly supplied MELEE_BACKEND. This keeps the choice easy to
 * override for diagnostics while avoiding any change on desktop platforms.
 */
__attribute__((constructor)) static void pc_android_select_renderer(void) {
    if (getenv("MELEE_BACKEND") == NULL) {
        setenv("MELEE_BACKEND", "gles", 0);
    }
}
#endif
