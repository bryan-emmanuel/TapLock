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

import static com.piusvelte.taplock.client.core.TapLock.KEY_ADDRESS;
import static com.piusvelte.taplock.client.core.TapLock.KEY_NAME;
import static com.piusvelte.taplock.client.core.TapLock.KEY_PASSPHRASE;
import static com.piusvelte.taplock.client.core.TapLock.EXTRA_DEVICE_ADDRESS;
import static com.piusvelte.taplock.client.core.TapLock.EXTRA_DEVICE_NAME;
import static com.piusvelte.taplock.client.core.TapLock.ACTION_PASSPHRASE;
import static com.piusvelte.taplock.client.core.TapLock.ACTION_TOGGLE;
import static com.piusvelte.taplock.client.core.TapLock.PARAM_ACTION;
import static com.piusvelte.taplock.client.core.TapLock.PARAM_CHALLENGE;
import static com.piusvelte.taplock.client.core.TapLock.PARAM_ERROR;
import static com.piusvelte.taplock.client.core.TapLock.PARAM_HMAC;
import static com.piusvelte.taplock.client.core.TapLock.PARAM_PASSPHRASE;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.json.JSONException;
import org.json.JSONObject;

import android.app.PendingIntent;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.widget.RemoteViews;

public class TapLockService extends Service implements OnSharedPreferenceChangeListener {
	private static final String TAG = "TapLockService";
	private BluetoothAdapter mBtAdapter;
	private ConnectThread mConnectThread;
	private String mQueueAddress;
	private String mQueueState;
	private String mQueuePassphrase;
	private boolean mRequestDiscovery = false;
	private boolean mStartedBT = false;
	private boolean mDeviceFound = false;
	private ArrayList<JSONObject> mDevices = new ArrayList<JSONObject>();
	private static final UUID sTapLockUUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
	private int[] mThreadLock = new int[0];
	private static final int MAX_CONNECTION_ATTEMPTS = 4;
	private Handler mHandler = new Handler();

	private ITapLockUI mUIInterface;
	private final ITapLockService.Stub mServiceInterface = new ITapLockService.Stub() {

		@Override
		public void setCallback(IBinder uiBinder) throws RemoteException {
			if (uiBinder != null)
				mUIInterface = ITapLockUI.Stub.asInterface(uiBinder);
		}

		@Override
		public void write(String address, String action, String passphrase) throws RemoteException {
			requestWrite(address, action, passphrase);
		}

		@Override
		public void requestDiscovery() throws RemoteException {
			mRequestDiscovery = true;
			if (mBtAdapter.isEnabled()) {
				if (mBtAdapter.isDiscovering())
					mBtAdapter.cancelDiscovery();
				mBtAdapter.startDiscovery();
			} else {
				mStartedBT = true;
				mBtAdapter.enable();
			}
		}

		@Override
		public void stop() throws RemoteException {
			// stop the service if there's no activity
			if (mConnectThread == null)
				stopSelf();
			// when the connectthread stops, it will stop the service
			mUIInterface = null;
		}

		@Override
		public void pairDevice(String address) throws RemoteException {
			requestWrite(address, null, null);
		}

		@Override
		public void enableBluetooth() throws RemoteException {
			mStartedBT = true;
			mBtAdapter.enable();
		}
	};

	@Override
	public void onCreate() {
		super.onCreate();
		onSharedPreferenceChanged(getSharedPreferences(getString(R.string.key_preferences), Context.MODE_PRIVATE), getString(R.string.key_devices));
		mBtAdapter = BluetoothAdapter.getDefaultAdapter();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if (intent != null) {
			String action = intent.getAction();
			if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
				int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF);
				if (state == BluetoothAdapter.STATE_ON) {
					if (mUIInterface != null) {
						try {
							mUIInterface.setMessage("Bluetooth enabled");
						} catch (RemoteException e) {
							Log.e(TAG, e.getMessage());
						}
					}
					// if pending...
					if (mStartedBT) {
						if ((mQueueAddress != null) && (mQueueState != null))
							requestWrite(mQueueAddress, mQueueState, mQueuePassphrase);
						else if (mRequestDiscovery && !mBtAdapter.isDiscovering())
							mBtAdapter.startDiscovery();
						else if (mUIInterface != null) {
							try {
								mUIInterface.setBluetoothEnabled();
							} catch (RemoteException e) {
								Log.e(TAG, e.getMessage());
							}
						}
					}
				} else if (state == BluetoothAdapter.STATE_TURNING_OFF) {
					if (mUIInterface != null) {
						try {
							mUIInterface.setMessage("Bluetooth disabled");
						} catch (RemoteException e) {
							Log.e(TAG, e.getMessage());
						}
					}
					stopThreads();
				}
			} else if (BluetoothDevice.ACTION_FOUND.equals(action)) {
				// Get the BluetoothDevice object from the Intent
				BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
				if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
					// connect if configured
					String address = device.getAddress();
					for (JSONObject deviceJObj : mDevices) {
						try {
							if (deviceJObj.getString(KEY_ADDRESS).equals(address)) {
								// if queued
								mDeviceFound = (mQueueAddress != null) && mQueueAddress.equals(address) && (mQueueState != null);
								break;
							}
						} catch (JSONException e) {
							Log.e(TAG, e.getMessage());
						}
					}
				} else if (mRequestDiscovery && (mUIInterface != null)) {
					try {
						mUIInterface.setUnpairedDevice(device.getName() + " " + device.getAddress());
					} catch (RemoteException e) {
						Log.e(TAG, e.toString());
					}
				}
			} else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
				if (mDeviceFound) {
					requestWrite(mQueueAddress, mQueueState, mQueuePassphrase);
					mDeviceFound = false;
				} else if (mRequestDiscovery) {
					mRequestDiscovery = false;
					if (mUIInterface != null) {
						try {
							mUIInterface.setDiscoveryFinished();
						} catch (RemoteException e) {
							Log.e(TAG, e.toString());
						}
					}
				}
			} else if (ACTION_TOGGLE.equals(action) && intent.hasExtra(EXTRA_DEVICE_ADDRESS)) {
				String address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS);
				requestWrite(address, ACTION_TOGGLE, mQueuePassphrase);
			} else if (AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(action)) {
				// create widget
				AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(this);
				SharedPreferences sp = getSharedPreferences(getString(R.string.key_preferences), MODE_PRIVATE);
				Set<String> widgets = sp.getStringSet(getString(R.string.key_widgets), (new HashSet<String>()));
				if (intent.hasExtra(AppWidgetManager.EXTRA_APPWIDGET_ID)) {
					int appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
					// check if the widget exists, otherwise add it
					if (intent.hasExtra(EXTRA_DEVICE_NAME) && intent.hasExtra(EXTRA_DEVICE_ADDRESS)) {
						Set<String> newWidgets = new HashSet<String>();
						for (String widget : widgets)
							newWidgets.add(widget);
						String name = intent.getStringExtra(EXTRA_DEVICE_NAME);
						String address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS);
						String widgetString = name + " " + Integer.toString(appWidgetId) + " " + address;
						// store the widget
						if (!newWidgets.contains(widgetString))
							newWidgets.add(widgetString);
						SharedPreferences.Editor spe = sp.edit();
						spe.putStringSet(getString(R.string.key_widgets), newWidgets);
						spe.commit();
					}
					appWidgetManager.updateAppWidget(appWidgetId, buildWidget(intent, appWidgetId, widgets));
				} else if (intent.hasExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)) {
					int[] appWidgetIds = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS);
					for (int appWidgetId : appWidgetIds)
						appWidgetManager.updateAppWidget(appWidgetId, buildWidget(intent, appWidgetId, widgets));
				}
			} else if (AppWidgetManager.ACTION_APPWIDGET_DELETED.equals(action)) {
				int appWidgetId = intent.getExtras().getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
				SharedPreferences sp = getSharedPreferences(getString(R.string.key_preferences), MODE_PRIVATE);
				Set<String> widgets = sp.getStringSet(getString(R.string.key_widgets), (new HashSet<String>()));
				Set<String> newWidgets = new HashSet<String>();
				for (String widget : widgets) {
					JSONObject deviceJObj;
					try {
						deviceJObj = new JSONObject(widget);
						if (!deviceJObj.getString(KEY_PASSPHRASE).equals(Integer.toString(appWidgetId)))
							newWidgets.add(widget);
					} catch (JSONException e) {
						Log.e(TAG, e.getMessage());
					}
				}
				SharedPreferences.Editor spe = sp.edit();
				spe.putStringSet(getString(R.string.key_widgets), newWidgets);
				spe.commit();
			}
		}
		return START_STICKY;
	}

	@Override
	public IBinder onBind(Intent arg0) {
		return mServiceInterface;
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		stopThreads();
		if (mStartedBT) {
			mStartedBT = false;
			mBtAdapter.disable();
		}
	}

	protected static String getHashString(String str) throws NoSuchAlgorithmException, UnsupportedEncodingException {
		MessageDigest md = MessageDigest.getInstance("SHA-256");
		md.update(str.getBytes("UTF-8"));
		StringBuffer hexString = new StringBuffer();
		byte[] hash = md.digest();
		for (byte b : hash) {
			if ((0xFF & b) < 0x10)
				hexString.append("0" + Integer.toHexString((0xFF & b)));
			else
				hexString.append(Integer.toHexString(0xFF & b));
		}
		return hexString.toString();
	}

	private RemoteViews buildWidget(Intent intent, int appWidgetId, Set<String> widgets) {
		RemoteViews views = new RemoteViews(getPackageName(), R.layout.widget);
		if (intent.hasExtra(EXTRA_DEVICE_NAME)) {
			views.setTextViewText(R.id.device_name, intent.getStringExtra(EXTRA_DEVICE_NAME));
		} else {
			String name = getString(R.string.widget_device_name);
			for (String widget : widgets) {
				JSONObject deviceJObj;
				try {
					deviceJObj = new JSONObject(widget);
					if (deviceJObj.getString(KEY_PASSPHRASE).equals(Integer.toString(appWidgetId))) {
						name = deviceJObj.getString(KEY_NAME);
						break;
					}
				} catch (JSONException e) {
					Log.e(TAG, e.getMessage());
				}
			}
			views.setTextViewText(R.id.device_name, name);
		}
		if (intent.hasExtra(EXTRA_DEVICE_ADDRESS))
			views.setOnClickPendingIntent(R.id.widget, PendingIntent.getBroadcast(this, 0, TapLock.getPackageIntent(this, TapLockWidget.class).setAction(ACTION_TOGGLE).putExtra(EXTRA_DEVICE_ADDRESS, intent.getStringExtra(EXTRA_DEVICE_ADDRESS)), 0));
		return views;
	}

	private void requestWrite(String address, String action, String passphrase) {
		if (mBtAdapter.isEnabled()) {
			synchronized (mThreadLock) {
				if (mConnectThread != null)
					mConnectThread.shutdown();
				// attempt connect
				mConnectThread = new ConnectThread(address, action, passphrase);
				mConnectThread.start();
			}
			mQueueAddress = null;
			mQueueState = null;
			mQueuePassphrase = null;
		} else {
			mQueueAddress = address;
			mQueueState = action;
			mQueuePassphrase = passphrase;
			mStartedBT = true;
			mBtAdapter.enable();
		}
	}

	private void stopThreads() {
		synchronized (mThreadLock) {
			if (mConnectThread != null)
				mConnectThread.shutdown();
		}
	}

	private class ConnectThread extends Thread {
		private String mAddress = null;
		private BluetoothSocket mSocket = null;
		private InputStream inStream;
		private OutputStream outStream;
		private String mAction;
		private String mNewPassphrase = null;

		public ConnectThread(String address, String action, String newPassphrase) {
			mAction = action;
			mAddress = address;
			mNewPassphrase = newPassphrase;
			if (mNewPassphrase == null)
				mNewPassphrase = "";
		}

		public void run() {
			boolean pass = false;
			mBtAdapter.cancelDiscovery();
			BluetoothDevice device = mBtAdapter.getRemoteDevice(mAddress);
			mHandler.post(new MessageSetter("connecting..."));
			int connectionAttempt;
			for (connectionAttempt = 0; connectionAttempt < MAX_CONNECTION_ATTEMPTS; connectionAttempt++) {
				try {
					mSocket = device.createRfcommSocketToServiceRecord(sTapLockUUID);
					mSocket.connect();
				} catch (IOException e) {
					mSocket = null;
				}
				if (mSocket != null) {
					if (mAction == null)
						mHandler.post(new PairingResultSetter(device.getName(), mAddress));
					else {
						// Get the BluetoothSocket input and output streams
						try {
							inStream = mSocket.getInputStream();
							outStream = mSocket.getOutputStream();
						} catch (IOException e) {
							mHandler.post(new MessageSetter("...failed to get streams: " + e.getMessage()));
							shutdown(pass);
							return;
						}
						byte[] buffer = new byte[1024];
						int readBytes = -1;
						try {
							readBytes = inStream.read(buffer);
						} catch (IOException e) {
							mHandler.post(new MessageSetter("...failed to read input stream: " + e.getMessage()));
						}
						if (readBytes != -1) {
							// construct a string from the valid bytes in the buffer
							String responseStr = new String(buffer, 0, readBytes);
							JSONObject responseJObj = null;
							String challenge = null;
							try {
								responseJObj = new JSONObject(responseStr);
								if (responseJObj.has(PARAM_CHALLENGE))
									challenge = responseJObj.getString(PARAM_CHALLENGE);
							} catch (JSONException e) {
								mHandler.post(new MessageSetter("...failed to parse response: " + responseStr + ", " + e.getMessage()));
							}
							if (challenge != null) {
								// get passphrase
								String passphrase = null;
								for (JSONObject deviceJObj : mDevices) {
									try {
										if (deviceJObj.getString(KEY_ADDRESS).equals(mAddress)) {
											if ((passphrase = deviceJObj.getString(KEY_PASSPHRASE)) != null) {
												try {
													JSONObject requestJObj = new JSONObject();
													try {
														requestJObj.put(PARAM_ACTION, mAction);
														if (ACTION_PASSPHRASE.equals(mAction))
															requestJObj.put(PARAM_PASSPHRASE, mNewPassphrase);
														requestJObj.put(PARAM_HMAC, getHashString(challenge + passphrase + mAction + mNewPassphrase));
														String requestStr = requestJObj.toString();
														byte[] requestBytes = requestStr.getBytes();
														outStream.write(requestBytes);
														if (ACTION_PASSPHRASE.equals(mAction))
															mHandler.post(new PassphraseSetter(mAddress, mNewPassphrase));
														pass = true;
													} catch (JSONException e) {
														mHandler.post(new MessageSetter("...failed to build request: " + e.getMessage()));
													}
												} catch (NoSuchAlgorithmException e) {
													mHandler.post(new MessageSetter("...failed to get hash string: " + e.getMessage()));
												} catch (UnsupportedEncodingException e) {
													mHandler.post(new MessageSetter("...failed to get hash string: " + e.getMessage()));
												} catch (IOException e) {
													mHandler.post(new MessageSetter("...failed to write to output stream: " + e.getMessage()));
												}
											}
											break;
										}
									} catch (JSONException e) {
										Log.e(TAG, e.getMessage());
									}
								}
								if (passphrase == null)
									mHandler.post(new MessageSetter("...no passphrase found for device"));
							} else
								mHandler.post(new MessageSetter("...failed to receive a challenge"));
							// check for error messages
							try {
								readBytes = inStream.read(buffer);
							} catch (IOException e) {
								readBytes = -1;
							}
							if (readBytes != -1) {
								responseStr = new String(buffer, 0, readBytes);
								String error = null;
								try {
									responseJObj = new JSONObject(responseStr);
									if (responseJObj.has(PARAM_ERROR)) {
										pass = false;
										error = responseJObj.getString(PARAM_ERROR);
									}
								} catch (JSONException e) {
									responseJObj = null;
								}
								if (error != null)
									mHandler.post(new MessageSetter("error: " + error));
							}
						} else
							mHandler.post(new MessageSetter("...failed to read input stream"));
					}
					break;
				}
			}
			if (connectionAttempt == MAX_CONNECTION_ATTEMPTS)
				mHandler.post(new MessageSetter("...device unavailable."));
			shutdown(pass);
		}

		// convenience method for shutting down thread
		public void shutdown() {
			shutdown(true);
		}

		public void shutdown(boolean pass) {
			if (inStream != null) {
				try {
					inStream.close();
				} catch (IOException e) {
					Log.e(TAG, e.toString());
				}
				inStream = null;
			}
			if (outStream != null) {
				try {
					outStream.close();
				} catch (IOException e) {
					Log.e(TAG, e.toString());
				}
				outStream = null;
			}
			if (mSocket != null) {
				try {
					mSocket.close();
				} catch (IOException e) {
					Log.e(TAG, e.toString());
				}
				mSocket = null;
			}
			mConnectThread = null;
			mHandler.post(new StateFinishedSetter(pass));
		}
	}

	@Override
	public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
		if (key.equals(getString(R.string.key_devices))) {
			Set<String> devices = sharedPreferences.getStringSet(getString(R.string.key_devices), null);
			if (devices != null) {
				for (String device : devices) {
					try {
						mDevices.add(new JSONObject(device));
					} catch (JSONException e) {
						Log.e(TAG, e.toString());
					}
				}
			}
		}
	}

	class MessageSetter implements Runnable {

		String mMessage = null;

		public MessageSetter(String message) {
			mMessage = message;
		}

		@Override
		public void run() {
			Log.d(TAG, "Message: " + mMessage);
			if (mUIInterface != null) {
				try {
					mUIInterface.setMessage(mMessage);
				} catch (RemoteException e) {
					Log.e(TAG, e.getMessage());
				}
			}
		}		
	}

	class PairingResultSetter implements Runnable {

		String mName = null;
		String mAddress = null;

		public PairingResultSetter(String name, String address) {
			mName = name;
			mAddress = address;
		}

		@Override
		public void run() {
			if (mUIInterface != null) {
				try {
					mUIInterface.setPairingResult(mName, mAddress);
				} catch (RemoteException e) {
					Log.e(TAG, e.getMessage());
				}
			}
		}
	}

	class PassphraseSetter implements Runnable {

		String mAddress = null;
		String mPassphrase = null;

		public PassphraseSetter(String address, String passphrase) {
			mAddress = address;
			mPassphrase = passphrase;
		}

		@Override
		public void run() {
			if (mUIInterface != null) {
				try {
					mUIInterface.setPassphrase(mAddress, mPassphrase);
				} catch (RemoteException e) {
					Log.e(TAG, e.getMessage());
				}
			}
		}
	}

	class StateFinishedSetter implements Runnable {

		boolean mPass = false;

		public StateFinishedSetter(boolean pass) {
			mPass = pass;
		}

		@Override
		public void run() {
			if (mUIInterface != null) {
				try {
					mUIInterface.setStateFinished(mPass);
				} catch (RemoteException e) {
					Log.e(TAG, e.getMessage());
				}
			} else
				stopSelf();
		}
	}
}
