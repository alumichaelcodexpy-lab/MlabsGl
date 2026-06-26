package com.mycompany.myapp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.view.MotionEvent;
import android.view.View;

public class JoystickView extends View {

    public interface JoystickListener {
        void onJoystick(float dx, float dy);   // normalised to -1 … 1
    }

    private JoystickListener listener;
    private Paint ringPaint, thumbPaint;
    private float outerRadius;      // radius of the ring
    private float thumbRadius;      // radius of the thumb
    private PointF center = new PointF();
    private PointF thumbPos = new PointF();
    private float maxDist;          // thumb centre can travel up to outerRadius

    public JoystickView(Context context) {
        super(context);
        init();
    }

    private void init() {
        setBackgroundColor(Color.TRANSPARENT);   // no black box

        ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setColor(Color.argb(128, 255, 255, 255));   // white 50% opacity
        ringPaint.setStrokeWidth(3f);

        thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        thumbPaint.setStyle(Paint.Style.FILL);
        thumbPaint.setColor(Color.argb(200, 255, 255, 255));
    }

    public void setJoystickListener(JoystickListener l) {
        this.listener = l;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        center.x = w / 2f;
        center.y = h / 2f;
        thumbPos.set(center.x, center.y);
        // Ring fits within the view so the thumb never clips
        outerRadius = Math.min(w, h) / 3f;
        thumbRadius = outerRadius / 2f;
        maxDist = outerRadius;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawCircle(center.x, center.y, outerRadius, ringPaint);
        canvas.drawCircle(thumbPos.x, thumbPos.y, thumbRadius, thumbPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;

        float x = event.getX();
        float y = event.getY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                float dx = x - center.x;
                float dy = y - center.y;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                if (dist > maxDist) {
                    float scale = maxDist / dist;
                    dx *= scale;
                    dy *= scale;
                }
                thumbPos.set(center.x + dx, center.y + dy);
                float nx = (maxDist > 0) ? (dx / maxDist) : 0f;
                float ny = (maxDist > 0) ? (dy / maxDist) : 0f;
                if (listener != null) {
                    listener.onJoystick(nx, ny);
                }
                invalidate();
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                thumbPos.set(center.x, center.y);
                if (listener != null) {
                    listener.onJoystick(0f, 0f);
                }
                invalidate();
                return true;
        }
        return super.onTouchEvent(event);
    }
}
