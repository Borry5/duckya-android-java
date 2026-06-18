package com.duckya.yaya.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.net.Uri;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

/**
 * 这个文件是可缩放图片控件，负责在预览页支持双指放大缩小和单指拖动图片。
 * 输入是图片内容、用户的触摸手势，以及外部传入的缩放同步状态。
 * 处理过程是维护图片 Matrix、缩放比例和中心点位置，并在布局变化时自动重置矩阵。
 * 输出是可交互的图片预览效果，以及可供双图同步的缩放状态对象。
 */
public class ZoomImageView extends AppCompatImageView {
    private static final float MIN_SCALE = 1.0f;
    private static final float MAX_SCALE = 5.0f;

    private final Matrix imageMatrixValue = new Matrix();
    private final PointF lastPoint = new PointF();
    private ScaleGestureDetector scaleDetector;
    private float currentScale = MIN_SCALE;
    private boolean dragging;
    private boolean resetWhenLaidOut;
    @Nullable
    private OnZoomStateChangeListener zoomStateChangeListener;

    /**
     * 这个构造函数用于在代码中创建可缩放图片控件。
     * 输入是当前 Context。
     * 输出是一个完成基础初始化的 ZoomImageView。
     */
    public ZoomImageView(Context context) {
        super(context);
        init(context);
    }

    /**
     * 这个构造函数用于从 XML 创建可缩放图片控件。
     * 输入是 Context 和属性集。
     * 输出是一个完成基础初始化的 ZoomImageView。
     */
    public ZoomImageView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    /**
     * 这个构造函数用于从 XML 和样式参数创建可缩放图片控件。
     * 输入是 Context、属性集和默认样式 id。
     * 输出是一个完成基础初始化的 ZoomImageView。
     */
    public ZoomImageView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    /**
     * 这个函数用于初始化缩放控件的基础配置。
     * 输入是当前 Context。
     * 输出是启用 Matrix 模式、手势检测器和初始缩放状态。
     */
    private void init(Context context) {
        setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
        resetZoom();
    }

    /**
     * 这个函数用于把图片缩放状态重置回初始适配显示。
     * 输入是无。
     * 输出是重新计算后的 fit-center 显示效果。
     */
    public void resetZoom() {
        if (!hasDrawableAndSize()) {
            resetWhenLaidOut = true;
            return;
        }
        currentScale = MIN_SCALE;
        imageMatrixValue.reset();
        applyFitCenterMatrix();
        setImageMatrix(imageMatrixValue);
        resetWhenLaidOut = false;
    }

    /**
     * 这个函数用于设置缩放状态变化监听器。
     * 输入是监听器对象。
     * 输出是后续缩放变化时可通知外部。
     */
    public void setOnZoomStateChangeListener(@Nullable OnZoomStateChangeListener listener) {
        zoomStateChangeListener = listener;
    }

    /**
     * 这个函数用于把外部传入的缩放状态应用到当前图片。
     * 输入是缩放状态对象。
     * 输出是同步后的缩放比例和图片中心点位置。
     */
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
    /**
     * 这个函数用于设置图片 Uri 后重置缩放矩阵。
     * 输入是图片 Uri。
     * 输出是新的图片内容和重置后的显示状态。
     */
    public void setImageURI(@Nullable Uri uri) {
        super.setImageURI(uri);
        resetZoom();
        // URI 解码和布局时机可能晚于 setImageURI，下一帧再兜底校准一次矩阵。
        post(this::resetZoom);
    }

    @Override
    /**
     * 这个函数用于设置 Bitmap 后重置缩放矩阵。
     * 输入是新的 Bitmap。
     * 输出是新的图片内容和重置后的显示状态。
     */
    public void setImageBitmap(Bitmap bm) {
        super.setImageBitmap(bm);
        resetZoom();
        // 后台采样解码回到主线程后，等待 Drawable 完成绑定再校准矩阵。
        post(this::resetZoom);
    }

    @Override
    /**
     * 这个函数用于在控件尺寸变化时重新计算缩放矩阵。
     * 输入是新旧宽高。
     * 输出是适配新尺寸的图片显示效果。
     */
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        resetZoom();
    }

    @Override
    /**
     * 这个函数用于在布局完成后按需重新应用初始缩放。
     * 输入是布局变化标记和四个边界坐标。
     * 输出是布局后的正确图片矩阵。
     */
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (changed || resetWhenLaidOut) {
            resetZoom();
        }
    }

    /**
     * 这个函数用于判断当前是否已经具备图片和控件尺寸信息。
     * 输入是无。
     * 输出是是否可以安全计算缩放矩阵的布尔值。
     */
    private boolean hasDrawableAndSize() {
        Drawable drawable = getDrawable();
        return drawable != null
                && drawable.getIntrinsicWidth() > 0
                && drawable.getIntrinsicHeight() > 0
                && getWidth() > 0
                && getHeight() > 0;
    }

    /**
     * 这个函数用于把图片按 fit-center 方式放进当前控件。
     * 输入是当前 Drawable 和控件尺寸。
     * 输出是更新后的初始 Matrix 变换。
     */
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
    /**
     * 这个函数用于处理图片上的缩放和拖动触摸事件。
     * 输入是用户触摸事件对象。
     * 输出是更新后的图片矩阵，并消费该事件。
     */
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

    /**
     * 这个函数用于处理单指拖动图片的位置变化。
     * 输入是触摸事件对象。
     * 输出是拖动后的图片平移效果。
     */
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

    /**
     * 这个函数用于在缩放状态变化时通知外部监听器。
     * 输入是无。
     * 输出是当前最新的 ZoomState。
     */
    private void notifyZoomStateChanged() {
        if (zoomStateChangeListener != null) {
            zoomStateChangeListener.onZoomStateChanged(this, buildZoomState());
        }
    }

    /**
     * 这个函数用于根据当前矩阵构建可同步的缩放状态对象。
     * 输入是当前图片矩阵、图片尺寸和控件尺寸。
     * 输出是包含缩放比例和归一化中心点的 ZoomState。
     */
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

    /**
     * 这个函数用于把数值限制在指定区间内。
     * 输入是原值、最小值和最大值。
     * 输出是被限制后的结果。
     */
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
