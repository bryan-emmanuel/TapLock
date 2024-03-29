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

import static com.piusvelte.taplock.client.core.TapLock.ACTION_LOCK;
import static com.piusvelte.taplock.client.core.TapLock.ACTION_TOGGLE;
import static com.piusvelte.taplock.client.core.TapLock.ACTION_UNLOCK;
import static com.piusvelte.taplock.client.core.TapLock.KEY_ADDRESS;
import static com.piusvelte.taplock.client.core.TapLock.KEY_NAME;
import static com.piusvelte.taplock.client.core.TapLock.KEY_DEVICES;
import static com.piusvelte.taplock.client.core.TapLock.KEY_PREFS;
import static com.piusvelte.taplock.client.core.TapLock.EXTRA_DEVICE_NAME;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Set;

import org.json.JSONException;
import org.json.JSONObject;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcelable;
import android.os.RemoteException;
import android.util.Log;
import android.widget.Toast;

public class TapLockToggle extends Activity implements ServiceConnection {
	private static final String TAG = "TapLockToggle";
	private ArrayList<JSONObject> mDevices = new ArrayList<JSONObject>();
	private ProgressDialog mProgressDialog;
	private String mProgressMessage = "";

	private ITapLockService mServiceInterface;
	private ITapLockUI.Stub mUIInterface = new ITapLockUI.Stub() {

		@Override
		public void setMessage(String message) throws RemoteException {
			if ((mProgressDialog != null) && mProgressDialog.isShowing()) {
				mProgressMessage += message + "\n";
				mProgressDialog.setMessage(mProgressMessage);
			}
		}

		@Override
		public void setUnpairedDevice(String device) throws RemoteException {
		}

		@Override
		public void setDiscoveryFinished() throws RemoteException {
		}

		@Override
		public void setStateFinished(boolean pass) throws RemoteException {
			if (pass)
				finish();
		}

		@Override
		public void setPairingResult(String name, String address) throws RemoteException {
		}

		@Override
		public void setPassphrase(String address, String passphrase) throws RemoteException {
		}

		@Override
		public void setBluetoothEnabled() throws RemoteException {
		}

	};

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.toggle);
	}

	@Override
	protected void onResume() {
		super.onResume();
		mProgressDialog = new ProgressDialog(this);
		mProgressDialog.setTitle(R.string.title_toggle);
		mProgressDialog.setMessage(mProgressMessage);
		mProgressDialog.setCancelable(true);
		mProgressDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
			@Override
			public void onCancel(DialogInterface dialog) {
				finish();
			}
		});
		mProgressDialog.setButton(getString(R.string.close), new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				if (mServiceInterface != null) {
					try {
						mServiceInterface.cancelRequest();
					} catch (RemoteException e) {
						Log.e(TAG, e.toString());
					}
				}
				dialog.cancel();
			}
		});
		mProgressDialog.show();
		mDevices.clear();
		final SharedPreferences sp = getSharedPreferences(KEY_PREFS, MODE_PRIVATE);
		Set<String> devices = sp.getStringSet(KEY_DEVICES, null);
		if (devices != null) {
			for (String device : devices) {
				try {
					mDevices.add(new JSONObject(device));
				} catch (JSONException e) {
					Log.e(TAG, e.toString());
				}
			}
		}
		// start the service before binding so that the service stays around for faster future connections
		startService(TapLock.getPackageIntent(this, TapLockService.class));
		bindService(TapLock.getPackageIntent(this, TapLockService.class), this, BIND_AUTO_CREATE);
	}

	@Override
	protected void onPause() {
		super.onPause();
		if ((mProgressDialog != null) && mProgressDialog.isShowing())
			mProgressDialog.cancel();
		if (mServiceInterface != null) {
			try {
				mServiceInterface.stop();
			} catch (RemoteException e) {
				Log.e(TAG, e.toString());
			}
			unbindService(this);
		}
	}

	@Override
	public void onServiceConnected(ComponentName name, IBinder binder) {
		mServiceInterface = ITapLockService.Stub.asInterface(binder);
		if (mUIInterface != null) {
			try {
				mServiceInterface.setCallback(mUIInterface);
			} catch (RemoteException e) {
				Log.e(TAG, e.toString());
			}
		}
		Intent intent = getIntent();
		if (intent != null) {
			String action = intent.getAction();
			if (NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action) && intent.hasExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)) {
				Log.d(TAG, "service connected, NDEF_DISCOVERED");
				Parcelable[] rawMsgs = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES);
				NdefMessage message;
				if (rawMsgs != null) {
					// process the first message
					message = (NdefMessage) rawMsgs[0];
					// process the first record
					NdefRecord record = message.getRecords()[0];
					if (record.getTnf() == NdefRecord.TNF_WELL_KNOWN) {
						try {
							byte[] payload = record.getPayload();
							/*
							 * payload[0] contains the "Status Byte Encodings" field, per the
							 * NFC Forum "Text Record Type Definition" section 3.2.1.
							 *
							 * bit7 is the Text Encoding Field.
							 *
							 * if (Bit_7 == 0): The text is encoded in UTF-8 if (Bit_7 == 1):
							 * The text is encoded in UTF16
							 *
							 * Bit_6 is reserved for future use and must be set to zero.
							 *
							 * Bits 5 to 0 are the length of the IANA language code.
							 */
							String textEncoding = ((payload[0] & 0200) == 0) ? "UTF-8" : "UTF-16";
							int languageCodeLength = payload[0] & 0077;
							String taggedDeviceName = new String(payload, languageCodeLength + 1, payload.length - languageCodeLength - 1, textEncoding);
							manageDevice(taggedDeviceName, ACTION_TOGGLE);
						} catch (UnsupportedEncodingException e) {
							// should never happen unless we get a malformed tag.
							Log.e(TAG, e.toString());
							finish();
						}
					} else
						finish();
				} else
					finish();
			} else if (intent.getData() != null) {
				String taggedDeviceName = intent.getData().getHost();
				if (taggedDeviceName != null)
					manageDevice(taggedDeviceName, ACTION_TOGGLE);
				else
					finish();
			} else if (ACTION_UNLOCK.equals(action) || ACTION_LOCK.equals(action) || ACTION_TOGGLE.equals(action))
				manageDevice(intent.getStringExtra(EXTRA_DEVICE_NAME), action);
			else
				finish();
		} else
			finish();
	}

	@Override
	public void onServiceDisconnected(ComponentName arg0) {
		mServiceInterface = null;
	}
	
	private void manageDevice(String name, String action) {
		String address = null;
		for (JSONObject deviceJObj : mDevices) {
			try {
				if (deviceJObj.getString(KEY_NAME).equals(name)) {
					address = deviceJObj.getString(KEY_ADDRESS);
					break;
				}
			} catch (JSONException e) {
				Log.e(TAG, e.toString());
			}
		}
		if (address != null) {
			try {
				mServiceInterface.write(address, action, null);
			} catch (RemoteException e) {
				Log.e(TAG, e.toString());
			}
		} else {
			Toast.makeText(getApplicationContext(), String.format(getString(R.string.msg_device_not_conf), name), Toast.LENGTH_LONG).show();
			finish();
		}
	}
}