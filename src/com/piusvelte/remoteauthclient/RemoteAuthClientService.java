package com.piusvelte.remoteauthclient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

import android.app.PendingIntent;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
//import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.widget.RemoteViews;
import android.widget.Toast;

public class RemoteAuthClientService extends Service implements OnSharedPreferenceChangeListener {
	private static final String TAG = "RemoteAuthClientService";
	public static final String ACTION_TOGGLE = "com.piusvelte.remoteAuthClient.ACTION_TOGGLE";
	public static final String EXTRA_DEVICE_ADDRESS = "com.piusvelte.remoteAuthClient.EXTRA_DEVICE_ADDRESS";
	public static final String EXTRA_DEVICE_NAME = "com.piusvelte.remoteAuthClient.EXTRA_DEVICE_NAME";
	public static final String EXTRA_DEVICE_STATE = "com.piusvelte.remoteAuthClient.EXTRA_DEVICE_STATE";
	private BluetoothAdapter mBtAdapter;
	private HashMap<String, ConnectThread> mConnectThreads = new HashMap<String, ConnectThread>();
	private String mPendingState;
	private String mPendingAddress;
	private String mPendingUuid;
	private static final int REQUEST_NONE = 0;
	private static final int REQUEST_WRITE = 1;
	private static final int REQUEST_DISCOVERY = 2;
	private int REQUEST = REQUEST_NONE;
	private boolean mStartedBT = false;
	private String[] mDevices = new String[0];
	// Unique UUID for this application
//	private static final String sSPD = "RemoteAuth";
	private static final UUID sRemoteAuthServerUUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

	protected String mMessage = "";
	protected final Handler mHandler = new Handler();
	protected final Runnable mRunnable = new Runnable() {
		public void run() {
			if (mUIInterface != null) {
				try {
					if (mMessage != null) {
						mUIInterface.setMessage(mMessage);
					} else {
						mUIInterface.setStateFinished();
					}
				} catch (RemoteException e) {
					Log.e(TAG, e.toString());
				}
			} else {
				Toast.makeText(RemoteAuthClientService.this, mMessage, Toast.LENGTH_LONG).show();
			}
			Log.d(TAG, mMessage);
		}
	};

	private IRemoteAuthClientUI mUIInterface;
	private final IRemoteAuthClientService.Stub mServiceInterface = new IRemoteAuthClientService.Stub() {

		@Override
		public void setCallback(IBinder uiBinder) throws RemoteException {
			if (uiBinder != null) {
				mUIInterface = IRemoteAuthClientUI.Stub.asInterface(uiBinder);
			}
		}

		@Override
		public void write(String address, String state) throws RemoteException {
			requestWrite(address, state);
		}

		@Override
		public void requestDiscovery() throws RemoteException {
			setRequest(REQUEST_DISCOVERY);
			if (mBtAdapter.isEnabled()) {
				// If we're already discovering, stop it
				if (mBtAdapter.isDiscovering()) {
					mBtAdapter.cancelDiscovery();
				}
				// Request discover from BluetoothAdapter
				mBtAdapter.startDiscovery();
			} else {
				mBtAdapter.enable();
			}
		}

		@Override
		public void stop() throws RemoteException {
			if (mStartedBT) {
				mBtAdapter.disable();
			}
		}
	};

	// The BroadcastReceiver that listens for discovered devices and
	// changes the title when discovery is finished
	private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();

			if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
				int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF);
				if (state == BluetoothAdapter.STATE_TURNING_OFF) {
					stopConnectionThreads();
					if (mUIInterface == null) {
						RemoteAuthClientService.this.stopSelf();
					}
				}
				// When discovery finds a device
			} else if (BluetoothDevice.ACTION_FOUND.equals(action)) {
				// Get the BluetoothDevice object from the Intent
				BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
				int request = getRequest();
				switch (request) {
				case REQUEST_WRITE:
					if (hasPendingRequest() && (device.getAddress().equals(mPendingAddress))) {
						connectDevice(mPendingAddress, mPendingUuid, device);
					}
					break;
				case REQUEST_DISCOVERY:
					// If it's already paired, skip it, because it's been listed already
					if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
						if (mUIInterface != null) {
							try {
								mUIInterface.setUnpairedDevice(device.getName() + " " + device.getAddress());
							} catch (RemoteException e) {
								Log.e(TAG, e.toString());
							}
						}
					}
					break;
				default:
					break;
				}
			} else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
				int request = getRequest();
				switch (request) {
				case REQUEST_WRITE:
					// even if a pending request exists, it should have been triggered in the discovery phase
					setRequest(REQUEST_NONE);
					break;
				case REQUEST_DISCOVERY:
					if (mUIInterface != null) {
						try {
							mUIInterface.setDiscoveryFinished();
						} catch (RemoteException e) {
							Log.e(TAG, e.toString());
						}
					}
					setRequest(REQUEST_NONE);
					break;
				default:
					break;
				}
			}
		}
	};

	@Override
	public void onCreate() {
		super.onCreate();
		onSharedPreferenceChanged(getSharedPreferences(getString(R.string.key_preferences), Context.MODE_PRIVATE), getString(R.string.key_devices));
		mBtAdapter = BluetoothAdapter.getDefaultAdapter();
		IntentFilter filter = new IntentFilter();
		filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
		filter.addAction(BluetoothDevice.ACTION_FOUND);
		filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
		registerReceiver(mReceiver, filter);
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if (intent != null) {
			String action = intent.getAction();
			if (action != null) {
				if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
					stopConnectionThreads();
					int request = getRequest();
					switch (request) {
					case REQUEST_WRITE:
						if (hasPendingRequest()) {
							connectDevice(mPendingAddress, mPendingUuid, null);
						}
						break;
					case REQUEST_DISCOVERY:
						mBtAdapter.startDiscovery();
						break;
					default:
						break;
					}
				} else if (action.equals(ACTION_TOGGLE) && intent.hasExtra(EXTRA_DEVICE_ADDRESS)) {
					String address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS);
					requestWrite(address, Integer.toString(RemoteAuthClientUI.STATE_TOGGLE));
					//					if (intent.hasExtra(AppWidgetManager.EXTRA_APPWIDGET_ID)) {
					//						// show progress on widget
					//						int appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
					//						RemoteViews views = new RemoteViews(getPackageName(), R.layout.widget);
					//						if (intent.hasExtra(RemoteAuthClientService.EXTRA_DEVICE_NAME)) {
					//							views.setTextViewText(R.id.device_name, intent.getStringExtra(RemoteAuthClientService.EXTRA_DEVICE_NAME));
					//						} else {
					//							String name = getString(R.string.widget_device_name);
					//							SharedPreferences sp = getSharedPreferences(getString(R.string.key_preferences), Context.MODE_PRIVATE);
					//							Set<String> widgets = sp.getStringSet(getString(R.string.key_widgets), (new HashSet<String>()));
					//							for (String widget : widgets) {
					//								String[] widgetParts = RemoteAuthClientUI.parseDeviceString(widget);
					//								if (widgetParts[RemoteAuthClientUI.DEVICE_PASSPHRASE].equals(Integer.toString(appWidgetId))) {
					//									name = widgetParts[RemoteAuthClientUI.DEVICE_NAME];
					//									break;
					//								}
					//							}
					//							views.setTextViewText(R.id.device_name, name);
					//						}
					//						views.setTextViewText(R.id.device_state, "...");
					//						if (intent.hasExtra(RemoteAuthClientService.EXTRA_DEVICE_ADDRESS)) {
					//							views.setOnClickPendingIntent(R.id.widget, PendingIntent.getBroadcast(this, 0, new Intent(this, RemoteAuthClientWidget.class).setAction(RemoteAuthClientService.ACTION_TOGGLE).putExtra(RemoteAuthClientService.EXTRA_DEVICE_ADDRESS, address), 0));
					//						}
					//						AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(this);
					//						appWidgetManager.updateAppWidget(appWidgetId, views);
					//					}
				} else if (action.equals(AppWidgetManager.ACTION_APPWIDGET_UPDATE)) {
					// create widget
					AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(this);
					SharedPreferences sp = getSharedPreferences(getString(R.string.key_preferences), MODE_PRIVATE);
					Set<String> widgets = sp.getStringSet(getString(R.string.key_widgets), (new HashSet<String>()));
					if (intent.hasExtra(AppWidgetManager.EXTRA_APPWIDGET_ID)) {
						int appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
						// check if the widget exists, otherwise add it
						if (intent.hasExtra(RemoteAuthClientService.EXTRA_DEVICE_NAME) && intent.hasExtra(RemoteAuthClientService.EXTRA_DEVICE_ADDRESS)) {
							Set<String> newWidgets = new HashSet<String>();
							for (String widget : widgets) {
								newWidgets.add(widget);
							}
							String name = intent.getStringExtra(RemoteAuthClientService.EXTRA_DEVICE_NAME);
							String address = intent.getStringExtra(RemoteAuthClientService.EXTRA_DEVICE_ADDRESS);
							String widgetString = name + " " + Integer.toString(appWidgetId) + " " + address;
							// store the widget
							if (!newWidgets.contains(widgetString)) {
								newWidgets.add(widgetString);
							}
							SharedPreferences.Editor spe = sp.edit();
							spe.putStringSet(getString(R.string.key_widgets), newWidgets);
							spe.commit();
						}
						appWidgetManager.updateAppWidget(appWidgetId, buildWidget(intent, appWidgetId, widgets));
					} else if (intent.hasExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)) {
						int[] appWidgetIds = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS);
						for (int appWidgetId : appWidgetIds) {
							appWidgetManager.updateAppWidget(appWidgetId, buildWidget(intent, appWidgetId, widgets));
						}
					}
				} else if (action.equals(AppWidgetManager.ACTION_APPWIDGET_DELETED)) {
					int appWidgetId = intent.getExtras().getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
					SharedPreferences sp = getSharedPreferences(getString(R.string.key_preferences), MODE_PRIVATE);
					Set<String> widgets = sp.getStringSet(getString(R.string.key_widgets), (new HashSet<String>()));
					Set<String> newWidgets = new HashSet<String>();
					for (String widget : widgets) {
						String[] widgetParts = RemoteAuthClientUI.parseDeviceString(widget);
						if (!widgetParts[RemoteAuthClientUI.DEVICE_PASSPHRASE].equals(Integer.toString(appWidgetId))) {
							newWidgets.add(widget);
						}
					}
					SharedPreferences.Editor spe = sp.edit();
					spe.putStringSet(getString(R.string.key_widgets), newWidgets);
					spe.commit();
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
		unregisterReceiver(mReceiver);
		stopConnectionThreads();
		//		setListen(false, null);
	}

	private RemoteViews buildWidget(Intent intent, int appWidgetId, Set<String> widgets) {
		RemoteViews views = new RemoteViews(getPackageName(), R.layout.widget);
		if (intent.hasExtra(RemoteAuthClientService.EXTRA_DEVICE_NAME)) {
			views.setTextViewText(R.id.device_name, intent.getStringExtra(RemoteAuthClientService.EXTRA_DEVICE_NAME));
		} else {
			String name = getString(R.string.widget_device_name);
			for (String widget : widgets) {
				String[] widgetParts = RemoteAuthClientUI.parseDeviceString(widget);
				if (widgetParts[RemoteAuthClientUI.DEVICE_PASSPHRASE].equals(Integer.toString(appWidgetId))) {
					name = widgetParts[RemoteAuthClientUI.DEVICE_NAME];
					break;
				}
			}
			views.setTextViewText(R.id.device_name, name);
		}
		if (intent.hasExtra(RemoteAuthClientService.EXTRA_DEVICE_STATE)) {
			views.setTextViewText(R.id.device_state, intent.getStringExtra(RemoteAuthClientService.EXTRA_DEVICE_STATE));
		} else {
			views.setTextViewText(R.id.device_state, getString(R.string.widget_device_state));
		}
		if (intent.hasExtra(RemoteAuthClientService.EXTRA_DEVICE_ADDRESS)) {
			views.setOnClickPendingIntent(R.id.widget, PendingIntent.getBroadcast(this, 0, new Intent(this, RemoteAuthClientWidget.class).setAction(RemoteAuthClientService.ACTION_TOGGLE).putExtra(RemoteAuthClientService.EXTRA_DEVICE_ADDRESS, intent.getStringExtra(RemoteAuthClientService.EXTRA_DEVICE_ADDRESS)), 0));
		}
		return views;
	}

	private void requestWrite(String address, String state) {
		String uuid = sRemoteAuthServerUUID.toString();
		setRequest(REQUEST_WRITE);
		if (mBtAdapter.isEnabled()) {
			if (mConnectThreads.containsKey(address)) {
				ConnectThread connectThread;
				synchronized (this) {
					connectThread = mConnectThreads.get(address);
					if ((connectThread != null) && connectThread.isConnected(address)) {
						if (connectThread.hasStreams()) {
							// Perform the write unsynchronized
							connectThread.write(state);
						} else {
							setPendingRequest(state, address, uuid);
						}
					} else {
						setPendingRequest(state, address, uuid);
						connectDevice(address, uuid, null);
					}
				}
			} else {
				setPendingRequest(state, address, uuid);
				connectDevice(address, uuid, null);
			}
		} else {
			setPendingRequest(state, address, uuid);
			mStartedBT = true;
			mBtAdapter.enable();
		}
	}

	private void stopConnectionThread(String address) {
		if (mConnectThreads.containsKey(address)) {
			mConnectThreads.get(address).cancel();
			mConnectThreads.remove(address);
		}
	}

	private void stopConnectionThreads() {
		// Cancel any thread attempting to make a connection
		if (!mConnectThreads.isEmpty()) {
			Set<String> keys = mConnectThreads.keySet();
			for (String key : keys) {
				mConnectThreads.get(key).cancel();
				mConnectThreads.remove(key);
			}
		}

	}

	public BluetoothDevice getDevice(String address) {
		BluetoothDevice device = null;
		Set<BluetoothDevice> devices = mBtAdapter.getBondedDevices();
		Iterator<BluetoothDevice> iter = devices.iterator();
		while (iter.hasNext() && (device == null)) {
			BluetoothDevice d = iter.next();
			if (d.getAddress().equals(address)) {
				device = d;
			}
		}
		if (device == null) {
			device = mBtAdapter.getRemoteDevice(address);
		}
		return device;
	}

	public void setPendingRequest(String state, String address, String uuid) {
		mPendingState = state;
		mPendingAddress = address;
		mPendingUuid = uuid;
	}

	public boolean hasPendingRequest() {
		return (mPendingState != null) && (mPendingAddress != null) && (mPendingUuid != null);
	}

	public synchronized int getRequest() {
		return REQUEST;
	}

	public synchronized void setRequest(int request) {
		REQUEST = request;
	}

	public synchronized void connectDevice(String address, String uuid, BluetoothDevice device) {
		// don't reconnect if already connected
		if (mConnectThreads.containsKey(address)) {
			ConnectThread connectThread = mConnectThreads.get(address);
			if (!connectThread.isConnected(address)) {
				connectThread.cancel();
				mConnectThreads.remove(address);
				if (device == null) {
					device = getDevice(address);
					connectThread = new ConnectThread(device, uuid);
					if (connectThread.hasSocket()) {
						connectThread.start();
						mConnectThreads.put(address, connectThread);
					} else {
						connectThread.cancel();
					}
				}
			}
		}
	}

	public synchronized void connected(BluetoothSocket socket, BluetoothDevice device) {
		String address = device.getAddress();
		stopConnectionThread(address);

		ConnectThread connectThread = new ConnectThread(socket);
		if (connectThread.hasStreams()) {
			connectThread.start();
			mConnectThreads.put(address, connectThread);
		} else {
			connectThread.cancel();
		}
	}

	private class ConnectThread extends Thread {
		private BluetoothSocket mSocket;
		private InputStream mInStream;
		private OutputStream mOutStream;

		private String mChallenge;
		private MessageDigest mDigest;

		private boolean mConnected = true;

		public ConnectThread(BluetoothSocket socket) {
			mSocket = socket;
		}

		public ConnectThread(BluetoothDevice device, String uuid) {
			BluetoothSocket tmp = null;

			// Get a BluetoothSocket for a connection with the
			// given BluetoothDevice
			try {
				tmp = device.createInsecureRfcommSocketToServiceRecord(UUID.fromString(uuid));
			} catch (IOException e) {
				Log.e(TAG, e.toString());
			}
			mSocket = tmp;
		}

		public boolean hasSocket() {
			return (mSocket != null);
		}

		public void run() {
			Log.d(TAG, "socket connect");
			mBtAdapter.cancelDiscovery();

			try {
				mSocket.connect();
			} catch (IOException e) {
				mMessage = "connection attempt failed: " + e.toString();
				mHandler.post(mRunnable);
				try {
					mSocket.close();
				} catch (IOException e2) {
					Log.e(TAG, e2.toString());
				}
				mSocket = null;
			}

			if (hasSocket() && mSocket.isConnected()) {
				InputStream tmpIn = null;
				OutputStream tmpOut = null;

				// Get the BluetoothSocket input and output streams
				try {
					tmpIn = mSocket.getInputStream();
					tmpOut = mSocket.getOutputStream();
				} catch (IOException e) {
					mMessage = "sockets not created: " + e.toString();
					mHandler.post(mRunnable);
				}

				mInStream = tmpIn;
				mOutStream = tmpOut;

				if (hasStreams()) {
					while (mConnected) {
						byte[] buffer = new byte[1024];
						int readBytes = -1;
						try {
							readBytes = mInStream.read(buffer);
						} catch (IOException e) {
							mConnected = false;
							Log.e(TAG, e.toString());
						}
						if (readBytes != -1) {
							// construct a string from the valid bytes in the buffer
							String message = new String(buffer, 0, readBytes);
							// listen for challenge, then process a response
							if ((message.length() > 10) && (message.substring(0, 9).equals("challenge"))) {
								mChallenge = message.substring(10);
								if (hasPendingRequest()) {
									write(mPendingState);
								}
								//							} else if ((message.length() > 6) && (message.substring(0, 5).equals("state"))) {
								//								// update widgets
								//								// need to identify the widgets for this device
								//								int state = Integer.parseInt(message.substring(6, 7));
								//								String response = getString(R.string.widget_device_state);
								//								if (state== RemoteAuthClientUI.STATE_UNLOCK) {
								//									response = "lock";
								//								} else if (state == RemoteAuthClientUI.STATE_LOCK) {
								//									response = "unlock";
								//								}
								//								SharedPreferences sp = getSharedPreferences(getString(R.string.key_preferences), Context.MODE_PRIVATE);
								//								Set<String> widgets = sp.getStringSet(getString(R.string.key_widgets), null);
								//								Set<Integer> appWidgetIds = new HashSet<Integer>();
								//								if (widgets != null) {
								//									Log.d(TAG, "stored widgets: " + widgets.size());
								//									String name = getString(R.string.widget_device_name);
								//									for (String widget : widgets) {
								//										String[] widgetParts = RemoteAuthClientUI.parseDeviceString(widget);
								//										if (widgetParts[RemoteAuthClientUI.DEVICE_ADDRESS].equals(mAddress)) {
								//											name = widgetParts[RemoteAuthClientUI.DEVICE_NAME];
								//											appWidgetIds.add(Integer.parseInt(widgetParts[RemoteAuthClientUI.DEVICE_PASSPHRASE]));
								//											break;
								//										}
								//									}
								//									if (!appWidgetIds.isEmpty()) {
								//										int[] widgetArray = new int[appWidgetIds.size()];
								//										int i = 0;
								//										for (int appWidgetId : appWidgetIds) {
								//											widgetArray[i++] = appWidgetId;
								//										}
								//										sendBroadcast(new Intent(RemoteAuthClientService.this, RemoteAuthClientWidget.class).setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE).putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, widgetArray).putExtra(RemoteAuthClientService.EXTRA_DEVICE_NAME, name).putExtra(RemoteAuthClientService.EXTRA_DEVICE_ADDRESS, mAddress).putExtra(RemoteAuthClientService.EXTRA_DEVICE_STATE, response));
								//									}
								//								}
							}
						} else {
							mConnected = false;
						}
					}
				}
			}
			// return to a listening state
			cancel();
		}

		public void write(String state) {
			// get passphrase
			String passphrase = null;
			if (mSocket != null) {
				BluetoothDevice bd = mSocket.getRemoteDevice();
				if (bd != null) {
					String address = bd.getAddress();
					if (address != null) {
						for (String d : mDevices) {
							String[] parts = RemoteAuthClientUI.parseDeviceString(d);
							if (parts[RemoteAuthClientUI.DEVICE_ADDRESS].equals(address)) {
								passphrase = parts[RemoteAuthClientUI.DEVICE_PASSPHRASE];
								break;
							}
						}
					}
				}
			}
			if (passphrase != null) {
				String challenge = getChallenge();
				if (challenge != null) {
					if (mDigest == null) {
						try {
							mDigest = MessageDigest.getInstance("SHA-256");
						} catch (NoSuchAlgorithmException e) {
							Log.e(TAG, e.toString());
						}
					}
					if ((challenge != null) && (mDigest != null)) {
						mDigest.reset();
						try {
							mDigest.update((challenge + passphrase + state).getBytes("UTF-8"));
							String request = new BigInteger(1, mDigest.digest()).toString(16);
							mOutStream.write(request.getBytes());
							mPendingState = null;
							mPendingAddress = null;
							mPendingUuid = null;
							mMessage = null;
							mHandler.post(mRunnable);
						} catch (IOException e) {
							mMessage = "write error: " + e.toString();
							mHandler.post(mRunnable);
						}
						// need to get a new challenge
						try {
							mOutStream.write(("challenge").getBytes());
						} catch (IOException e) {
							Log.e(TAG, "get challenge error: ", e);
						}
					}
				} else {
					// need to get a new challenge
					try {
						mOutStream.write(("challenge").getBytes());
					} catch (IOException e) {
						Log.e(TAG, "get challenge error: ", e);
					}
				}
			}
			if (getRequest() == REQUEST_WRITE) {
				setRequest(REQUEST_NONE);
			}
		}

		public boolean hasStreams() {
			return (mInStream != null) && (mOutStream != null);
		}

		public boolean isConnected(String address) {
			if (mSocket != null) {
				BluetoothDevice device = mSocket.getRemoteDevice();
				if (device == null) {
					return false;
				} else {
					return device.getAddress().equals(address);
				}
			} else {
				return false;
			}
		}

		private synchronized String getChallenge() {
			// the challenge is stale after it's used
			String challenge = mChallenge;
			mChallenge = null;
			return challenge;
		}

		public void cancel() {
			mConnected = false;
			if (mSocket != null) {
				String address = mSocket.getRemoteDevice().getAddress();
				try {
					mSocket.close();
				} catch (IOException e) {
					Log.e(TAG, e.toString());
				}
				mSocket = null;
				synchronized (RemoteAuthClientService.this) {
					if ((address != null) && mConnectThreads.containsKey(address)) {
						mConnectThreads.remove(address);
					}
				}
			}
			// fallback to listening
			//			setListen(true, mPassphrase);
		}
	}

	@Override
	public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
		if (key.equals(getString(R.string.key_devices))) {
			Set<String> devices = sharedPreferences.getStringSet(getString(R.string.key_devices), null);
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
		}
	}

}
