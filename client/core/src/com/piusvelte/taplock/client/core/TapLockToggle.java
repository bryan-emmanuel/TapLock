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

import static com.piusvelte.taplock.client.core.TapLockService.ACTION_LOCK;
import static com.piusvelte.taplock.client.core.TapLockService.ACTION_TOGGLE;
import static com.piusvelte.taplock.client.core.TapLockService.ACTION_UNLOCK;
import static com.piusvelte.taplock.client.core.TapLockService.KEY_ADDRESS;
import static com.piusvelte.taplock.client.core.TapLockService.KEY_NAME;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Set;

import org.json.JSONException;
import org.json.JSONObject;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcelable;
import android.os.RemoteException;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

public class TapLockToggle extends Activity implements ServiceConnection {
	private static final String TAG = "TapLockToggle";
	private TextView mInfo;
	private ArrayList<JSONObject> mDevices = new ArrayList<JSONObject>();

	private ITapLockService mServiceInterface;
	private ITapLockUI.Stub mUIInterface = new ITapLockUI.Stub() {

		@Override
		public void setMessage(String message) throws RemoteException {
			mInfo.append(message + "\n");
		}

		@Override
		public void setUnpairedDevice(String device) throws RemoteException {
		}

		@Override
		public void setDiscoveryFinished() throws RemoteException {
		}

		@Override
		public void setStateFinished() throws RemoteException {
			finish();
		}

		@Override
		public void setPairingResult(String name, String address) throws RemoteException {
		}

		@Override
		public void setPassphrase(String address, String passphrase) throws RemoteException {
		}

	};

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.toggle);
		mInfo = (TextView) findViewById(R.id.info);
	}

	@Override 
	public void onNewIntent(Intent intent) {
		// handle NFC intents
		setIntent(intent);
	}

	@Override
	protected void onResume() {
		super.onResume();
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
		// start the service before binding so that the service stays around for faster future connections
		startService(new Intent(this, TapLockService.class));
		bindService(new Intent(this, TapLockService.class), this, BIND_AUTO_CREATE);
	}

	@Override
	protected void onPause() {
		super.onPause();
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
							toggleDevice(taggedDeviceName);
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
				Uri remoteCmd = intent.getData();
				action = remoteCmd.getLastPathSegment();
				String taggedDeviceName = remoteCmd.getHost();
				if ((ACTION_UNLOCK.equals(action) || ACTION_LOCK.equals(action) || ACTION_TOGGLE.equals(action)) && (taggedDeviceName != null))
					toggleDevice(taggedDeviceName);
				else
					finish();
			} else
				finish();
		} else
			finish();
	}

	@Override
	public void onServiceDisconnected(ComponentName arg0) {
		mServiceInterface = null;
	}

	private void toggleDevice(String taggedDeviceName) {
		String address = null;
		for (JSONObject deviceJObj : mDevices) {
			try {
				if (deviceJObj.getString(KEY_NAME).equals(taggedDeviceName)) {
					address = deviceJObj.getString(KEY_ADDRESS);
					break;
				}
			} catch (JSONException e) {
				Log.e(TAG, e.toString());
			}
		}
		if (address != null) {
			try {
				mServiceInterface.write(address, ACTION_TOGGLE, null);
			} catch (RemoteException e) {
				Log.e(TAG, e.toString());
			}
		} else {
			Toast.makeText(getApplicationContext(), String.format(getString(R.string.msg_device_not_conf), taggedDeviceName), Toast.LENGTH_LONG).show();
			finish();
		}
	}
}