package ch.hsr.eyecam;

import java.util.List;

import android.app.Activity;
import android.content.Context;
import android.hardware.Camera;
import android.hardware.Camera.Parameters;
import android.hardware.Camera.PreviewCallback;
import android.hardware.Camera.Size;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.view.OrientationEventListener;
import ch.hsr.eyecam.view.ColorView;
import ch.hsr.eyecam.view.ControlBar;

/**
 * This class represents the core of the eyeCam application. It is responsible for
 * the initialization of the Camera and the View and managing all aspects of the
 * life cycle of the application itself.
 * 
 * @author Dominik Spengler, Patrice Mueller
 * @see <a href="http://developer.android.com/reference/
 * 			android/app/Activity.html">
 * 			android.app.Activity</a>
 */
public class EyeCamActivity extends Activity {
	private PowerManager.WakeLock mWakeLock;
	private OrientationEventListener mOrientationEventListener;
	private Camera mCamera;
	private byte[] mCallBackBuffer;
	
	private ColorView mColorView;
	private ControlBar mControlBar;
	private Orientation mOrientationCurrent =  Orientation.UNKNOW;
	
	private Handler mHandler = new Handler(){
		@Override
		public void handleMessage(Message msg) {
			switch (msg.what){
			case CAMERA_START_PREVIEW:
				startCameraPreview();
				break;
			case CAMERA_STOP_PREVIEW:
				stopCameraPreview();
				break;
			case CAMERA_LIGHT_OFF:
				setCameraLight(Camera.Parameters.FLASH_MODE_OFF);
				break;
			case CAMERA_LIGHT_ON:
				setCameraLight(Camera.Parameters.FLASH_MODE_TORCH);
				break;
			}
		}

	};
	
	private final DisplayMetrics mMetrics = new DisplayMetrics();
	private final static String LOG_TAG = "ch.hsr.eyecam.EyeCamActivity";
	
	public final static int CAMERA_START_PREVIEW = 0;
	public final static int CAMERA_STOP_PREVIEW = 1;
	public final static int CAMERA_LIGHT_OFF = 2;
	public final static int CAMERA_LIGHT_ON = 3;
	
	private void setCameraLight(String cameraFlashMode) {
		Parameters parameters = mCamera.getParameters();
		parameters.setFlashMode(cameraFlashMode);
		mCamera.setParameters(parameters);
	}

	/** 
	 * Called when the activity is first created.
	 * 
	 * @see <a href="http://developer.android.com/reference/
	 * 			android/app/Activity.html#ActivityLifecycle">
	 * 			android.app.Activity#ActivityLifecycle</a>
	 */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);
		
		mColorView = (ColorView) findViewById(R.id.cameraSurface);
		mControlBar = (ControlBar) findViewById(R.id.conrolBar);
		mControlBar.setActivityHandler(mHandler);
		mControlBar.enableOnClickListeners();
		mControlBar.rotate(Orientation.UNKNOW);
	
		getWindowManager().getDefaultDisplay().getMetrics(mMetrics);
		
		PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
		mWakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "eyeCam");
		
		initOrientationEventListener();
		mOrientationEventListener.enable();
	}

	private void initOrientationEventListener() {
		mOrientationEventListener = new OrientationEventListener(this, 
				SensorManager.SENSOR_DELAY_NORMAL) {
			
			@Override
			public void onOrientationChanged(int inputOrientation) {
				Orientation orientation = getCurrentOrientation(inputOrientation);
				if(orientation != mOrientationCurrent){
					mOrientationCurrent = orientation;
					mControlBar.rotate(mOrientationCurrent);
					mColorView.setOrientation(mOrientationCurrent);
					Log.d(LOG_TAG, "Orientation: "+mOrientationCurrent);
				}			
			}
			
			private Orientation getCurrentOrientation(int orientationInput){
				int orientation = orientationInput;
				orientation = orientation % 360;
				
				if (orientation < (0 * 90) + 45) return Orientation.PORTRAIT;
				if (orientation < (1 * 90) + 45) return Orientation.LANDSCAPE_RIGHT;
				if (orientation < (2 * 90) + 45) return Orientation.PORTRAIT;
				if (orientation < (3 * 90) + 45) return Orientation.LANDSCAPE_LEFT;
				
				return Orientation.PORTRAIT;
			}
		};
	}

	/** 
	 * Called after onCreate() and onStart().
	 * 
	 * @see <a href="http://developer.android.com/reference/
	 * 			android/app/Activity.html#ActivityLifecycle">
	 * 			android.app.Activity#ActivityLifecycle</a>
	 */
	@Override
	protected void onResume() {
		super.onResume();
		initCamera();
		mWakeLock.acquire();
		mOrientationEventListener.enable();
	}

	private void initCamera() {
		mCamera= Camera.open();
		Camera.Parameters parameters = mCamera.getParameters();	
		
		Size optSize = getOptimalSize(parameters.getSupportedPreviewSizes());
		for (Size s : parameters.getSupportedPreviewSizes()){
			Log.d(LOG_TAG, "Supported - H:" + s.height + "W:" + s.width);
		}
		parameters.setPreviewSize(optSize.width, optSize.height);
		Log.d(LOG_TAG, "Chosen - H:" +optSize.height + "W:" +optSize.width);
		Log.d(LOG_TAG, "Screen - H:" +mMetrics.heightPixels + "W:" 
				+mMetrics.widthPixels);
		
		disableFlashIfUnsupported(parameters);
		
		mCallBackBuffer = new byte[optSize.width*optSize.height*2];
		mColorView.setDataBuffer(mCallBackBuffer, optSize.width, optSize.height);
		mCamera.setParameters(parameters);
		startCameraPreview();
		mControlBar.setCamIsPreviewing();
	}

	private Size getOptimalSize(List<Size> sizeList){
		if(isNull(sizeList)) return null;
		
		double targetRatio = (double) mMetrics.widthPixels / mMetrics.heightPixels;
		int targetHeight = mMetrics.heightPixels;
		double diffRatio = Double.MAX_VALUE;
		Size optSize = null;
		
		for(Size size : sizeList){
			double tmpDiffRatio = (double) size.width / size.height;
			if(Math.abs(targetRatio-tmpDiffRatio)< diffRatio){
				optSize = size;
				diffRatio = Math.abs(targetRatio-tmpDiffRatio) +
							Math.abs(size.height-targetHeight);
				if (diffRatio == 0) return optSize;
			}
		}
		return optSize;
	}

	private void disableFlashIfUnsupported(Camera.Parameters parameters) {
		if(parameters.getSupportedFlashModes() == null){
			mControlBar.enableLight(false);
		}
		else if(parameters.getSupportedFlashModes().contains(Camera.Parameters.FLASH_MODE_TORCH))
			mControlBar.enableLight(true);
	}

	private void startCameraPreview() {
		mCamera.addCallbackBuffer(mCallBackBuffer);
		mCamera.setPreviewCallbackWithBuffer((PreviewCallback) mColorView);
		mCamera.startPreview();
		mColorView.enablePopup(false);
	}

	/** 
	 * Called whenever the Activity will be sent to the background.
	 * 
	 * @see <a href="http://developer.android.com/reference/
	 * 			android/app/Activity.html#ActivityLifecycle">
	 * 			android.app.Activity#ActivityLifecycle</a>
	 */
	@Override
	protected void onPause() {
		super.onPause();
		releaseCamera();
		mWakeLock.release();
		mOrientationEventListener.disable();
	}
	
	private void releaseCamera(){
		if(isNull(mCamera)) return;
		stopCameraPreview();
		mCamera.release();
		mCamera = null;
	}

	private void stopCameraPreview() {
		mCamera.setPreviewCallbackWithBuffer(null);
		mCamera.stopPreview();
		mColorView.enablePopup(true);
	}

	/** 
	 * Called whenever the activity will be shut down.
	 * 
	 * @see <a href="http://developer.android.com/reference/
	 * 			android/app/Activity.html#ActivityLifecycle">
	 * 			android.app.Activity#ActivityLifecycle</a>
	 */
	@Override
	protected void onDestroy() {
		mOrientationEventListener.disable();
		mColorView.dismissPopup();
		mControlBar.dismissMenu();
		super.onDestroy();
	}

	private boolean isNotNull(Object anyObject) {
		return anyObject != null;
	}
	
	private boolean isNull(Object anyObject){
		return !isNotNull(anyObject);
	}

	/**
	 * By overwriting this hook, the activity blocks search requests.
	 * 
	 * @see <a href="http://developer.android.com/reference/
	 * 			android/app/Activity.html#onSearchRequested()">
	 * 			android.app.Activity#onSearchRequested()</a>
	 */
	@Override
	public boolean onSearchRequested(){
		return false;
	}
	
	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		if (mControlBar.isMenuShowing()){
			mControlBar.dismissMenu();
			return true;
		}
		
		return super.onKeyDown(keyCode, event);
	}
}
