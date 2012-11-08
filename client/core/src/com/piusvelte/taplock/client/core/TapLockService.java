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
import static com.piusvelte.taplock.client.core.TapLock.KEY_WIDGETS;
import static com.piusvelte.taplock.client.core.TapLock.EXTRA_DEVICE_ADDRESS;
import static com.piusvelte.taplock.client.core.TapLock.EXTRA_DEVICE_NAME;
import static com.piusvelte.taplock.client.core.TapLock.ACTION_PASSPHRASE;
import static com.piusvelte.taplock.client.core.TapLock.ACTION_TOGGLE;
import static com.piusvelte.taplock.client.core.TapLock.PARAM_ACTION;
import static com.piusvelte.taplock.client.core.TapLock.PARAM_CHALLENGE;
import static com.piusvelte.taplock.client.core.TapLock.PARAM_ERROR;
import static com.piusvelte.taplock.client.core.TapLock.PARAM_HMAC;
import static com.piusvelte.taplock.client.core.TapLock.PARAM_PASSPHRASE;
import static com.piusvelte.taplock.client.core.TapLock.DEFAULT_PASSPHRASE;
import static com.piusvelte.taplock.client.core.TapLock.KEY_DEVICES;
import static com.piusvelte.taplock.client.core.TapLock.KEY_PREFS;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

import org.json.JSONArray;
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
import android.net.Uri;
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
	private boolean mRequestCanceled = true;
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

		@Override
		public void cancelRequest() throws RemoteException {
			setRequestCanceled(false);
		}
	};

	@Override
	public void onCreate() {
		super.onCreate();
		onSharedPreferenceChanged(getSharedPreferences(KEY_PREFS, Context.MODE_PRIVATE), KEY_DEVICES);
		mBtAdapter = BluetoothAdapter.getDefaultAdapter();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if (intent != null) {
			String action = intent.getAction();
			if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
				int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF);
				if (state == BluetoothAdapter.STATE_ON) {
					if (mStartedBT) {
						if (mUIInterface != null) {
							try {
								mUIInterface.setMessage("Bluetooth enabled");
							} catch (RemoteException e) {
								Log.e(TAG, e.getMessage());
							}
						}
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
					String unpairedDevice = TapLock.createDevice(device.getName(), device.getAddress(), DEFAULT_PASSPHRASE).toString();
					try {
						mUIInterface.setUnpairedDevice(unpairedDevice);
					} catch (RemoteException e) {
						Log.e(TAG, e.getMessage());
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
				requestWrite(address, ACTION_TOGGLE, null);
			} else if (AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(action)) {
				// create widget
				if (intent.hasExtra(AppWidgetManager.EXTRA_APPWIDGET_ID)) {
					int appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
					if (intent.hasExtra(EXTRA_DEVICE_NAME)) {
						// add a widget
						String deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME);
						for (int i = 0, l = mDevices.size(); i < l; i++) {
							String name = null;
							try {
								name = mDevices.get(i).getString(KEY_NAME);
							} catch (JSONException e1) {
								e1.printStackTrace();
							}
							if ((name != null) && name.equals(deviceName)) {
								JSONObject deviceJObj = mDevices.remove(i);
								JSONArray widgetsJArr;
								if (deviceJObj.has(KEY_WIDGETS)) {
									try {
										widgetsJArr = deviceJObj.getJSONArray(KEY_WIDGETS);
									} catch (JSONException e) {
										widgetsJArr = new JSONArray();
									}
								} else
									widgetsJArr = new JSONArray();
								widgetsJArr.put(appWidgetId);
								try {
									deviceJObj.put(KEY_WIDGETS, widgetsJArr);
								} catch (JSONException e) {
									e.printStackTrace();
								}
								mDevices.add(i, deviceJObj);
								TapLock.storeDevices(getSharedPreferences(KEY_PREFS, MODE_PRIVATE), mDevices);
								break;
							}
						}
					}
					buildWidget(appWidgetId);
				} else if (intent.hasExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)) {
					int[] appWidgetIds = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS);
					if (appWidgetIds != null) {
						for (int appWidgetId : appWidgetIds)
							buildWidget(appWidgetId);
					}
				}
			} else if (AppWidgetManager.ACTION_APPWIDGET_DELETED.equals(action)) {
				int appWidgetId = intent.getExtras().getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
				Log.d(TAG, "delete appWidgetId: " + appWidgetId);
				for (int i = 0, l = mDevices.size(); i < l; i++) {
					JSONObject deviceJObj = mDevices.get(i);
					if (deviceJObj.has(KEY_WIDGETS)) {
						JSONArray widgetsJArr = null;
						try {
							widgetsJArr = deviceJObj.getJSONArray(KEY_WIDGETS);
						} catch (JSONException e) {
							e.printStackTrace();
						}
						if (widgetsJArr != null) {
							boolean wasUpdated = false;
							JSONArray newWidgetsJArr = new JSONArray();
							for (int widgetIdx = 0, wdigetsLen = widgetsJArr.length(); widgetIdx < wdigetsLen; widgetIdx++) {
								int widgetId;
								try {
									widgetId = widgetsJArr.getInt(widgetIdx);
								} catch (JSONException e) {
									widgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
									e.printStackTrace();
								}
								Log.d(TAG, "eval widgetId: " + widgetId);
								if ((widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) && (widgetId == appWidgetId)) {
									Log.d(TAG, "skip: " + widgetId);
									wasUpdated = true;
								} else {
									Log.d(TAG, "include: " + widgetId);
									newWidgetsJArr.put(widgetId);
								}
							}
							if (wasUpdated) {
								try {
									deviceJObj.put(KEY_WIDGETS, newWidgetsJArr);
									mDevices.remove(i);
									mDevices.add(i, deviceJObj);
									TapLock.storeDevices(getSharedPreferences(KEY_PREFS, MODE_PRIVATE), mDevices);
									Log.d(TAG, "stored: " + deviceJObj.toString());
								} catch (JSONException e) {
									e.printStackTrace();
								}
							}
						}
					} else {
						JSONArray widgetsJArr = new JSONArray();
						try {
							deviceJObj.put(KEY_WIDGETS, widgetsJArr);
							mDevices.remove(i);
							mDevices.add(i, deviceJObj);
							TapLock.storeDevices(getSharedPreferences(KEY_PREFS, MODE_PRIVATE), mDevices);
						} catch (JSONException e) {
							e.printStackTrace();
						}
					}
				}
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

	private void buildWidget(int appWidgetId) {
		RemoteViews rv = new RemoteViews(getPackageName(), R.layout.widget);
		String deviceName = null;
		for (JSONObject deviceJObj : mDevices) {
			if (deviceJObj.has(KEY_WIDGETS) && deviceJObj.has(KEY_NAME)) {
				JSONArray widgetsJArr = null;
				try {
					widgetsJArr = deviceJObj.getJSONArray(KEY_WIDGETS);
				} catch (JSONException e) {
					e.printStackTrace();
				}
				if (widgetsJArr != null) {
					for (int i = 0, l = widgetsJArr.length(); (i < l) && (deviceName == null); i++) {
						int widgetId;
						try {
							widgetId = widgetsJArr.getInt(i);
						} catch (JSONException e) {
							widgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
							e.printStackTrace();
						}
						if ((widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) && (appWidgetId == widgetId)) {
							try {
								deviceName = deviceJObj.getString(KEY_NAME);
							} catch (JSONException e) {
								e.printStackTrace();
							}
							break;
						}
					}
				}
				if (deviceName != null)
					break;
			}
		}
		if (deviceName == null)
			deviceName = "unknown";
		rv.setTextViewText(R.id.device_name, deviceName);
		rv.setOnClickPendingIntent(R.id.widget_icon, PendingIntent.getActivity(this, 0, TapLock.getPackageIntent(this, TapLockToggle.class).setData(Uri.parse(String.format(getString(R.string.device_uri), deviceName))), Intent.FLAG_ACTIVITY_NEW_TASK));
		AppWidgetManager.getInstance(this).updateAppWidget(appWidgetId, rv);
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

	private void requestWrite(String address, String action, String passphrase) {
		setRequestCanceled(true);
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
	
	private void setRequestCanceled(boolean requestCanceled) {
		synchronized (mThreadLock) {
			mRequestCanceled = requestCanceled;
		}
	}
	
	private boolean requestCanceled() {
		synchronized (mThreadLock) {
			return mRequestCanceled;
		}
	}

	private class ConnectThread extends Thread {
		private String mAddress = null;
		private BluetoothSocket mSocket = null;
		private InputStream inStream = null;
		private OutputStream outStream = null;
		private String mAction = null;
		private String mNewPassphrase = null;

		public ConnectThread(String address, String action, String newPassphrase) {
			mAction = action;
			mAddress = address;
			mNewPassphrase = newPassphrase;
			if (mNewPassphrase == null)
				mNewPassphrase = "";
		}

		public void run() {
			String passphrase = null;
			String name = "device";
			for (JSONObject deviceJObj : mDevices) {
				try {
					if (deviceJObj.getString(KEY_ADDRESS).equals(mAddress)) {
						passphrase = deviceJObj.getString(KEY_PASSPHRASE);
						name = deviceJObj.getString(KEY_NAME);
					}
				} catch (JSONException e) {
					Log.e(TAG, e.getMessage());
				}
			}
			boolean pass = false;
			if ((mAction != null) && passphrase == null)
				mHandler.post(new MessageSetter("...no passphrase found for " + name));
			else {
				mBtAdapter.cancelDiscovery();
				BluetoothDevice device = mBtAdapter.getRemoteDevice(mAddress);
				int connectionAttempt;
				for (connectionAttempt = 0; (connectionAttempt < MAX_CONNECTION_ATTEMPTS) && !requestCanceled(); connectionAttempt++) {
					if (connectionAttempt == 0)
						mHandler.post(new MessageSetter(String.format(getResources().getStringArray(R.array.connection_messages)[connectionAttempt], name)));
					else
						mHandler.post(new MessageSetter(getResources().getStringArray(R.array.connection_messages)[connectionAttempt]));
					try {
						mSocket = device.createRfcommSocketToServiceRecord(sTapLockUUID);
						mSocket.connect();
					} catch (IOException e) {
						mSocket = null;
					}
					if (mSocket != null) {
						if (mAction == null) {
							mHandler.post(new PairingResultSetter(device.getName(), mAddress));
							break;
						} else {
							// Get the BluetoothSocket input and output streams
							try {
								inStream = mSocket.getInputStream();
								outStream = mSocket.getOutputStream();
							} catch (IOException e) {
								inStream = null;
								outStream = null;
								mHandler.post(new MessageSetter("...error getting streams: " + e.getMessage()));
							}
							if ((inStream != null) && (outStream != null)) {
								byte[] buffer = new byte[1024];
								int readBytes = -1;
								try {
									readBytes = inStream.read(buffer);
								} catch (IOException e) {
									mHandler.post(new MessageSetter("...error reading input stream: " + e.getMessage()));
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
										mHandler.post(new MessageSetter("...error reading response: " + responseStr + ", " + e.getMessage()));
									}
									if (challenge != null) {
										byte[] requestBytes = null;
										try {
											JSONObject requestJObj = new JSONObject();
											try {
												requestJObj.put(PARAM_ACTION, mAction);
												if (ACTION_PASSPHRASE.equals(mAction))
													requestJObj.put(PARAM_PASSPHRASE, mNewPassphrase);
												requestJObj.put(PARAM_HMAC, getHashString(challenge + passphrase + mAction + mNewPassphrase));
												String requestStr = requestJObj.toString();
												requestBytes = requestStr.getBytes();
											} catch (JSONException e) {
												mHandler.post(new MessageSetter("...error building request: " + e.getMessage()));
											}
										} catch (NoSuchAlgorithmException e) {
											mHandler.post(new MessageSetter("...error generating hash: " + e.getMessage()));
										} catch (UnsupportedEncodingException e) {
											mHandler.post(new MessageSetter("...error generating hash: " + e.getMessage()));
										}
										if (requestBytes != null) {
											try {
												outStream.write(requestBytes);
												if (ACTION_PASSPHRASE.equals(mAction))
													mHandler.post(new PassphraseSetter(mAddress, mNewPassphrase));
												pass = true;
											} catch (IOException e) {
												mHandler.post(new MessageSetter("...error writing to output stream: " + e.getMessage()));
											}
										}
									} else
										mHandler.post(new MessageSetter("...error receiving challenge from Tap Lock Server."));
									if (pass) {
										// check for error messages
										String error = null;
										try {
											readBytes = inStream.read(buffer);
										} catch (IOException e) {
											readBytes = -1;
											error = e.getMessage();
										}
										if (readBytes != -1) {
											responseStr = new String(buffer, 0, readBytes);
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
										} else
											mHandler.post(new MessageSetter("... error reading input stream: " + error));
									}
									break;
								}
							}
						}
					}
				}
				if (connectionAttempt == MAX_CONNECTION_ATTEMPTS)
					mHandler.post(new MessageSetter("...unable to connect to " + name + ". Is it in range? Is it bluetooth enabled? Please close this."));
			}
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
		if (key.equals(KEY_DEVICES)) {
			mDevices.clear();
			Set<String> devices = sharedPreferences.getStringSet(KEY_DEVICES, null);
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
