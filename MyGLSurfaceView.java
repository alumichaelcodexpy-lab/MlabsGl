package com.mycompany.myapp;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

public class MyGLSurfaceView extends GLSurfaceView {

    public interface TapListener {
        void onTopLeftTap();
    }

    private final GLRenderer renderer;
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;  // for double‑tap

    private float lastX;
    private float lastY;
    private float lastMidX;
    private float lastMidY;
    private float lastPinchDistance = -1f;
    private boolean dragging = false;
    private boolean hasTwoFingerMid = false;

    // Tap detection for top‑left corner
    private TapListener tapListener;
    private float tapDownX, tapDownY;
    private long tapDownTime;
    private boolean possibleTap = false;
    private static final int TAP_ZONE = 100;
    private static final int TAP_MOVE_THRESHOLD = 20;
    private static final long TAP_TIME_THRESHOLD = 300;

    public MyGLSurfaceView(Context context) {
        super(context);
        setEGLContextClientVersion(3);

        renderer = new GLRenderer(context);
        setRenderer(renderer);
        setRenderMode(RENDERMODE_CONTINUOUSLY);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
				@Override
				public boolean onScale(ScaleGestureDetector detector) {
					float scaleFactor = detector.getScaleFactor();
					if (scaleFactor > 0f) {
						float zoomDelta = (1f - scaleFactor) * 10f;
						renderer.addZoomDelta(zoomDelta);
					}
					return true;
				}
			});

        // Double‑tap detector
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
				@Override
				public boolean onDoubleTap(MotionEvent e) {
					renderer.togglePerspective();
					return true;
				}
			});
    }

    public GLRenderer getRendererInstance() {
        return renderer;
    }

    public void setTapListener(TapListener listener) {
        this.tapListener = listener;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Pass to gesture detector for double‑tap
        gestureDetector.onTouchEvent(event);
        // Also pass to scale detector for pinch
        scaleDetector.onTouchEvent(event);

        int pointerCount = event.getPointerCount();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (pointerCount == 1) {
                    tapDownX = event.getX();
                    tapDownY = event.getY();
                    tapDownTime = System.currentTimeMillis();
                    possibleTap = (tapDownX <= TAP_ZONE && tapDownY <= TAP_ZONE);
                } else {
                    possibleTap = false;
                }
                dragging = true;
                hasTwoFingerMid = false;
                lastPinchDistance = -1f;
                lastX = event.getX();
                lastY = event.getY();
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
                possibleTap = false;
                if (pointerCount >= 2) {
                    dragging = false;
                    lastMidX = getMidX(event);
                    lastMidY = getMidY(event);
                    hasTwoFingerMid = true;
                    lastPinchDistance = getDistance(event);
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (possibleTap) {
                    float dx = Math.abs(event.getX() - tapDownX);
                    float dy = Math.abs(event.getY() - tapDownY);
                    if (dx > TAP_MOVE_THRESHOLD || dy > TAP_MOVE_THRESHOLD) {
                        possibleTap = false;
                    }
                }
                if (pointerCount >= 2) {
                    float midX = getMidX(event);
                    float midY = getMidY(event);
                    if (hasTwoFingerMid) {
                        float dx = midX - lastMidX;
                        float dy = midY - lastMidY;
                        renderer.panCameraBy(-dx * 0.01f, dy * 0.01f);
                    }
                    float currentDistance = getDistance(event);
                    if (lastPinchDistance > 0f) {
                        float pinchDelta = currentDistance - lastPinchDistance;
                        renderer.addZoomDelta(-pinchDelta * 0.02f);
                    }
                    lastMidX = midX;
                    lastMidY = midY;
                    lastPinchDistance = currentDistance;
                    hasTwoFingerMid = true;
                } else if (dragging) {
                    float x = event.getX();
                    float y = event.getY();
                    float dx = x - lastX;
                    float dy = y - lastY;
                    renderer.addOrbitDelta(dx * 0.35f, dy * 0.35f);
                    lastX = x;
                    lastY = y;
                }
                break;

            case MotionEvent.ACTION_POINTER_UP:
                if (event.getPointerCount() - 1 < 2) {
                    hasTwoFingerMid = false;
                    lastPinchDistance = -1f;
                    dragging = true;
                    int index = event.getActionIndex();
                    if (index == 0 && event.getPointerCount() > 1) {
                        lastX = event.getX(1);
                        lastY = event.getY(1);
                    } else {
                        lastX = event.getX(0);
                        lastY = event.getY(0);
                    }
                }
                break;

            case MotionEvent.ACTION_UP:
                if (possibleTap && tapListener != null) {
                    long elapsed = System.currentTimeMillis() - tapDownTime;
                    if (elapsed <= TAP_TIME_THRESHOLD) {
                        tapListener.onTopLeftTap();
                    }
                }
                possibleTap = false;
                // fall through
            case MotionEvent.ACTION_CANCEL:
                possibleTap = false;
                dragging = false;
                hasTwoFingerMid = false;
                lastPinchDistance = -1f;
                break;
        }
        return true;
    }

    private float getMidX(MotionEvent event) { return (event.getX(0) + event.getX(1)) * 0.5f; }
    private float getMidY(MotionEvent event) { return (event.getY(0) + event.getY(1)) * 0.5f; }
    private float getDistance(MotionEvent event) {
        float dx = event.getX(0) - event.getX(1);
        float dy = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
