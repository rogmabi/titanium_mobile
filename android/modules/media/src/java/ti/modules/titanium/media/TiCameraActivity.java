package ti.modules.titanium.media;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.appcelerator.titanium.TiBaseActivity;
import org.appcelerator.titanium.proxy.TiViewProxy;

import ti.modules.titanium.media.android.AndroidModule.MediaScannerClient;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.hardware.Camera;
import android.hardware.Camera.Parameters;
import android.hardware.Camera.PictureCallback;
import android.hardware.Camera.PreviewCallback;
import android.hardware.Camera.ShutterCallback;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.SurfaceHolder;
import android.content.Intent;
import android.view.SurfaceView;
import android.view.ViewGroup.LayoutParams;
import android.widget.FrameLayout;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.graphics.ImageFormat;

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
	private FrameLayout previewLayout;
	
	public static MediaModule mediaModule = null;
	public static TiViewProxy overlayProxy = null;
	public static TiCameraActivity cameraActivity = null;
	public static boolean autohide = true;
	public static boolean skipPreview = false;
	public static KrollFunction successCallback = null;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// set picture location
		storageUri = (Uri)getIntent().getParcelableExtra(MediaStore.EXTRA_OUTPUT);

		// create camera preview
		preview = new SurfaceView(this);
		SurfaceHolder previewHolder = preview.getHolder();
		previewHolder.addCallback(this);
		previewHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

		// set preview overlay
		localOverlayProxy = overlayProxy;
		overlayProxy = null; // clear the static object once we have a local reference

		// set overall layout - will populate in onResume
		previewLayout = new FrameLayout(this);

		setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
		setContentView(previewLayout);
	}

	public void surfaceChanged(SurfaceHolder previewHolder, int format, int width, int height) {
		camera.startPreview();  // make sure setPreviewDisplay is called before this
	}

	public void surfaceCreated(SurfaceHolder previewHolder) {
		camera = Camera.open();

		/*
		 * Disabling this since it can end up picking a bad preview
		 * size which can create stretching issues (TIMOB-8151).
		 * Original words of wisdom left by some unknown person:
		 * "using default preview size may be causing problem on some devices, setting dimensions manually"
		 * We may want to expose camera parameters to the developer for extra control.
		Parameters cameraParams = camera.getParameters();
		Camera.Size previewSize = cameraParams.getSupportedPreviewSizes().get((cameraParams.getSupportedPreviewSizes().size()) - 1);
		cameraParams.setPreviewSize(previewSize.width, previewSize.height );
		camera.setParameters(cameraParams);
		*/

		try {
			Log.i(LCAT, "setting preview display");
			camera.setPreviewDisplay(previewHolder);
		} catch(IOException e) {
			e.printStackTrace();
		}
	}

	// make sure to call release() otherwise you will have to force kill the app before 
	// the built in camera will open
	public void surfaceDestroyed(SurfaceHolder previewHolder) {
		camera.setOneShotPreviewCallback(null);
		camera.release();
		camera = null;
	}
	
	static private void savePicture(byte[] data, Camera camera) {
		// Made a separate function since I'm going to re-use this :)
		FileOutputStream outputStream = null;
		try {
			// write photo to storage
			outputStream = new FileOutputStream(cameraActivity.storageUri.getPath());
			outputStream.write(data);
			outputStream.close();
			if(autohide) {
				cameraActivity.setResult(Activity.RESULT_OK);
				cameraActivity.finish();
			} else {
				// FIX: made separate onResult() handler for overriding default Titanium behaviour.
				// I did it in a way that the rest of Titanium will not be affected by this.
				mediaModule.resultHandler.onResult(cameraActivity, 0, Activity.RESULT_OK, null);
			}
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@Override
	protected void onResume() {
		super.onResume();

		cameraActivity = this;
		previewLayout.addView(preview);
		previewLayout.addView(localOverlayProxy.getOrCreateView().getNativeView(), new FrameLayout.LayoutParams(LayoutParams.FILL_PARENT, LayoutParams.FILL_PARENT));
	}

	@Override
	protected void onPause() {
		super.onPause();

		previewLayout.removeView(preview);
		previewLayout.removeView(localOverlayProxy.getOrCreateView().getNativeView());

		try {
			camera.setOneShotPreviewCallback(null);
			camera.release();
			camera = null;
		} catch (Throwable t) {
			Log.i(LCAT, "camera is not open, unable to release");
		}

		cameraActivity = null;
	}

	static public void takePicture() {
		Log.i(LCAT, "Taking picture");
		if(skipPreview)
			camera.setOneShotPreviewCallback(previewCallback);
		else // Or just the usual Titanium stuff
			camera.takePicture(shutterCallback, rawCallback, jpegCallback);
	}

	// support user defined callback for this in the future?
	static ShutterCallback shutterCallback = new ShutterCallback() {
		public void onShutter() {
		}
	};

	// support user defined callback for this in the future?
	static PictureCallback rawCallback = new PictureCallback() {
		public void onPictureTaken(byte[] data, Camera camera) {
		}
	};
	
	// Preview callback used for "no-preview" workaround for Yamm.ca
	static PreviewCallback previewCallback = new PreviewCallback() {
 
	  public void onPreviewFrame(byte[] data, Camera camera) {
		// Catch pixel stream of live preview to give it back to Titanium instead of the "real" picture.
		if(skipPreview) {
			Camera.Parameters parameters = camera.getParameters();
			int format = parameters.getPreviewFormat();
			if(format == ImageFormat.NV21) {
				int w = parameters.getPreviewSize().width;
				int h = parameters.getPreviewSize().height;
				YuvImage image = new YuvImage(data, format, w, h, null);
				Rect rect = new Rect(0, 0, w, h);
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				image.compressToJpeg(rect, 100, out);
				savePicture(out.toByteArray(), camera);
			}
		}		
	  }
	};

	static PictureCallback jpegCallback = new PictureCallback() {
		public void onPictureTaken(byte[] data, Camera camera) {
			savePicture(data, camera);
		}
	};
}
