package dev.encounter.aurora;

import android.content.Context;
import android.view.SurfaceHolder;

import org.libsdl.app.SDLSurface;

public class AuroraSurface extends SDLSurface {
    private static final int PERFORMANCE_SHORT_EDGE = 540;

    private static native void nativeSetSurfaceReady(boolean ready);

    private boolean mPerformanceBufferRequested = false;

    public AuroraSurface(Context context) {
        super(context);
        getHolder().setKeepScreenOn(true);
    }

    private boolean requestPerformanceBuffer(SurfaceHolder holder, int width, int height) {
        if (mPerformanceBufferRequested || width <= 0 || height <= 0) {
            return false;
        }

        final int shortEdge = Math.min(width, height);
        if (shortEdge <= PERFORMANCE_SHORT_EDGE) {
            return false;
        }

        final float scale = (float) PERFORMANCE_SHORT_EDGE / (float) shortEdge;
        int targetWidth = Math.max(2, Math.round(width * scale));
        int targetHeight = Math.max(2, Math.round(height * scale));

        // Keep dimensions even for Vulkan swapchain / texture compatibility.
        targetWidth &= ~1;
        targetHeight &= ~1;

        if (targetWidth == width && targetHeight == height) {
            return false;
        }

        mPerformanceBufferRequested = true;
        holder.setFixedSize(targetWidth, targetHeight);
        return true;
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        nativeSetSurfaceReady(false);
        super.surfaceCreated(holder);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        nativeSetSurfaceReady(false);
        mPerformanceBufferRequested = false;
        super.surfaceDestroyed(holder);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        nativeSetSurfaceReady(false);

        // The game itself is already rendered close to GameCube resolution.
        // Presenting that image through a full 1080p+ Android swapchain wastes
        // fill-rate and memory bandwidth on mobile GPUs. Ask SurfaceFlinger for
        // an aspect-correct ~540p buffer and let the hardware compositor scale
        // it to the full-screen SurfaceView. The Android touch overlay remains
        // full-resolution because it is a separate View above this surface.
        if (requestPerformanceBuffer(holder, width, height)) {
            return;
        }

        super.surfaceChanged(holder, format, width, height);
        nativeSetSurfaceReady(mIsSurfaceReady);
    }
}
