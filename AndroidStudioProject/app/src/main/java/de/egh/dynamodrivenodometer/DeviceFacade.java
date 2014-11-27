package de.egh.dynamodrivenodometer;

/**
 * Used by Service to access devices.
 */
public interface DeviceFacade {

	/**Returns the device type.*/
	public DeviceType getDeviceType();

	/**Search for device an connect to it or, if FALSE, disconnect from device.*/
	public void scanDevice(final boolean enable);
}
