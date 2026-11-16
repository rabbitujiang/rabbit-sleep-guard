package com.rabbit.sleepguard;

import android.accessibilityservice.AccessibilityService;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * The floating rabbit. It lives in the accessibility service's window layer, so it needs no
 * extra permission. It only ever talks to this app's own server: unread counts come from
 * GET /api/device/orb and a tap forwards a poke through POST /api/device/orb/poke.
 */
public final class OrbController {
    private static final long POLL_MS = 20_000L;
    private static final long TAP_WINDOW_MS = 420L;
    private static final long POKED_MS = 1_300L;
    private static final long POKE_COOLDOWN_MS = 60_000L;
    private static final long BOUNCE_EVERY_MS = 4_000L;

    private final AccessibilityService service;
    private final WindowManager windowManager;
    private final GuardPreferences preferences;
    private final GuardApiClient api;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private ImageView rabbit;
    private WindowManager.LayoutParams params;
    private ObjectAnimator bounce;
    private ValueAnimator snap;

    private boolean started;
    private boolean suppressed;
    private boolean attached;
    private boolean receiverRegistered;
    private boolean pollInFlight;
    private boolean poked;
    private int unread;
    private int tapCount;
    private long lastPokeSentAt;
    private String openUrl = "";

    private final Runnable refresh = this::refreshNow;
    private final Runnable endPoked = () -> {
        poked = false;
        applyLook();
    };
    private final Runnable tapDecision = () -> {
        tapCount = 0;
        sendPoke();
    };
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            handler.removeCallbacks(this);
            if (!attached) return;
            if (!pollInFlight) {
                pollInFlight = true;
                api.orbUnread((ok, total, url) -> handler.post(() -> {
                    pollInFlight = false;
                    if (!ok) return;
                    unread = Math.max(0, total);
                    if (url != null && !url.isEmpty()) openUrl = url;
                    applyLook();
                }));
            }
            handler.postDelayed(this, POLL_MS);
        }
    };
    private final Runnable bounceBurst = new Runnable() {
        @Override
        public void run() {
            handler.removeCallbacks(this);
            if (!attached || poked || unread <= 0 || rabbit == null) return;
            if (bounce != null) bounce.cancel();
            bounce = ObjectAnimator.ofFloat(rabbit, "translationY", 0f, -dp(10));
            bounce.setDuration(260L);
            bounce.setRepeatCount(5);
            bounce.setRepeatMode(ValueAnimator.REVERSE);
            bounce.setInterpolator(new AccelerateDecelerateInterpolator());
            bounce.start();
            handler.postDelayed(this, BOUNCE_EVERY_MS);
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handler.post(refresh);
        }
    };
    // Held in a field: SharedPreferences only keeps a weak reference to its listeners.
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener =
            (shared, key) -> {
                if (GuardPreferences.isOrbKey(key)) handler.post(refresh);
            };

    public OrbController(AccessibilityService service, WindowManager windowManager,
                         GuardPreferences preferences, GuardApiClient api) {
        this.service = service;
        this.windowManager = windowManager;
        this.preferences = preferences;
        this.api = api;
    }

    public void start() {
        if (started) return;
        started = true;
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                service.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                service.registerReceiver(screenReceiver, filter);
            }
            receiverRegistered = true;
        } catch (RuntimeException ignored) {
            receiverRegistered = false;
        }
        preferences.listen(preferenceListener);
        refreshNow();
    }

    public void stop() {
        started = false;
        if (receiverRegistered) {
            try {
                service.unregisterReceiver(screenReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            receiverRegistered = false;
        }
        preferences.unlisten(preferenceListener);
        handler.removeCallbacksAndMessages(null);
        detach();
    }

    /** The guard card owns the screen while it is up; the rabbit steps aside. */
    public void setSuppressed(boolean value) {
        suppressed = value;
        handler.post(refresh);
    }

    private void refreshNow() {
        boolean wanted = started && !suppressed && preferences.orbEnabled()
                && preferences.configured() && screenUsable();
        if (wanted) {
            attach();
            handler.removeCallbacks(poll);
            handler.post(poll);
        } else {
            handler.removeCallbacks(poll);
            detach();
        }
    }

    private boolean screenUsable() {
        PowerManager power = (PowerManager) service.getSystemService(Context.POWER_SERVICE);
        if (power != null && !power.isInteractive()) return false;
        KeyguardManager keyguard = (KeyguardManager) service.getSystemService(Context.KEYGUARD_SERVICE);
        return keyguard == null || !keyguard.isKeyguardLocked();
    }

    private void attach() {
        if (attached || windowManager == null) return;
        if (root == null) buildViews();
        params = new WindowManager.LayoutParams(
                dp(84), dp(92),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        Rect screen = screenBounds();
        params.x = edgeX(preferences.orbOnRight(), screen);
        params.y = clampY(Math.round(preferences.orbY() * (screen.height() - dp(92))), screen);
        try {
            windowManager.addView(root, params);
            attached = true;
        } catch (RuntimeException ignored) {
            attached = false;
            return;
        }
        applyLook();
    }

    private void detach() {
        handler.removeCallbacks(bounceBurst);
        handler.removeCallbacks(endPoked);
        handler.removeCallbacks(tapDecision);
        tapCount = 0;
        poked = false;
        if (bounce != null) bounce.cancel();
        if (snap != null) snap.cancel();
        if (!attached) return;
        try {
            windowManager.removeView(root);
        } catch (RuntimeException ignored) {
        }
        attached = false;
    }

    private void buildViews() {
        root = new FrameLayout(service);
        rabbit = new ImageView(service);
        rabbit.setScaleType(ImageView.ScaleType.FIT_CENTER);
        rabbit.setImageResource(R.drawable.orb_idle);
        root.addView(rabbit, new FrameLayout.LayoutParams(
                dp(68), dp(68), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        root.setOnTouchListener(new DragListener());
    }

    private void applyLook() {
        if (!attached || rabbit == null) return;
        if (poked) {
            rabbit.setImageResource(R.drawable.orb_poked);
            stopBounce();
        } else if (unread > 0) {
            rabbit.setImageResource(R.drawable.orb_alert);
            handler.removeCallbacks(bounceBurst);
            handler.post(bounceBurst);
        } else {
            rabbit.setImageResource(R.drawable.orb_idle);
            stopBounce();
        }
    }

    private void stopBounce() {
        handler.removeCallbacks(bounceBurst);
        if (bounce != null) bounce.cancel();
        if (rabbit != null) rabbit.setTranslationY(0f);
    }

    private void onTap() {
        tapCount++;
        showPoked();
        handler.removeCallbacks(tapDecision);
        if (tapCount >= 3) {
            tapCount = 0;
            openWarren();
        } else {
            handler.postDelayed(tapDecision, TAP_WINDOW_MS);
        }
    }

    private void showPoked() {
        poked = true;
        applyLook();
        rabbit.setPivotX(rabbit.getWidth() / 2f);
        rabbit.setPivotY(rabbit.getHeight());
        rabbit.setScaleX(0.86f);
        rabbit.setScaleY(0.86f);
        rabbit.animate().scaleX(1f).scaleY(1f).setDuration(320L)
                .setInterpolator(new OvershootInterpolator(3f)).start();
        handler.removeCallbacks(endPoked);
        handler.postDelayed(endPoked, POKED_MS);
    }

    private void sendPoke() {
        long now = System.currentTimeMillis();
        // The server enforces the same cooldown; this only saves pointless requests.
        if (now - lastPokeSentAt < POKE_COOLDOWN_MS) return;
        lastPokeSentAt = now;
        api.orbPoke((ok, total, url) -> {
        });
    }

    private void openWarren() {
        if (openUrl.isEmpty()) {
            handler.removeCallbacks(poll);
            handler.post(poll);
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(openUrl));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            service.startActivity(intent);
        } catch (RuntimeException ignored) {
            return;
        }
        unread = 0;
        handler.removeCallbacks(poll);
        handler.postDelayed(poll, 4_000L);
    }

    private Rect screenBounds() {
        if (Build.VERSION.SDK_INT >= 30) {
            return windowManager.getMaximumWindowMetrics().getBounds();
        }
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        return new Rect(0, 0, metrics.widthPixels, metrics.heightPixels);
    }

    private int edgeX(boolean right, Rect screen) {
        return right ? screen.width() - dp(84) + dp(6) : -dp(6);
    }

    private int clampY(int y, Rect screen) {
        int top = dp(48);
        int bottom = Math.max(top, screen.height() - dp(92) - dp(48));
        return Math.max(top, Math.min(bottom, y));
    }

    private void snapToEdge() {
        final Rect screen = screenBounds();
        final boolean right = params.x + dp(42) > screen.width() / 2;
        final int target = edgeX(right, screen);
        params.y = clampY(params.y, screen);
        int range = Math.max(1, screen.height() - dp(92));
        preferences.saveOrbPosition(right, Math.max(0f, Math.min(1f, params.y / (float) range)));
        if (snap != null) snap.cancel();
        snap = ValueAnimator.ofInt(params.x, target);
        snap.setDuration(220L);
        snap.setInterpolator(new OvershootInterpolator(1.2f));
        snap.addUpdateListener(animation -> {
            if (!attached) return;
            params.x = (Integer) animation.getAnimatedValue();
            try {
                windowManager.updateViewLayout(root, params);
            } catch (RuntimeException ignored) {
            }
        });
        snap.start();
    }

    private int dp(int value) {
        return Math.round(value * service.getResources().getDisplayMetrics().density);
    }

    private final class DragListener implements View.OnTouchListener {
        private final int slop = ViewConfiguration.get(service).getScaledTouchSlop();
        private float downX;
        private float downY;
        private int startX;
        private int startY;
        private boolean dragging;

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            if (!attached || params == null) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (snap != null) snap.cancel();
                    downX = event.getRawX();
                    downY = event.getRawY();
                    startX = params.x;
                    startY = params.y;
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - downX;
                    float dy = event.getRawY() - downY;
                    if (!dragging && Math.hypot(dx, dy) > slop) dragging = true;
                    if (dragging) {
                        params.x = startX + Math.round(dx);
                        params.y = clampY(startY + Math.round(dy), screenBounds());
                        try {
                            windowManager.updateViewLayout(root, params);
                        } catch (RuntimeException ignored) {
                        }
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (dragging) {
                        snapToEdge();
                    } else {
                        onTap();
                    }
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) snapToEdge();
                    dragging = false;
                    return true;
                default:
                    return false;
            }
        }
    }
}
