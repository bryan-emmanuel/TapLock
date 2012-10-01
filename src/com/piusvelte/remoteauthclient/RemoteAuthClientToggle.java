/*
 * RemoteAuthClient
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
package com.piusvelte.remoteauthclient;

import static com.piusvelte.remoteauthclient.RemoteAuthClientUI.DEVICE_ADDRESS;
import static com.piusvelte.remoteauthclient.RemoteAuthClientUI.STATE_LOCK;
import static com.piusvelte.remoteauthclient.RemoteAuthClientUI.STATE_TOGGLE;
import static com.piusvelte.remoteauthclient.RemoteAuthClientUI.STATE_UNLOCK;

import java.io.UnsupportedEncodingException;
import java.util.Iterator;
import java.util.Set;

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

public class RemoteAuthClientToggle extends Activity implements ServiceConnection {
	private static final String TAG = "RemoteAuthClientUI";
	private TextView mInfo;
	private String[] mDevices;

	private IRemoteAuthClientService mServiceInterface;
	private IRemoteAuthClientUI.Stub mUIInterface = new IRemoteAuthClientUI.Stub() {

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
			RemoteAuthClientToggle.this.finish();
		}

		@Override
		public void setPairingResult(String device) throws RemoteException {
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
			mDevices = new String[devices.size()];
			int d = 0;
			Iterator<String> iter = devices.iterator();
			while (iter.hasNext()) {
				mDevices[d++] = iter.next();
			}
		} else {
			mDevices = new String[0];
		}
		// start the service before binding so that the service stays around for faster future connections
		startService(new Intent(this, RemoteAuthClientService.class));
		bindService(new Intent(this, RemoteAuthClientService.class), this, BIND_AUTO_CREATE);
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
		mServiceInterface = IRemoteAuthClientService.Stub.asInterface(binder);
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
							Log.d(TAG, "taggedDeviceName: " + taggedDeviceName);
							String[] parsedDevice = null;
							for (String device : mDevices) {
								String deviceName = RemoteAuthClientUI.parseDeviceString(device)[0];
								if (deviceName.equals(taggedDeviceName)) {
									parsedDevice = RemoteAuthClientUI.parseDeviceString(device);
									break;
								}
							}
							if (parsedDevice != null) {
								try {
									mServiceInterface.write(parsedDevice[DEVICE_ADDRESS], Integer.toString(STATE_TOGGLE));
								} catch (RemoteException e) {
									Log.e(TAG, e.toString());
								}
							}
						} catch (UnsupportedEncodingException e) {
							// should never happen unless we get a malformed tag.
							Log.e(TAG, e.toString());
						}
					}
				}
			} else if (intent.getData() != null) {
				Uri remoteCmd = intent.getData();
				String cmd = remoteCmd.getLastPathSegment();
				String taggedDeviceName = remoteCmd.getHost();
				if ((cmd != null) && (taggedDeviceName != null)) {
					String[] parsedDevice = null;
					for (String device : mDevices) {
						String deviceName = RemoteAuthClientUI.parseDeviceString(device)[0];
						if (deviceName.equals(taggedDeviceName)) {
							parsedDevice = RemoteAuthClientUI.parseDeviceString(device);
							break;
						}
					}
					if (parsedDevice != null) {
						String states[] = getResources().getStringArray(R.array.state_values);
						if (states[STATE_UNLOCK].equals(cmd))
							cmd = Integer.toString(STATE_UNLOCK);
						else if (states[STATE_LOCK].equals(cmd))
							cmd = Integer.toString(STATE_LOCK);
						else if (states[STATE_TOGGLE].equals(cmd))
							cmd = Integer.toString(STATE_TOGGLE);
						else
							cmd = null;
						if (cmd != null) {
							try {
								mServiceInterface.write(parsedDevice[DEVICE_ADDRESS], cmd);
							} catch (RemoteException e) {
								Log.e(TAG, e.toString());
							}
						}
					}
				}
			}
		}
	}

	@Override
	public void onServiceDisconnected(ComponentName arg0) {
		mServiceInterface = null;
	}
}