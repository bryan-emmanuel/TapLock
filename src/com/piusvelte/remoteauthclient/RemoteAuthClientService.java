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
	private HashMap<String, String> mRequestQueue = new HashMap<String, String>();
	private boolean mStartedBT = false;
	private String[] mDevices = new String[0];
	private static final UUID sRemoteAuthServerUUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

	protected static int[] sSocketLock = new int[0];
	protected static int[] sThreadLock = new int[0];
	protected static int[] sRequestLock = new int[0];

	protected String mMessage = "";
	protected final Handler mHandler = new Handler();
	protected final Runnable mRunnable = new Runnable() {
		public void run() {
			if (mUIInterface != null) {
				try {
					if (mMessage != null)
						mUIInterface.setMessage(mMessage);
					else
						mUIInterface.setStateFinished();
				} catch (RemoteException e) {
					Log.e(TAG, e.toString());
				}
			} else if (mMessage != null) {
				Toast.makeText(RemoteAuthClientService.this, mMessage, Toast.LENGTH_LONG).show();
				Log.d(TAG, mMessage);
			}
		}
	};

	private IRemoteAuthClientUI mUIInterface;
	private final IRemoteAuthClientService.Stub mServiceInterface = new IRemoteAuthClientService.Stub() {

		@Override
		public void setCallback(IBinder uiBinder) throws RemoteException {
			if (uiBinder != null)
				mUIInterface = IRemoteAuthClientUI.Stub.asInterface(uiBinder);
		}

		@Override
		public void write(String address, String state) throws RemoteException {
			requestWrite(address, state);
		}

		@Override
		public void requestDiscovery() throws RemoteException {
			if (mBtAdapter.isEnabled()) {
				// If we're already discovering, stop it
				if (mBtAdapter.isDiscovering())
					mBtAdapter.cancelDiscovery();
				// Request discover from BluetoothAdapter
				mBtAdapter.startDiscovery();
			} else {
				mBtAdapter.enable();
			}
		}

		@Override
		public void stop() throws RemoteException {
			if (mStartedBT)
				mBtAdapter.disable();
		}
	};

	private BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {

		@Override
		public void onReceive(Context context, Intent intent) {
			startService(intent.setClass(context, RemoteAuthClientService.class));
		}

	};

	@Override
	public void onCreate() {
		super.onCreate();
		onSharedPreferenceChanged(getSharedPreferences(getString(R.string.key_preferences), Context.MODE_PRIVATE), getString(R.string.key_devices));
		mBtAdapter = BluetoothAdapter.getDefaultAdapter();
		IntentFilter filter = new IntentFilter();
		filter.addAction(Intent.ACTION_SCREEN_OFF);
		filter.addAction(Intent.ACTION_SCREEN_ON);
		registerReceiver(mScreenReceiver, filter);
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if (intent != null) {
			String action = intent.getAction();
			if ((action == null) || Intent.ACTION_SCREEN_ON.equals(action)) {
				if (mBtAdapter.isEnabled()) {
					if (!mBtAdapter.isDiscovering())
						mBtAdapter.startDiscovery();
				} else {
					if (mStartedBT)
						mBtAdapter.enable();
				}
			} else {
				if (Intent.ACTION_SCREEN_OFF.equals(action)) {
					stopConnectionThreads();
					if (mStartedBT)
						mBtAdapter.disable();
				} else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
					int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF);
					if (state == BluetoothAdapter.STATE_ON) {
						if (!mBtAdapter.isDiscovering())
							mBtAdapter.startDiscovery();
					} else if (state == BluetoothAdapter.STATE_TURNING_OFF) {
						stopConnectionThreads();
						if (mUIInterface == null)
							RemoteAuthClientService.this.stopSelf();
					}
					// When discovery finds a device
				} else if (BluetoothDevice.ACTION_FOUND.equals(action)) {
					// Get the BluetoothDevice object from the Intent
					BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
					String address = device.getAddress();
					synchronized (sThreadLock) {
						if (!mConnectThreads.containsKey(address)) {
							// not currently connect, check if stored
							for (String d : mDevices) {
								String[] parts = RemoteAuthClientUI.parseDeviceString(d);
								if (parts[RemoteAuthClientUI.DEVICE_ADDRESS].equals(address)) {
									// connect to stored device
									ConnectThread connectThread = new ConnectThread(address);
									mConnectThreads.put(address, connectThread);
									connectThread.start();
									break;
								}
							}
						}
					}
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
				} else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
					if (mUIInterface != null) {
						try {
							mUIInterface.setDiscoveryFinished();
						} catch (RemoteException e) {
							Log.e(TAG, e.toString());
						}
					}
				} else if (action.equals(ACTION_TOGGLE) && intent.hasExtra(EXTRA_DEVICE_ADDRESS)) {
					String address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS);
					requestWrite(address, Integer.toString(RemoteAuthClientUI.STATE_TOGGLE));
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
							for (String widget : widgets)
								newWidgets.add(widget);
							String name = intent.getStringExtra(RemoteAuthClientService.EXTRA_DEVICE_NAME);
							String address = intent.getStringExtra(RemoteAuthClientService.EXTRA_DEVICE_ADDRESS);
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
				} else if (action.equals(AppWidgetManager.ACTION_APPWIDGET_DELETED)) {
					int appWidgetId = intent.getExtras().getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
					SharedPreferences sp = getSharedPreferences(getString(R.string.key_preferences), MODE_PRIVATE);
					Set<String> widgets = sp.getStringSet(getString(R.string.key_widgets), (new HashSet<String>()));
					Set<String> newWidgets = new HashSet<String>();
					for (String widget : widgets) {
						String[] widgetParts = RemoteAuthClientUI.parseDeviceString(widget);
						if (!widgetParts[RemoteAuthClientUI.DEVICE_PASSPHRASE].equals(Integer.toString(appWidgetId)))
							newWidgets.add(widget);
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
		stopConnectionThreads();
		if (mScreenReceiver != null) {
			unregisterReceiver(mScreenReceiver);
			mScreenReceiver = null;
		}
		if (mStartedBT)
			mBtAdapter.disable();
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
		if (intent.hasExtra(RemoteAuthClientService.EXTRA_DEVICE_STATE))
			views.setTextViewText(R.id.device_state, intent.getStringExtra(RemoteAuthClientService.EXTRA_DEVICE_STATE));
		else
			views.setTextViewText(R.id.device_state, getString(R.string.widget_device_state));
		if (intent.hasExtra(RemoteAuthClientService.EXTRA_DEVICE_ADDRESS))
			views.setOnClickPendingIntent(R.id.widget, PendingIntent.getBroadcast(this, 0, new Intent(this, RemoteAuthClientWidget.class).setAction(RemoteAuthClientService.ACTION_TOGGLE).putExtra(RemoteAuthClientService.EXTRA_DEVICE_ADDRESS, intent.getStringExtra(RemoteAuthClientService.EXTRA_DEVICE_ADDRESS)), 0));
		return views;
	}

	private void requestWrite(String address, String state) {
		Log.d(TAG,"requestWrite:" + address + "," + state);
		if (mBtAdapter.isEnabled()) {
			synchronized (sThreadLock) {
				ConnectThread connectThread;
				if (mConnectThreads.containsKey(address)) {
					Log.d(TAG,"thread already exists");
					connectThread = mConnectThreads.get(address);
					if (connectThread.isConnected(address)) {
						Log.d(TAG,"thread is connected");
						if (connectThread.hasStreams()) {
							Log.d(TAG,"thread has streams");
							connectThread.write(state);
							return;
						}
					}
					Log.d(TAG, "not connected, queue the request, and attempt to reconnect");
					synchronized (sRequestLock) {
						mRequestQueue.put(address, state);
					}
					// attempt reconnect
					connectThread.shutdown();
					mConnectThreads.remove(address);
				}
				connectThread = new ConnectThread(address);
				mConnectThreads.put(address, connectThread);
				connectThread.start();
			}
			return;
		} else {
			Log.d(TAG, "BT is disabled, queue the request and start BT");
			synchronized (sRequestLock) {
				mRequestQueue.put(address, state);
			}
			mStartedBT = true;
			mBtAdapter.enable();
		}
	}

	private void stopConnectionThreads() {
		// Cancel any thread attempting to make a connection
		synchronized (sThreadLock) {
			if (!mConnectThreads.isEmpty()) {
				Set<String> keys = mConnectThreads.keySet();
				for (String key : keys) {
					mConnectThreads.get(key).shutdown();
					mConnectThreads.remove(key);
				}
			}
		}
	}

	private class ConnectThread extends Thread {
		private BluetoothSocket mSocket;
		private InputStream mInStream;
		private OutputStream mOutStream;

		private String mChallenge;
		private MessageDigest mDigest;

		private String mAddress;

		public ConnectThread(String address) {
			Log.d(TAG,"new thread");
			mAddress = address;
		}

		public void run() {
			Log.d(TAG, "start thread, get device...");
			if (mBtAdapter.isDiscovering())
				mBtAdapter.cancelDiscovery();
			try {
				synchronized (sSocketLock) {
					BluetoothDevice device = mBtAdapter.getRemoteDevice(mAddress);
					Log.d(TAG, "...got device, get socket...");
//					mSocket = device.createInsecureRfcommSocketToServiceRecord(sRemoteAuthServerUUID);
					mSocket = device.createRfcommSocketToServiceRecord(sRemoteAuthServerUUID);
					Log.d(TAG, "...got socket, connect...");
					mSocket.connect();
					Log.d(TAG, "...check if connected...");
					if (mSocket.isConnected()) {
						Log.d(TAG, "...connected");
						// Get the BluetoothSocket input and output streams
						mInStream = mSocket.getInputStream();
						mOutStream = mSocket.getOutputStream();
					}
				}
			} catch (IOException e) {
				Log.e(TAG, "connect error: " + e.getMessage());
				shutdown();
			}

			if (hasStreams()) {
				while (isReadable()) {
					synchronized (sRequestLock) {
						if (mRequestQueue.containsKey(mAddress))
							write(mRequestQueue.get(mAddress));
					}
				}
			}
		}

		private boolean isReadable() {
			byte[] buffer = new byte[1024];
			int readBytes = -1;
			synchronized (sSocketLock) {
				try {
					readBytes = mInStream.read(buffer);
				} catch (IOException e) {
					Log.e(TAG, e.toString());
					return false;
				}
			}
			if (readBytes != -1) {
				// construct a string from the valid bytes in the buffer
				String message = new String(buffer, 0, readBytes);
				// listen for challenge, then process a response
				if ((message.length() > 10) && (message.substring(0, 9).equals("challenge")))
					mChallenge = message.substring(10);
				return true;
			} else
				return false;
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
								if ((passphrase = parts[RemoteAuthClientUI.DEVICE_PASSPHRASE]) != null) {
									String challenge = mChallenge;
									// the challenge is stale after it's used
									mChallenge = null;
									if (challenge != null) {
										if (mDigest == null) {
											try {
												mDigest = MessageDigest.getInstance("SHA-256");
											} catch (NoSuchAlgorithmException e) {
												Log.e(TAG, e.toString());
											}
										}
										if (mDigest != null) {
											mDigest.reset();
											try {
												mDigest.update((challenge + passphrase + state).getBytes("UTF-8"));
												String request = new BigInteger(1, mDigest.digest()).toString(16);
												synchronized (sSocketLock) {
													mOutStream.write(request.getBytes());
												}
												synchronized (sRequestLock) {
													if (mRequestQueue.containsKey(mAddress))
														mRequestQueue.remove(mAddress);
												}
												mMessage = null;
												mHandler.post(mRunnable);
											} catch (IOException e) {
												mMessage = "write error: " + e.toString();
												mHandler.post(mRunnable);
											}
											requestChallenge();
										}
									} else
										requestChallenge();
								}
								break;
							}
						}
					}
				}
			}
		}

		public void requestChallenge() {
			try {
				synchronized (sSocketLock) {
					mOutStream.write(("challenge").getBytes());
				}
			} catch (IOException e) {
				Log.e(TAG, "get challenge error: ", e);
			}
		}

		public boolean hasStreams() {
			return (mInStream != null) && (mOutStream != null);
		}

		public boolean isConnected(String address) {
			if (mSocket != null) {
				return address.equals(mAddress);
			} else
				return false;
		}

		public void shutdown() {
			Log.d(TAG,"thread shutdown");
			mInStream = null;
			mOutStream = null;
			if (mSocket != null) {
				try {
					mSocket.close();
				} catch (IOException e) {
					Log.e(TAG, e.toString());
				}
				mSocket = null;
			}
			synchronized (sThreadLock) {
				if ((mAddress != null) && mConnectThreads.containsKey(mAddress))
					mConnectThreads.remove(mAddress);
			}
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
				while (iter.hasNext())
					mDevices[d++] = iter.next();
			} else
				mDevices = new String[0];
		}
	}

}
