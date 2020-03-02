package lhworkshop.flutter.fastqrreaderview;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;

import androidx.annotation.NonNull;

import com.google.zxing.BarcodeFormat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;
import io.flutter.plugin.common.PluginRegistry.Registrar;
import io.flutter.view.FlutterView;
import lhworkshop.flutter.fastqrreaderview.common.CameraSource;
import lhworkshop.flutter.fastqrreaderview.common.CameraSourcePreview;
import lhworkshop.flutter.fastqrreaderview.java.barcodescanning.BarcodeScanningProcessor;
import lhworkshop.flutter.fastqrreaderview.java.barcodescanning.OnCodeScanned;

/**
 * FastQrReaderViewPlugin
 */
public class FastQrReaderViewPlugin implements MethodCallHandler {

    private static final int CAMERA_REQUEST_ID = 513469796;
    private static final String TAG = "FastQrReaderViewPlugin";

    private static CameraManager cameraManager;
    private final FlutterView view;
    private QrReader camera;
    private Activity activity;
    private Registrar registrar;
    private Application.ActivityLifecycleCallbacks activityLifecycleCallbacks;
    private ComponentCallbacks componentCallbacks;
    // The code to run after requesting camera permissions.
    private Runnable cameraPermissionContinuation;
    private boolean requestingPermission;
    private static MethodChannel channel;

    private FastQrReaderViewPlugin(Registrar registrar, FlutterView view, Activity activity) {

        this.registrar = registrar;
        this.view = view;
        this.activity = activity;

        registrar.addRequestPermissionsResultListener(new CameraRequestPermissionsListener());

        this.activityLifecycleCallbacks =
                new Application.ActivityLifecycleCallbacks() {
                    @Override
                    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                    }

                    @Override
                    public void onActivityStarted(Activity activity) {
                    }

                    @Override
                    public void onActivityResumed(Activity activity) {
                        if (requestingPermission) {
                            requestingPermission = false;
                            return;
                        }
                        if (activity == FastQrReaderViewPlugin.this.activity) {
                            Log.d(TAG, "onActivityResumed");
                            if (camera != null) {
                                camera.startCameraSource();
                            }
                        }
                    }

                    @Override
                    public void onActivityPaused(Activity activity) {
                        if (activity == FastQrReaderViewPlugin.this.activity) {
                            Log.d(TAG, "onActivityPaused");
                            if (camera != null) {
                                if (camera.preview != null) {
                                    camera.preview.stop();
                                }
                            }
                        }
                    }

                    @Override
                    public void onActivityStopped(Activity activity) {
                        if (activity == FastQrReaderViewPlugin.this.activity) {
                            Log.d(TAG, "onActivityStopped");
                            if (camera != null) {
                                if (camera.preview != null) {
                                    camera.preview.stop();
                                }
                                if (camera.cameraSource != null) {
                                    camera.cameraSource.release();
                                }
                            }
                        }
                    }

                    @Override
                    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
                    }

                    @Override
                    public void onActivityDestroyed(Activity activity) {
                    }
                };

        this.componentCallbacks = new ComponentCallbacks() {
            @Override
            public void onConfigurationChanged(Configuration configuration) {
                Log.d(TAG, "onConfigurationChanged");
                if (camera.preview != null) {
                    camera.preview.stop();
                    camera.startCameraSource();
                }
            }

            @Override
            public void onLowMemory() {
            }
        };
    }

    /**
     * Plugin registration.
     */
    public static void registerWith(Registrar registrar) {
        channel = new MethodChannel(registrar.messenger(), "fast_qr_reader_view");

        cameraManager = (CameraManager) registrar.context().getSystemService(Context.CAMERA_SERVICE);

        FastQrReaderViewPlugin plugin;
        if (registrar.activity() != null) {
            plugin = new FastQrReaderViewPlugin(registrar, registrar.view(), registrar.activity());
        } else {
            plugin = new FastQrReaderViewPlugin(registrar, null, null);
        }
        channel.setMethodCallHandler(plugin);
    }


    @Override
    public void onMethodCall(MethodCall call, final Result result) {
        switch (call.method) {
            case "init":
                if (camera != null) {
                    camera.close();
                }
                result.success(null);
                break;
            case "availableCameras":
                try {
                    String[] cameraNames = cameraManager.getCameraIdList();
                    List<Map<String, Object>> cameras = new ArrayList<>();
                    for (String cameraName : cameraNames) {
                        HashMap<String, Object> details = new HashMap<>();
                        CameraCharacteristics characteristics =
                                cameraManager.getCameraCharacteristics(cameraName);
                        details.put("name", cameraName);
                        @SuppressWarnings("ConstantConditions")
                        int lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
                        switch (lensFacing) {
                            case CameraMetadata.LENS_FACING_FRONT:
                                details.put("lensFacing", "front");
                                break;
                            case CameraMetadata.LENS_FACING_BACK:
                                details.put("lensFacing", "back");
                                break;
                            case CameraMetadata.LENS_FACING_EXTERNAL:
                                details.put("lensFacing", "external");
                                break;
                        }
                        cameras.add(details);
                    }
                    result.success(cameras);
                } catch (CameraAccessException e) {
                    result.error("cameraAccess", e.getMessage(), null);
                }
                break;
            case "initialize": {
                String cameraName = call.argument("cameraName");
                String resolutionPreset = call.argument("resolutionPreset");
                ArrayList<String> codeFormats = call.argument("codeFormats");

                if (camera != null) {
                    camera.close();
                }
                if (this.activity != null) {
                    this.activity
                            .getApplication()
                            .registerActivityLifecycleCallbacks(this.activityLifecycleCallbacks);
                    this.activity
                            .getApplication()
                            .registerComponentCallbacks(this.componentCallbacks);
                }
                camera = new QrReader(cameraName, resolutionPreset, codeFormats, result);
                break;
            }
            case "startScanning":
                startScanning(result);
                break;
            case "stopScanning":
                stopScanning(result);
                break;
            case "toggleFlash":
                toggleFlash(result);
                break;
            case "dispose": {
                if (camera != null) {
                    camera.dispose();
                }

                if (this.activity != null) {
                    this.activity
                            .getApplication()
                            .unregisterActivityLifecycleCallbacks(this.activityLifecycleCallbacks);
                    this.activity
                            .getApplication()
                            .unregisterComponentCallbacks(this.componentCallbacks);
                }
                result.success(null);
                break;
            }
            default:
                result.notImplemented();
                break;
        }
    }

    private static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow.
            return Long.signum(
                    (long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    private class CameraRequestPermissionsListener
            implements PluginRegistry.RequestPermissionsResultListener {
        @Override
        public boolean onRequestPermissionsResult(int id, String[] permissions, int[] grantResults) {
            if (id == CAMERA_REQUEST_ID) {
                cameraPermissionContinuation.run();
                return true;
            }
            return false;
        }
    }


    void startScanning(@NonNull Result result) {
        camera.scanning = true;
        camera.barcodeScanningProcessor.shouldThrottle.set(false);
        result.success(null);
    }

    void stopScanning(@NonNull Result result) {
        stopScanning();
        result.success(null);
    }

    private void stopScanning() {
        camera.scanning = false;
        camera.barcodeScanningProcessor.shouldThrottle.set(true);
    }

    void toggleFlash(@NonNull Result result) {
        toggleFlash();
        result.success(null);
    }

    private void toggleFlash() {
        camera.cameraSource.toggleFlash();
    }


    private class QrReader {

        private CameraSource cameraSource = null;
        private CameraSourcePreview preview;

        private final FlutterView.SurfaceTextureEntry textureEntry;

        private EventChannel.EventSink eventSink;

        BarcodeScanningProcessor barcodeScanningProcessor;

        ArrayList<BarcodeFormat> reqFormats;
        private int sensorOrientation;
        private boolean isFrontFacing;
        private String cameraName;
        private Size captureSize;
        private Size previewSize;
        private Size videoSize;
        private boolean scanning;

        private void startCameraSource() {
            if (cameraSource != null) {
                try {
                    if (preview == null) {
                        Log.d(TAG, "resume: Preview is null");
                    } else {
                        preview.start(cameraSource);
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Unable to start camera source.", e);
                    cameraSource.release();
                    cameraSource = null;
                }
            }
        }

        QrReader(final String cameraName, final String resolutionPreset, final ArrayList<String> formats, @NonNull final Result result) {

            // AVAILABLE FORMATS:
            // enum CodeFormat { codabar, code39, code93, code128, ean8, ean13, itf, upca, upce, aztec, datamatrix, pdf417, qr }

            Map<String, BarcodeFormat> map = new HashMap<>();
            map.put("codabar", BarcodeFormat.CODABAR);
            map.put("code39", BarcodeFormat.CODE_39);
            map.put("code93", BarcodeFormat.CODE_93);
            map.put("code128", BarcodeFormat.CODE_128);
            map.put("ean8", BarcodeFormat.EAN_8);
            map.put("ean13", BarcodeFormat.EAN_13);
            map.put("itf", BarcodeFormat.ITF);
            map.put("upca", BarcodeFormat.UPC_A);
            map.put("upce", BarcodeFormat.UPC_E);
            map.put("aztec", BarcodeFormat.AZTEC);
            map.put("datamatrix", BarcodeFormat.DATA_MATRIX);
            map.put("pdf417", BarcodeFormat.PDF_417);
            map.put("qr", BarcodeFormat.QR_CODE);


            reqFormats = new ArrayList<>();

            for (String f :
                    formats) {
                if (map.get(f) != null) {
                    reqFormats.add(map.get(f));
                }
            }

            textureEntry = view.createSurfaceTexture();
            try {
                Size minPreviewSize;
                switch (resolutionPreset) {
                    case "high":
                        minPreviewSize = new Size(1024, 768);
                        break;
                    case "medium":
                        minPreviewSize = new Size(640, 480);
                        break;
                    case "low":
                        minPreviewSize = new Size(320, 240);
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown preset: " + resolutionPreset);
                }

                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraName);
                StreamConfigurationMap streamConfigurationMap =
                        characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                //noinspection ConstantConditions
                sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                //noinspection ConstantConditions
                isFrontFacing =
                        characteristics.get(CameraCharacteristics.LENS_FACING)
                                == CameraMetadata.LENS_FACING_FRONT;
                computeBestCaptureSize(streamConfigurationMap);
                computeBestPreviewAndRecordingSize(streamConfigurationMap, minPreviewSize, captureSize);

                if (cameraPermissionContinuation != null) {
                    result.error("cameraPermission", "Camera permission request ongoing", null);
                }
                cameraPermissionContinuation =
                        new Runnable() {
                            @Override
                            public void run() {
                                cameraPermissionContinuation = null;
                                if (!hasCameraPermission()) {
                                    result.error(
                                            "cameraPermission", "MediaRecorderCamera permission not granted", null);
                                    return;
                                }
                                open(result);
                            }
                        };
                requestingPermission = false;
                if (hasCameraPermission()) {
                    cameraPermissionContinuation.run();
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        requestingPermission = true;
                        registrar
                                .activity()
                                .requestPermissions(
                                        new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
                                        CAMERA_REQUEST_ID);
                    }
                }
            } catch (CameraAccessException e) {
                result.error("CameraAccess", e.getMessage(), null);
            } catch (IllegalArgumentException e) {
                result.error("IllegalArgumentException", e.getMessage(), null);
            }
        }

        private void registerEventChannel() {
            new EventChannel(
                    registrar.messenger(), "fast_qr_reader_view/cameraEvents" + textureEntry.id())
                    .setStreamHandler(
                            new EventChannel.StreamHandler() {
                                @Override
                                public void onListen(Object arguments, EventChannel.EventSink eventSink) {
                                    QrReader.this.eventSink = eventSink;
                                }

                                @Override
                                public void onCancel(Object arguments) {
                                    QrReader.this.eventSink = null;
                                }
                            });
        }

        private boolean hasCameraPermission() {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                    || activity.checkSelfPermission(Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED;
        }

        private void computeBestPreviewAndRecordingSize(
                StreamConfigurationMap streamConfigurationMap, Size minPreviewSize, Size captureSize) {
            Size[] sizes = streamConfigurationMap.getOutputSizes(SurfaceTexture.class);
            float captureSizeRatio = (float) captureSize.getWidth() / captureSize.getHeight();
            List<Size> goodEnough = new ArrayList<>();
            for (Size s : sizes) {
                if ((float) s.getWidth() / s.getHeight() == captureSizeRatio
                        && minPreviewSize.getWidth() < s.getWidth()
                        && minPreviewSize.getHeight() < s.getHeight()) {
                    goodEnough.add(s);
                }
            }

            Collections.sort(goodEnough, new CompareSizesByArea());

            if (goodEnough.isEmpty()) {
                previewSize = sizes[0];
                videoSize = sizes[0];
            } else {
                previewSize = goodEnough.get(0);

                // Video capture size should not be greater than 1080 because MediaRecorder cannot handle higher resolutions.
                videoSize = goodEnough.get(0);
                for (int i = goodEnough.size() - 1; i >= 0; i--) {
                    if (goodEnough.get(i).getHeight() <= 1080) {
                        videoSize = goodEnough.get(i);
                        break;
                    }
                }
            }
        }

        private void computeBestCaptureSize(StreamConfigurationMap streamConfigurationMap) {
            // For still image captures, we use the largest available size.
            captureSize =
                    Collections.max(
                            Arrays.asList(streamConfigurationMap.getOutputSizes(ImageFormat.YUV_420_888)),
                            new CompareSizesByArea());
        }

        @SuppressLint("MissingPermission")
        private void open(@NonNull final Result result) {
            if (!hasCameraPermission()) {
                result.error("cameraPermission", "Camera permission not granted", null);
            } else {
                cameraSource = new CameraSource(activity);
                cameraSource.setFacing(isFrontFacing ? 1 : 0);
                barcodeScanningProcessor = new BarcodeScanningProcessor(reqFormats);
                barcodeScanningProcessor.callback = new OnCodeScanned() {
                    @Override
                    public void onCodeScanned(com.google.zxing.Result barcode) {
                        if (camera.scanning) {
                            final String value = barcode.getText();
                            Log.w(TAG, "onSuccess: " + value);
                            activity.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    channel.invokeMethod("updateCode", value);
                                }
                            });
                            stopScanning();
                        }
                    }
                };
                cameraSource.setFrameProcessor(barcodeScanningProcessor);
                preview = new CameraSourcePreview(activity, null, textureEntry.surfaceTexture());

                startCameraSource();
                registerEventChannel();

                Map<String, Object> reply = new HashMap<>();
                reply.put("textureId", textureEntry.id());
                reply.put("previewWidth", cameraSource.getPreviewSize().getWidth());
                reply.put("previewHeight", cameraSource.getPreviewSize().getHeight());
                reply.put("previewRotation", cameraSource.getRotation());
                result.success(reply);
            }
        }

        private void sendErrorEvent(final String errorDescription) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (eventSink != null) {
                        Map<String, String> event = new HashMap<>();
                        event.put("eventType", "error");
                        event.put("errorDescription", errorDescription);
                        eventSink.success(event);
                    }
                }
            });
        }

        private void close() {
            if (preview != null) {
                preview.stop();
            }
            if (cameraSource != null) {
                cameraSource.release();
            }
            camera = null;
        }

        private void dispose() {
            textureEntry.release();
            if (preview != null) {
                preview.stop();
            }
            if (cameraSource != null) {
                cameraSource.release();
            }
        }
    }
}

