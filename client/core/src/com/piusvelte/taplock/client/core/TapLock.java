/*
 * TapLock
 * Copyright (C) 2012 Bryan Emmanuel
 * 
 * This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *  
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *  
 *  Bryan Emmanuel piusvelte@gmail.com
 */
package com.piusvelte.taplock.client.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

public class TapLock {
	
	private static final String TAG = "TapLock";
	public static final String PRO = "pro";
	protected static final String GOOGLE_AD_ID = "a1507b536d5668e";
	public static final String ACTION_TOGGLE = "com.piusvelte.taplock.ACTION_TOGGLE";
	public static final String ACTION_UNLOCK = "com.piusvelte.taplock.ACTION_UNLOCK";
	public static final String ACTION_LOCK = "com.piusvelte.taplock.ACTION_LOCK";
	public static final String ACTION_PASSPHRASE = "com.piusvelte.taplock.ACTION_PASSPHRASE";
	public static final String ACTION_TAG = "com.piusvelte.taplock.ACTION_TAG";
	public static final String ACTION_REMOVE = "com.piusvelte.taplock.ACTION_REMOVE";
	public static final String ACTION_DOWNLOAD_SDCARD = "com.piusvelte.taplock.ACTION_DOWNLOAD_SDCARD";
	public static final String ACTION_DOWNLOAD_EMAIL = "com.piusvelte.taplock.ACTION_DOWNLOAD_EMAIL";
	public static final String ACTION_COPY_DEVICE_URI = "com.piusvelte.taplock.ACTION_COPY_DEVICE_URI";
	public static final String EXTRA_DEVICE_ADDRESS = "com.piusvelte.taplock.EXTRA_DEVICE_ADDRESS";
	public static final String EXTRA_DEVICE_NAME = "com.piusvelte.taplock.EXTRA_DEVICE_NAME";
	public static final String EXTRA_INFO = "com.piusvelte.taplock.EXTRA_INFO";
	public static final String PARAM_ACTION = "action";
	public static final String PARAM_HMAC = "hmac";
	public static final String PARAM_PASSPHRASE = "passphrase";
	public static final String PARAM_CHALLENGE = "challenge";
	public static final String PARAM_ERROR = "error";
	public static final String KEY_NAME = "name";
	public static final String KEY_PASSPHRASE = "passphrase";
	public static final String KEY_ADDRESS = "address";
	public static final String KEY_WIDGETS = "widgets";
	public static final String KEY_DEVICES = "devices";
	public static final String KEY_PREFS = "taplock";
	public static final String DEFAULT_PASSPHRASE = "TapLock";
	
	private TapLock() {}
	
	@SuppressWarnings("rawtypes")
	protected static Class getPackageClass(Context context, Class cls) {
		try {
			return Class.forName(context.getPackageName() + "." + cls.getSimpleName());
		} catch (ClassNotFoundException e) {
			Log.e(TAG, e.getMessage());
		}
		return cls;
	}
	
	@SuppressWarnings("rawtypes")
	protected static Intent getPackageIntent(Context context, Class cls) {
		return new Intent(context, getPackageClass(context, cls));
	}
	
	protected static void storeDevices(SharedPreferences sp, ArrayList<JSONObject> devicesArr) {
		Set<String> devices = new HashSet<String>();
		for (JSONObject deviceJObj : devicesArr)
			devices.add(deviceJObj.toString());
		sp.edit().putStringSet(KEY_DEVICES, devices).commit();
	}

	protected static String[] getDeviceNames(ArrayList<JSONObject> devices) {
		String[] deviceNames = new String[devices.size()];
		int d = 0;
		for (JSONObject device : devices) {
			try {
				deviceNames[d++] = device.getString(KEY_NAME);
			} catch (JSONException e) {
				Log.e(TAG, e.toString());
			}
		}
		return deviceNames;
	}
	
	public static JSONObject createDevice(String name, String address, String passphrase) {
		JSONObject deviceJObj = new JSONObject();
		try {
			deviceJObj.put(KEY_NAME, name);
			deviceJObj.put(KEY_ADDRESS, address);
			deviceJObj.put(KEY_PASSPHRASE, passphrase);
			deviceJObj.put(KEY_WIDGETS, new JSONArray());
		} catch (JSONException e) {
			e.printStackTrace();
		}
		return deviceJObj;
	}

}
