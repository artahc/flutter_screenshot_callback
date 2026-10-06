package com.flutter.moum.screenshot_callback;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import kotlin.Unit;
import kotlin.jvm.functions.Function1;

public class ScreenshotCallbackPlugin implements MethodCallHandler, FlutterPlugin, ActivityAware {
    private MethodChannel channel;
    private static final String ttag = "screenshot_callback";

    private Context applicationContext;
    private Activity activity;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean initialized;

    // API <= 33: watch MediaStore for new screenshot images.
    private ScreenshotDetector detector;
    private String lastScreenshotName;

    // API >= 34: official screen capture callback. Typed as Object so this class
    // does not reference Activity.ScreenCaptureCallback on older devices.
    private Object screenCaptureCallback;

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        onAttachedToEngine(binding.getApplicationContext(), binding.getBinaryMessenger());
    }

    private void onAttachedToEngine(Context applicationContext, BinaryMessenger messenger) {
        this.applicationContext = applicationContext;
        channel = new MethodChannel(messenger, "flutter.moum/screenshot_callback");
        channel.setMethodCallHandler(this);
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        initialized = false;
        stopDetection();
        applicationContext = null;
        if (channel != null) {
            channel.setMethodCallHandler(null);
            channel = null;
        }
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        activity = binding.getActivity();
        if (initialized) {
            startDetection();
        }
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity();
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        onAttachedToActivity(binding);
    }

    @Override
    public void onDetachedFromActivity() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            unregisterScreenCaptureCallback();
        }
        activity = null;
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
        switch (call.method) {
            case "initialize":
                // Re-initializing (e.g. after hot restart) must not leak the previous observer.
                stopDetection();
                initialized = true;
                startDetection();
                result.success("initialize");
                break;
            case "dispose":
                initialized = false;
                stopDetection();
                result.success("dispose");
                break;
            default:
                result.notImplemented();
        }
    }

    private void startDetection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Registered once an activity is attached; see onAttachedToActivity.
            registerScreenCaptureCallback();
            return;
        }

        if (detector != null || applicationContext == null) {
            return;
        }
        detector = new ScreenshotDetector(applicationContext, new Function1<String, Unit>() {
            @Override
            public Unit invoke(String screenshotName) {
                if (!screenshotName.equals(lastScreenshotName)) {
                    lastScreenshotName = screenshotName;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            notifyScreenshot();
                        }
                    });
                }
                return null;
            }
        });
        detector.start();
    }

    private void stopDetection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            unregisterScreenCaptureCallback();
        }
        if (detector != null) {
            detector.stop();
            detector = null;
        }
        lastScreenshotName = null;
    }

    private void notifyScreenshot() {
        if (channel != null) {
            channel.invokeMethod("onCallback", null);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private void registerScreenCaptureCallback() {
        if (activity == null || screenCaptureCallback != null) {
            return;
        }
        Activity.ScreenCaptureCallback callback = new Activity.ScreenCaptureCallback() {
            @Override
            public void onScreenCaptured() {
                notifyScreenshot();
            }
        };
        activity.registerScreenCaptureCallback(activity.getMainExecutor(), callback);
        screenCaptureCallback = callback;
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private void unregisterScreenCaptureCallback() {
        if (activity != null && screenCaptureCallback != null) {
            activity.unregisterScreenCaptureCallback(
                    (Activity.ScreenCaptureCallback) screenCaptureCallback);
        }
        screenCaptureCallback = null;
    }
}
