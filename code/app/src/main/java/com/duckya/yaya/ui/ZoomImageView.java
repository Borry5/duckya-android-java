package com.duckya.yaya.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

// 支持双指缩放和单指拖动，用于对比压缩前后的图片细节。
public class ZoomImageView extends AppCompatImageView {
    private static final float MIN_SCALE = 1.0f;
    private static final float MAX_SCALE = 5.0f;

    private final Matrix imageMatrixValue = new Matrix();
    private final PointF lastPoint = new PointF();
    private ScaleGestureDetector scaleDetector;
    private float currentScale = MIN_SCALE;
    private boolean dragging;
    @Nullable
    private OnZoomStateChangeListener zoomStateChangeListener;

    public ZoomImageView(Context context) {
        super(context);
        init(context);
    }

    public ZoomImageView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public ZoomImageView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
        resetZoom();
    }

    public void resetZoom() {
        currentScale = MIN_SCALE;
        imageMatrixValue.reset();
        applyFitCenterMatrix();
        setImageMatrix(imageMatrixValue);
    }

    public void setOnZoomStateChangeListener(@Nullable OnZoomStateChangeListener listener) {
        zoomStateChangeListener = listener;
    }

    public void applyZoomState(@Nullable ZoomState state) {
        Drawable drawable = getDrawable();
        if (state == null || drawable == null || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        int drawableWidth = drawable.getIntrinsicWidth();
        int drawableHeight = drawable.getIntrinsicHeight();
        if (drawableWidth <= 0 || drawableHeight <= 0) {
            return;
        }

        float targetScale = clamp(state.scale, MIN_SCALE, MAX_SCALE);
        float normalizedCenterX = clamp(state.normalizedCenterX, 0f, 1f);
        float normalizedCenterY = clamp(state.normalizedCenterY, 0f, 1f);
        float viewCenterX = getWidth() * 0.5f;
        float viewCenterY = getHeight() * 0.5f;

        imageMatrixValue.reset();
        applyFitCenterMatrix();
        imageMatrixValue.postScale(targetScale, targetScale, viewCenterX, viewCenterY);

        // 用归一化图片中心同步，避免原图和压缩图分辨率不同导致位置错位。
        float[] mappedPoint = new float[] {
                drawableWidth * normalizedCenterX,
                drawableHeight * normalizedCenterY
        };
        imageMatrixValue.mapPoints(mappedPoint);
        imageMatrixValue.postTranslate(viewCenterX - mappedPoint[0], viewCenterY - mappedPoint[1]);
        currentScale = targetScale;
        setImageMatrix(imageMatrixValue);
    }

    @Override
    public void setImageURI(@Nullable android.net.Uri uri) {
        super.setImageURI(uri);
        resetZoom();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        resetZoom();
    }

    private void applyFitCenterMatrix() {
        Drawable drawable = getDrawable();
        if (drawable == null || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        int drawableWidth = drawable.getIntrinsicWidth();
        int drawableHeight = drawable.getIntrinsicHeight();
        if (drawableWidth <= 0 || drawableHeight <= 0) {
            return;
        }
        float scale = Math.min(getWidth() / (float) drawableWidth, getHeight() / (float) drawableHeight);
        float dx = (getWidth() - drawableWidth * scale) * 0.5f;
        float dy = (getHeight() - drawableHeight * scale) * 0.5f;
        imageMatrixValue.postScale(scale, scale);
        imageMatrixValue.postTranslate(dx, dy);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (getDrawable() == null) {
            return super.onTouchEvent(event);
        }
        getParent().requestDisallowInterceptTouchEvent(true);
        scaleDetector.onTouchEvent(event);
        if (!scaleDetector.isInProgress()) {
            handleDrag(event);
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            dragging = false;
            getParent().requestDisallowInterceptTouchEvent(false);
        }
        return true;
    }

    private void handleDrag(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastPoint.set(event.getX(), event.getY());
                dragging = true;
                break;
            case MotionEvent.ACTION_MOVE:
                if (dragging && currentScale > MIN_SCALE) {
                    float dx = event.getX() - lastPoint.x;
                    float dy = event.getY() - lastPoint.y;
                    imageMatrixValue.postTranslate(dx, dy);
                    setImageMatrix(imageMatrixValue);
                    notifyZoomStateChanged();
                    lastPoint.set(event.getX(), event.getY());
                }
                break;
            default:
                break;
        }
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float factor = detector.getScaleFactor();
            float targetScale = Math.max(MIN_SCALE, Math.min(currentScale * factor, MAX_SCALE));
            float safeFactor = targetScale / currentScale;
            imageMatrixValue.postScale(safeFactor, safeFactor, detector.getFocusX(), detector.getFocusY());
            currentScale = targetScale;
            setImageMatrix(imageMatrixValue);
            notifyZoomStateChanged();
            return true;
        }
    }

    private void notifyZoomStateChanged() {
        if (zoomStateChangeListener != null) {
            zoomStateChangeListener.onZoomStateChanged(this, buildZoomState());
        }
    }

    private ZoomState buildZoomState() {
        Drawable drawable = getDrawable();
        if (drawable == null || getWidth() <= 0 || getHeight() <= 0) {
            return new ZoomState(currentScale, 0.5f, 0.5f);
        }
        int drawableWidth = drawable.getIntrinsicWidth();
        int drawableHeight = drawable.getIntrinsicHeight();
        if (drawableWidth <= 0 || drawableHeight <= 0) {
            return new ZoomState(currentScale, 0.5f, 0.5f);
        }
        Matrix inverse = new Matrix();
        if (!imageMatrixValue.invert(inverse)) {
            return new ZoomState(currentScale, 0.5f, 0.5f);
        }
        float[] centerPoint = new float[] {getWidth() * 0.5f, getHeight() * 0.5f};
        inverse.mapPoints(centerPoint);
        float normalizedCenterX = centerPoint[0] / drawableWidth;
        float normalizedCenterY = centerPoint[1] / drawableHeight;
        return new ZoomState(
                currentScale,
                clamp(normalizedCenterX, 0f, 1f),
                clamp(normalizedCenterY, 0f, 1f)
        );
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(value, max));
    }

    public interface OnZoomStateChangeListener {
        void onZoomStateChanged(ZoomImageView source, ZoomState state);
    }

    public static class ZoomState {
        private final float scale;
        private final float normalizedCenterX;
        private final float normalizedCenterY;

        private ZoomState(float scale, float normalizedCenterX, float normalizedCenterY) {
            this.scale = scale;
            this.normalizedCenterX = normalizedCenterX;
            this.normalizedCenterY = normalizedCenterY;
        }
    }
}
