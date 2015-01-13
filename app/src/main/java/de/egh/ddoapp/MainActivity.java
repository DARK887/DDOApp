package de.egh.ddoapp;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.support.v7.app.ActionBarActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;

import de.egh.dynamodrivenodometer.AppService;
import de.egh.dynamodrivenodometer.MessageBox;


public class MainActivity extends ActionBarActivity {


	// This name must be set in the RFDuino
	private static final String TAG = MainActivity.class.getSimpleName();
	//Last time a distance value has been received as timestamp
	private long mDodLastUpdateAt;
	//Distance in meters
	private long mDodDistanceValue;
	//Messages from the device
	private MessageBox messageBox;
	private SimpleDateFormat sdf = new SimpleDateFormat("dd.MM HH:mm:ss");
	private TextView distanceValueView;
	private TextView connectedToView;
	private TextView lastUpdateAtView;
	private TextView messageView;

	/**
	 * Restart the read
	 */
	private Runnable mRunnableValueReadJob;

	/**
	 * Is NULL when service is disconnected.
	 */
	private AppService mService;
	/**
	 * TRUE, if Device can be used for reading value data
	 */
	private boolean mDeviceAvailable = false;
	//For timer-driven actions: Connection end and alive-check
	private Handler mHandler;
	private BluetoothAdapter mBluetoothAdapter;
	//True while in device scanning mode
	private Scanmode mScanning;
	/**
	 * Receices GATT messages.
	 */
	private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			//		Log.v(TAG, "onReceive() ...");
			final String action = intent.getAction();

			// Status change to connected
			switch (action) {

				// Status change to disconnected
				case AppService.Constants.Actions.NOT_CONNECTED:
					Log.v(TAG, "Received: not connected.");
					mDeviceAvailable = false;
					connectedToView.setText(getString(R.string.dodConnectedFalse));
					updateUI();
					break;

				// Status state after Connected: Services available
				case AppService.Constants.Actions.CONNECTED:
					Log.v(TAG, "Received: connected.");
					mDeviceAvailable = true;
					mScanning = Scanmode.OFF;
					connectedToView.setText(getString(R.string.dodConnectedTrue));
					//It's time for our first value reading
					mService.readValue();
					updateUI();
					break;

				// Trigger: New data available
				case AppService.Constants.Actions.DATA_AVAILABLE:
//				Log.v(TAG, "GATT data available.");
					processMessage(intent.getStringExtra(AppService.Constants.EXTRA_DATA));
					startJob();
					break;

				case AppService.Constants.Actions.SCANNING:
					Log.v(TAG, "Received: Scanning");
					mScanning = Scanmode.SCANNING;
					connectedToView.setText(getString(R.string.dodConnectedScanning));
					updateUI();
					break;

				// Default
				default:
					Log.v(TAG, "Received unknown action: " + action + ".");
					break;
			}
		}
	};
	private TextView distanceMeterValueView;
	private boolean mDemo;
	// Code to manage Service lifecycle.
	private final ServiceConnection mServiceConnection = new ServiceConnection() {

		@Override
		public void onServiceConnected(ComponentName componentName, IBinder service) {
			Log.v(TAG, "onServiceConnected()");

			mService = ((AppService.LocalBinder) service).getService();
			// We want service to alive after unbinding to hold the actual status
//			startService(new Intent(MainActivity.this, AppService.class));

			if (!mService.initialize()) {
				Log.e(TAG, "Unable to initialize Bluetooth");
				finish();
			}


			startScanning();


		}

		@Override
		public void onServiceDisconnected(ComponentName componentName) {
			mService = null;
			Log.v(TAG, "onServiceDisconnected()");
		}
	};
	private TextView mDeviceNameView;

	private static IntentFilter makeGattUpdateIntentFilter() {
		final IntentFilter intentFilter = new IntentFilter();
		intentFilter.addAction(AppService.Constants.Actions.NOT_CONNECTED);
		intentFilter.addAction(AppService.Constants.Actions.CONNECTED);
		intentFilter.addAction(AppService.Constants.Actions.DATA_AVAILABLE);
		intentFilter.addAction(AppService.Constants.Actions.SCANNING);

		return intentFilter;
	}

	/**
	 * When GATT is available,
	 */
	private void startJob() {
//		Log.v(TAG, "startJob");

		//Initialize job one time.
		mRunnableValueReadJob = new Runnable() {

			@Override
			public void run() {
//					Log.v(TAG, "Job ended!");
				if (mService != null && mDeviceAvailable) {
					mService.readValue();
				}
			}
		};
		// Refresh every 1 Second
		mHandler.postDelayed(mRunnableValueReadJob, 1000);
	}


	/**
	 * Analyses the value and updates storage and UI.
	 */
	private void processMessage(String data) {

		String message = null;
		Log.v(TAG, "processMessage() " + data);

		if (data != null && data.length() > 0) {
//			Log.v(TAG, "Value >>>" + data + "<<<");

			//First character holds the type.
			char dataType = data.charAt(0);
			String dataContent = data.substring(1);

			//Distance value: Distance in Millimeters
			if (dataType == AppService.Constants.Datatypes.DISTANCE.charAt(0)) {

				long value;

				//Expecting the distance in mm as long value
				try {
					//Translate to Meters
					value = Long.valueOf(dataContent) / 1000;
					mDodDistanceValue = value;
					mDodLastUpdateAt = System.currentTimeMillis();
					Log.v(TAG, "Value in m=" + mDodDistanceValue);

				} catch (Exception e) {
					Log.v(TAG, "Caught exception: Not a long value >>>" + dataContent + "<<<");
					message = "Not a number >>>" + dataContent + "<<<";
				}
			}
			// Message
			else if (dataType == AppService.Constants.Datatypes.MESSAGE.charAt(0)) {
				Log.v(TAG, "Received message: " + dataContent);
				message = dataContent;
			}

			//Unknown type
			else {
				Log.v(TAG, "Received value with unknown type: " + dataContent);
				message = "Unknown value:" + dataContent;
			}
		}

		// Invalid message
		else {
			Log.v(TAG, "Received invalid message >>>" + data + "<<<");
			message = "Invalid message:" + data;
		}

		if (message != null) {
//			messageBox.add(message); TODO
		}

		updateUI();
	}

	/**
	 * Call this every time a UI value has changed. Also updates the menu.
	 */

	private void updateUI() {

		invalidateOptionsMenu();

		if (mDemo) {
			mDeviceNameView.setText(R.string.deviceNameDummy);
		} else {
			mDeviceNameView.setText(R.string.deviceNameDDO);
		}


		distanceValueView.setText(String.valueOf(mDodDistanceValue / 1000));
		distanceMeterValueView.setText(String.valueOf(mDodDistanceValue % 1000));
		lastUpdateAtView.setText("" + sdf.format(new Date(mDodLastUpdateAt)));
//		messageView.setText(messageBox.asString());

	}

	/** There are four steps for scanning devices. */
	private enum Scanmode {
		/** While preparing, BT will be switched on */
		PREPARING_BT(1),

		/** Scan mode switched on,but not started */
		SWITCH_ON(2),

		/** Service is scanning */
		SCANNING(3),

		/** Scanning not active */
		OFF(0);

		private int mode;

		Scanmode(int mode) {
			this.mode = mode;
		}

		public int getValue() {
			return mode;
		}

		public static Scanmode create(int mode) {
			switch (mode) {
				case 1:
					return Scanmode.PREPARING_BT;
				case 2:
					return Scanmode.SWITCH_ON;
				case 3:
					return Scanmode.SCANNING;
				default:
					return Scanmode.OFF;
			}
		}

	}

	;

	/**
	 * Will be called after switching Android's BT. Don't start scanning here, because onResume() will
	 * be called after later.
	 */
	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		Log.v(TAG, "onActivityResult()");
		// User chose not to enable Bluetooth.
		if (requestCode == Constants.Bluetooth.REQUEST_ENABLE_BT) {
			if (resultCode == Activity.RESULT_CANCELED) {
				Toast.makeText(this, R.string.messageBluetoothNotOn, Toast.LENGTH_SHORT).show();
				mScanning = Scanmode.OFF;
				updateUI();
			} else {
				mScanning = Scanmode.SWITCH_ON;
				startScanning();
				updateUI();
			}
			return;
		}
		super.onActivityResult(requestCode, resultCode, data);
	}

	@Override
	protected void onStop() {
		super.onStop();
		Log.v(TAG, "onStop()");
		// We need an Editor object to make preference changes.
		// All objects are from android.context.Context
		SharedPreferences.Editor editor = getSharedPreferences(Constants.SharedPrefs.NAME, 0).edit();
		editor.putLong(Constants.SharedPrefs.DISTANCE, mDodDistanceValue);
		editor.putLong(Constants.SharedPrefs.LAST_UPDATE_AT, mDodLastUpdateAt);
		editor.putBoolean(Constants.SharedPrefs.DEMO, mDemo);
		editor.putInt(Constants.SharedPrefs.SCANNING, mScanning.getValue());
		editor.apply();


		//Wenn hier disconnected, muss man in onResume wieder connecten !?
		if (mService != null) {
			mService.disconnect();
		}

		unregisterReceiver(mBroadcastReceiver);
	}


	@Override
	protected void onDestroy() {
		super.onDestroy();

		//TEstweise nach onPause verschoben
		unbindService(mServiceConnection);
		mService = null;
	}

	@Override
	protected void onStart() {
		super.onStart();
		Log.v(TAG, "onStart()");

		//Restore values from previous run
		mDodDistanceValue = getSharedPreferences(Constants.SharedPrefs.NAME, 0).getLong(Constants.SharedPrefs.DISTANCE, 0);
		mDodLastUpdateAt = getSharedPreferences(Constants.SharedPrefs.NAME, 0).getLong(Constants.SharedPrefs.LAST_UPDATE_AT, 0);
		mDemo = getSharedPreferences(Constants.SharedPrefs.NAME, 0).getBoolean(Constants.SharedPrefs.DEMO, true);
		mScanning = Scanmode.create(getSharedPreferences(Constants.SharedPrefs.NAME, 0).getInt(Constants.SharedPrefs.SCANNING, 1));

		// Ensures Bluetooth is enabled on the device.  If Bluetooth is not currently enabled,
		// fire an intent to display a dialog asking the user to grant permission to enable it.

		//Broadcast receiver is our service listener
		registerReceiver(mBroadcastReceiver, makeGattUpdateIntentFilter());

		if (mService != null) {
			startScanning();
		}
		updateUI();
	}

	/**
	 * Call this to switch to scanning mode. UI will not be updated.
	 */
	private void startScanning() {
		Log.v(TAG, "startScanning()");


		if (!mDemo && mBluetoothAdapter.getState() == BluetoothAdapter.STATE_OFF) {
			Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
			startActivityForResult(enableBtIntent, Constants.Bluetooth.REQUEST_ENABLE_BT);
		} else if (mService != null) {
			mService.scanDevice(mDemo);
		}

	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		distanceValueView = (TextView) findViewById(R.id.distanceValue);
		distanceMeterValueView = (TextView) findViewById(R.id.distanceMeterValue);
		connectedToView = (TextView) findViewById(R.id.connectedToValue);
		lastUpdateAtView = (TextView) findViewById(R.id.lastUpdateValue);
		messageView = (TextView) findViewById(R.id.messageValue);
		mDeviceNameView = (TextView) findViewById(R.id.deviceNameValue);


		mHandler = new Handler();

		// Use this check to determine whether BLE is supported on the device.  Then you can
		// selectively disable BLE-related features.
		if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
			Toast.makeText(this, R.string.msgBleNotSupported, Toast.LENGTH_SHORT).show();
			finish();
		}

		// Initializes a Bluetooth adapter.  For API level 18 and above, get a reference to
		// BluetoothAdapter through BluetoothManager.
		final BluetoothManager bluetoothManager =
				(BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
		mBluetoothAdapter = bluetoothManager.getAdapter();

		// Checks if Bluetooth is supported on the device.
		if (mBluetoothAdapter == null) {
			Toast.makeText(this, R.string.msgBluetoothNotSupported, Toast.LENGTH_SHORT).show();
			return;
		}

		//Verschoben nach onResume()
//		Log.v(TAG,"call bindService()....");
		Intent gattServiceIntent = new Intent(this, AppService.class);
//		Log.v(TAG, "... With result="+
		bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);


	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.menu_main, menu);

		if (mScanning.equals(Scanmode.SCANNING)) {
//			menu.findItem(R.id.menu_scan).setEnabled(false);
//			menu.findItem(R.id.menu_demo).setEnabled(false);
			menu.findItem(R.id.menu_refresh).setActionView(
					R.layout.actionbar_indeterminate_progress);
		} else {
//			menu.findItem(R.id.menu_scan).setEnabled(true);
//			menu.findItem(R.id.menu_demo).setEnabled(true);
			menu.findItem(R.id.menu_refresh).setActionView(null);
		}

		if (mDemo) {
//			menu.findItem(R.id.menu_scan).setEnabled(false);
			menu.findItem(R.id.menu_demo).setTitle(R.string.menuDemoActive);

		} else {
			menu.findItem(R.id.menu_scan).setEnabled(true);
			menu.findItem(R.id.menu_demo).setTitle(R.string.menuDemo);
		}

		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {

			case R.id.menu_scan:
				mService.disconnect();
				startScanning();
				updateUI();
				break;

			case R.id.menu_demo:
				mDodDistanceValue = 0;
				mDodLastUpdateAt = 0;
				mHandler.removeCallbacks(mRunnableValueReadJob);
				if (mDemo) {
					mDemo = false;
				} else {
					mDemo = true;
				}
				startScanning();
				updateUI();
				break;
		}

		return true;
	}


	//Structure for all used Constants
	private abstract class Constants {
		abstract class SharedPrefs {
			static final String NAME = "DOD";
			static final String DISTANCE = "DISTANCE";
			static final String LAST_UPDATE_AT = "LAST_UPDATE_AT";
			static final String MESSAGES = "MESSAGES";
			static final String DEMO = "DEMO";
			static final String SCANNING = "SCANNING";
		}

		abstract class Bluetooth {

			//ID for BT switch on intent
			static final int REQUEST_ENABLE_BT = 1;
		}


	}

}
