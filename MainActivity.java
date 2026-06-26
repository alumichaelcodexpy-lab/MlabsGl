package com.mycompany.myapp;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

public class MainActivity extends Activity {

    private MyGLSurfaceView glView;
    private TextView debugText;
    private boolean debugVisible = true;
    private JoystickView joystick;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.BLACK);
            getWindow().setNavigationBarColor(Color.BLACK);
        }

        FrameLayout root = new FrameLayout(this);

        glView = new MyGLSurfaceView(this);
        root.addView(glView);

        debugText = new TextView(this);
        debugText.setTextColor(Color.WHITE);
        debugText.setBackgroundColor(0x88000000);
        debugText.setTextSize(12f);
        debugText.setPadding(20, 20, 20, 20);
        debugText.setGravity(Gravity.TOP | Gravity.START);
        debugText.setVisibility(View.VISIBLE);
        FrameLayout.LayoutParams textParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        textParams.gravity = Gravity.TOP;
        root.addView(debugText, textParams);

        // ----- Joystick – smaller size, no clipping -----
        joystick = new JoystickView(this);
        joystick.setJoystickListener(new JoystickView.JoystickListener() {
				@Override
				public void onJoystick(float dx, float dy) {
					glView.getRendererInstance().setMoveDirection(dx, dy);
				}
			});
        int size = dpToPx(150);   // comfortable, unobtrusive
        FrameLayout.LayoutParams joyParams = new FrameLayout.LayoutParams(size, size);
        joyParams.gravity = Gravity.BOTTOM | Gravity.LEFT;
        joyParams.setMargins(dpToPx(8), 0, 0, dpToPx(8));
        root.addView(joystick, joyParams);

        setContentView(root);

        glView.setTapListener(new MyGLSurfaceView.TapListener() {
				@Override
				public void onTopLeftTap() {
					runOnUiThread(new Runnable() {
							@Override
							public void run() {
								debugVisible = !debugVisible;
								debugText.setVisibility(debugVisible ? View.VISIBLE : View.GONE);
							}
						});
				}
			});

        glView.getRendererInstance().setStatusListener(new GLRenderer.StatusListener() {
				@Override
				public void onStatus(final String message) {
					runOnUiThread(new Runnable() {
							@Override
							public void run() {
								debugText.setText(message);
							}
						});
				}
			});
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    private int dpToPx(int dp) {
        return (int) (dp * ((float) getResources().getDisplayMetrics().densityDpi
			/ DisplayMetrics.DENSITY_DEFAULT));
    }
}
