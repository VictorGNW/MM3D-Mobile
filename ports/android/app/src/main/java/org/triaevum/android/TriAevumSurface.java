package org.triaevum.android;

import android.content.Context;
import android.graphics.Region;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.ViewGroup;
import org.libsdl.app.SDLSurface;

/** SDL retains lifecycle/input ownership; only the Android buffer extent is constrained. */
final class TriAevumSurface extends SDLSurface {
    private final int maximumShortEdge;
    private boolean touchOverlayVisible;

    TriAevumSurface(Context context, int maximumShortEdge) {
        super(context);
        this.maximumShortEdge = maximumShortEdge;
        // A fixed buffer must not become the view's desired layout size on rotation.
        setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    void setTouchOverlayVisible(boolean visible) {
        if (touchOverlayVisible != visible) {
            touchOverlayVisible = visible;
            requestLayout();
        }
    }

    @Override public boolean gatherTransparentRegion(Region region) {
        if (!touchOverlayVisible) return super.gatherTransparentRegion(region);
        // The game surface fills the window, but the Android window still
        // contributes touch controls above it. Keep that layer composited.
        if (region != null) region.setEmpty();
        return false;
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        int[] extent = SurfaceExtentPolicy.fit(width, height, maximumShortEdge);
        getHolder().setFixedSize(extent[0], extent[1]);
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        int viewWidth = getWidth() > 0 ? getWidth() : width;
        int viewHeight = getHeight() > 0 ? getHeight() : height;
        int[] extent = SurfaceExtentPolicy.fit(viewWidth, viewHeight, maximumShortEdge);
        if (width != extent[0] || height != extent[1]) {
            holder.setFixedSize(extent[0], extent[1]);
            return; // Do not start Vulkan against the transient full-resolution buffer.
        }
        Log.i("TriAevum", "Render surface " + width + "x" + height
            + ", view " + viewWidth + "x" + viewHeight);
        super.surfaceChanged(holder, format, width, height);
        // SDL normalizes touch positions with these fields. Touch uses view pixels,
        // not the independently sized SurfaceHolder buffer sent to Vulkan above.
        mWidth = viewWidth;
        mHeight = viewHeight;
    }
}
