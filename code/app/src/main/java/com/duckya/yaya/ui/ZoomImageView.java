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
            return true;
        }
    }
}
