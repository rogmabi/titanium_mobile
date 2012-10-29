package ti.modules.titanium.media;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import java.util.List;

import org.appcelerator.titanium.TiBaseActivity;
import org.appcelerator.titanium.proxy.TiViewProxy;

import ti.modules.titanium.media.android.AndroidModule.MediaScannerClient;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.hardware.Camera;
import android.hardware.Camera.Size;
import android.hardware.Camera.Parameters;
import android.hardware.Camera.PictureCallback;
import android.hardware.Camera.PreviewCallback;
import android.hardware.Camera.ShutterCallback;
import android.net.Uri;
import android.content.Context;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.SurfaceHolder;
import android.content.Intent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.animation.Animation;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.graphics.ImageFormat;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.view.animation.AlphaAnimation;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.Animation.AnimationListener;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import org.appcelerator.kroll.KrollDict;
import org.appcelerator.kroll.KrollFunction;
import org.appcelerator.kroll.KrollModule;
import org.appcelerator.kroll.KrollObject;
import org.appcelerator.kroll.KrollRuntime;

public class TiCameraActivity extends TiBaseActivity implements SurfaceHolder.Callback {
    private static final String LCAT = "TiCameraActivity";
    private static Camera camera;

    private TiViewProxy localOverlayProxy = null;
    private Uri storageUri;
    private SurfaceView preview;
    private static ImageView previewFix; // Yamm preview workaroundo
    private static FrameLayout previewLayout;
    private static MediaPlayer _shootMP;
    private static boolean savingPictureLock = false; 
    public static MediaModule mediaModule = null;
    public static TiViewProxy overlayProxy = null;
    public static TiCameraActivity cameraActivity = null;
    public static boolean autohide = true;
    public static boolean skipPreview = false;
    public static KrollFunction successCallback = null;
    private static Context context;
    private static byte[] currentImageData = null;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TiCameraActivity.context = getApplicationContext();

        // set picture location
        storageUri = (Uri) getIntent().getParcelableExtra(
        MediaStore.EXTRA_OUTPUT);

        // create camera preview
        preview = new SurfaceView(this);
        SurfaceHolder previewHolder = preview.getHolder();
        previewHolder.addCallback(this);
        previewHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        
        // Preview fix
        previewFix = new ImageView(this);

        // set preview overlay
        localOverlayProxy = overlayProxy;
        overlayProxy = null; // clear the static object once we have a local
        // reference
        
        // set overall layout - will populate in onResume
        previewLayout = new FrameLayout(this);

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        setContentView(previewLayout);
    }

    private Size getOptimalPreviewSize(List < Size > sizes, int w, int h) {
        final double ASPECT_TOLERANCE = 0.05;
        double targetRatio = (double) w / h;
        if (sizes == null) return null;

        Size optimalSize = null;
        double minDiff = Double.MAX_VALUE;

        int targetHeight = h;

        // Try to find an size match aspect ratio and size
        for (Size size: sizes) {
            double ratio = (double) size.width / size.height;
            if (Math.abs(ratio - targetRatio) > ASPECT_TOLERANCE) continue;
            if (Math.abs(size.height - targetHeight) < minDiff) {
                optimalSize = size;
                minDiff = Math.abs(size.height - targetHeight);
            }
        }

        // Cannot find the one match the aspect ratio, ignore the requirement
        if (optimalSize == null) {
            minDiff = Double.MAX_VALUE;
            for (Size size: sizes) {
                if (Math.abs(size.height - targetHeight) < minDiff) {
                    optimalSize = size;
                    minDiff = Math.abs(size.height - targetHeight);
                }
            }
        }
        return optimalSize;
    }

    public void surfaceChanged(SurfaceHolder previewHolder, int format,
    int width, int height) {
        Camera.Parameters parameters = camera.getParameters();
        List < Size > sizes = parameters.getSupportedPreviewSizes();
        Size optimalSize = getOptimalPreviewSize(sizes, width, height);
        parameters.setPreviewSize(optimalSize.width, optimalSize.height);
        Log.i(LCAT, "Optional Size: " + optimalSize.width + "x" + optimalSize.height);
        camera.setParameters(parameters);
        camera.startPreview(); // make sure setPreviewDisplay is called before
        // this
    }

    public void surfaceCreated(SurfaceHolder previewHolder) {
        camera = Camera.open();
        camera.setPreviewCallback(previewCallback);
        /*
         * Disabling this since it can end up picking a bad preview size which
         * can create stretching issues (TIMOB-8151). Original words of wisdom
         * left by some unknown person:
         * "using default preview size may be causing problem on some devices, setting dimensions manually"
         * We may want to expose camera parameters to the developer for extra
         * control. Parameters cameraParams = camera.getParameters();
         * Camera.Size previewSize =
         * cameraParams.getSupportedPreviewSizes().get(
         * (cameraParams.getSupportedPreviewSizes().size()) - 1);
         * cameraParams.setPreviewSize(previewSize.width, previewSize.height );
         * camera.setParameters(cameraParams);
         */

        try {
            Log.i(LCAT, "setting preview display");
            camera.setPreviewDisplay(previewHolder);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // make sure to call release() otherwise you will have to force kill the app
    // before
    // the built in camera will open
    public void surfaceDestroyed(SurfaceHolder previewHolder) {
		camera.setPreviewCallback(null);
		TiCameraActivity.currentImageData = null;
        camera.release();
        camera = null;
    }

    static private void savePicture(byte[] data, Camera camera) {
        // Made a separate function since I'm going to re-use this :)
        TiCameraActivity.savingPictureLock = true;
        FileOutputStream outputStream = null;
        try {
            // write photo to storage
            outputStream = new FileOutputStream(
            cameraActivity.storageUri.getPath());
            outputStream.write(data);
            outputStream.close();
            if (autohide) {
                cameraActivity.setResult(Activity.RESULT_OK);
                cameraActivity.finish();
            } else {
                // FIX: made separate onResult() handler for overriding default
                // Titanium behaviour.
                // I did it in a way that the rest of Titanium will not be
                // affected by this.
                mediaModule.resultHandler.onResult(cameraActivity, 0,
                Activity.RESULT_OK, null);
                if (skipPreview) {
                    camera.startPreview();
                    shutterLock = false;
                    previewFix.setVisibility(View.INVISIBLE);
                }
            }
            TiCameraActivity.savingPictureLock = false;
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        TiCameraActivity.savingPictureLock = false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        cameraActivity = this;
        previewLayout.addView(preview);
        previewLayout.addView(previewFix,
			new FrameLayout.LayoutParams(LayoutParams.FILL_PARENT,
					LayoutParams.FILL_PARENT));
		previewFix.setVisibility(View.INVISIBLE);
        previewLayout.addView(localOverlayProxy.getOrCreateView()
            .getNativeView(), new FrameLayout.LayoutParams(
        LayoutParams.FILL_PARENT, LayoutParams.FILL_PARENT));
    }

    @Override
    protected void onPause() {
        super.onPause();
        previewLayout.removeView(preview);
        previewLayout.removeView(previewFix);
        previewLayout.removeView(localOverlayProxy.getOrCreateView()
            .getNativeView());
        try {
			camera.setPreviewCallback(null);
			TiCameraActivity.currentImageData = null;
            camera.release();
            camera = null;
        } catch (Throwable t) {
            Log.i(LCAT, "camera is not open, unable to release");
        }

        cameraActivity = null;
    }

    static public void takePicture() {
        Log.i(LCAT, "Taking picture");
        if(!autohide && TiCameraActivity.currentImageData != null) {
			shutterLock = true;
			Handler refresh = new Handler(Looper.getMainLooper());
			refresh.post(new Runnable() {
				public void run()
				{
					if(!TiCameraActivity.savingPictureLock) {
						byte[] data = TiCameraActivity.currentImageData;
						Camera.Parameters parameters = camera.getParameters();
						int format = parameters.getPreviewFormat();
						if (format == ImageFormat.NV21) {
							int w = parameters.getPreviewSize().width;
							int h = parameters.getPreviewSize().height;
							YuvImage image = new YuvImage(data, format, w, h, null);
							Rect rect = new Rect(0, 0, w, h);
							ByteArrayOutputStream out = new ByteArrayOutputStream();
							image.compressToJpeg(rect, 100, out);
							byte[] imageData = out.toByteArray();
							Bitmap imageBitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.length);
							if(imageBitmap == null) {
								Log.i(LCAT, "PreviewFix failed :(");
							} else {
								previewFix.setImageBitmap(imageBitmap);
							}					
							previewFix.setVisibility(View.VISIBLE);
						}
						data = null;
					}
				}
			});
		}
        camera.takePicture(shutterCallback, rawCallback, jpegCallback);
    }
    
    static boolean shutterLock = false;
	// Preview callback used for "no-preview" workaround for Yamm.ca
	static PreviewCallback previewCallback = new PreviewCallback() {

		public void onPreviewFrame(byte[] data, Camera camera) {
			// Catch pixel stream of live preview to give it back to Titanium
			// instead of the "real" picture.
			// Simulate android visual and audio feedback :)
			if (skipPreview) {
				if (shutterLock)
					return;
				TiCameraActivity.currentImageData = data;
			}
		}
	};


    // support user defined callback for this in the future?
    static ShutterCallback shutterCallback = new ShutterCallback() {
        public void onShutter() {
        }
    };

    // support user defined callback for this in the future?
    static PictureCallback rawCallback = new PictureCallback() {
        public void onPictureTaken(byte[] data, Camera camera) {}
    };

    static PictureCallback jpegCallback = new PictureCallback() {
        public void onPictureTaken(byte[] data, Camera camera) {
            savePicture(data, camera);
        }
    };
}
