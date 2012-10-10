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

import static com.piusvelte.taplock.client.core.TapLockService.ACTION_EDIT_SETTING;
import static com.piusvelte.taplock.client.core.TapLockService.KEY_ADDRESS;
import static com.piusvelte.taplock.client.core.TapLockService.KEY_NAME;

import java.util.ArrayList;
import java.util.Set;

import org.json.JSONException;
import org.json.JSONObject;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;

public class TapLockDevicePicker extends Activity {
	private static final String TAG = "TapLockDevicePicker";
	private ArrayList<JSONObject> mDevices = new ArrayList<JSONObject>();
	private AlertDialog mDialog;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.toggle);
	}

	@Override
	protected void onResume() {
		super.onResume();
		mDevices.clear();
		final SharedPreferences sp = getSharedPreferences(getString(R.string.key_preferences), Context.MODE_PRIVATE);
		Set<String> devices = sp.getStringSet(getString(R.string.key_devices), null);
		if (devices != null) {
			for (String device : devices) {
				try {
					mDevices.add(new JSONObject(device));
				} catch (JSONException e) {
					Log.e(TAG, e.toString());
				}
			}
		}
		final String[] displayNames = TapLockSettings.getDeviceNames(mDevices);
		mDialog = new AlertDialog.Builder(this)
		.setTitle("Select device for task")
		.setItems(displayNames, new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				JSONObject deviceJObj = mDevices.get(which);
				dialog.cancel();
			}
		})
		.create();
		mDialog.show();
	}

	@Override
	protected void onPause() {
		super.onPause();
		if (mDialog.isShowing())
			mDialog.cancel();
	}

}