package de.egh.dynamodrivenodometer;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;
import android.widget.Toast;

import de.egh.dynamodrivenodometer.Exceptions.EghBluetoothLeNotSupported;
import de.egh.dynamodrivenodometer.Exceptions.EghBluetoothNotSupported;

/**
 * Manage connection to DDO device with all the bluetooth stuff.
 */
public class DDOFascade implements DeviceFacade {

	private BluetoothAdapter mBluetoothAdapter;

	/**Context is needed for Bluetooth and broadcasts*/
	private Context mContext;

	private static final String TAG = DDOFascade.class.getSimpleName();

	public DDOFascade(Context context) throws EghBluetoothNotSupported, EghBluetoothLeNotSupported{
		this.mContext = context;

		mBluetoothAdapter = ((BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();

		// Checks if Bluetooth is supported on the device.
		if (mBluetoothAdapter == null) {
			//Toast.makeText(this, R.string.msgBluetoothNotSupported, Toast.LENGTH_SHORT).show();
			//finish();
			throw new EghBluetoothNotSupported();
		}

		// Use this check to determine whether BLE is supported on the device.  Then you can
		// selectively disable BLE-related features.
		if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
			throw new EghBluetoothLeNotSupported();
		}
	}

	public boolean connect(final String address) {
		return false;
	}


	@Override
	public DeviceType getDeviceType() {
		return DeviceType.DDO;
	}

	@Override
	public void scanDevice(boolean enable) {
		Log.v(TAG, "scanLeDevice() " + enable);

//		//Switching on
//		if (enable) {
//			if (mService != null) {
//				mService.close();
//			}
//			mRunnableStopScan = new Runnable() {
//				@Override
//				public void run() {
//					if (mScanning) {
//						Log.v(TAG, "End of scan period: Stopping scan.");
//						mScanning = false;
//						mBluetoothAdapter.stopLeScan(mLeScanCallback);
//						mConnectedToView.setText(getString(R.string.dodConnectedFalse));
//						mConnectSwitch.setChecked(false);
//					}
//				}
//			};
//			// Stops scanning after a pre-defined scan period.
//			mHandler.postDelayed(mRunnableStopScan, MyActivity.Constants.Bluetooth.SCAN_PERIOD);
//
//			mScanning = true;
//			mConnectedToView.setText(getString(R.string.dodConnectedScanning));
//			mBluetoothAdapter.startLeScan(mLeScanCallback);
//
//
//		}
//
//		//Switching off
//		else {
//			stopLeDeviceScan();
//		}
	}
}
