package de.egh.dynamodrivenodometer;

import android.content.Context;

/**
 * Created by ChristianSchulzendor on 26.11.2014.
 */
public class MockbikeFacade implements DeviceFacade {

	private boolean mConnected = false;

	/**Context is needed for Bluetooth and broadcasts*/
	private Context mContext;

	public MockbikeFacade(Context context){
		this.mContext = context;
	}

	@Override
	public DeviceType getDeviceType() {
		return DeviceType.MOCKBIKE;
	}

	@Override
	public void scanDevice(boolean enable) {
		mConnected = enable;
	}
}
