package com.piusvelte.remoteauthclient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.widget.RemoteViews;

public class RemoteAuthClientService extends Service implements OnSharedPreferenceChangeListener {
	private static final String TAG = "RemoteAuthClientService";
	public static final String ACTION_TOGGLE = "com.piusvelte.remoteAuthClient.ACTION_TOGGLE";
	public static final String EXTRA_DEVICE_ADDRESS = "com.piusvelte.remoteAuthClient.EXTRA_DEVICE_ADDRESS";
	public static final String EXTRA_DEVICE_NAME = "com.piusvelte.remoteAuthClient.EXTRA_DEVICE_NAME";
	public static final String EXTRA_DEVICE_STATE = "com.piusvelte.remoteAuthClient.EXTRA_DEVICE_STATE";
	private BluetoothAdapter mBtAdapter;
	private ConnectThread mConnectThread;
	private String mQueueAddress;
	private String mQueueState;
	private boolean mRequestDiscovery = false;
	private boolean mStartedBT = false;
	private boolean mDeviceFound = false;
	private String[] mDevices = new String[0];
	private static final UUID sRemoteAuthServerUUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
	private int[] mThreadLock = new int[0];

	protected String mMessage = "";
	protected String mPairedDevice = null;
	protected final Handler mHandler = new Handler();
	protected final Runnable mRunnable = new Runnable() {
		public void run() {
			if (mMessage != null)
				Log.d(TAG, mMessage);
			if (mUIInterface != null) {
				try {
					if (mMessage != null)
						mUIInterface.setMessage(mMessage);
					else if (mPairedDevice != null) {
						mUIInterface.setPairingResult(mPairedDevice);
						mPairedDevice = null;
					} else
						mUIInterface.setStateFinished();
				} catch (RemoteException e) {
					Log.e(TAG, e.toString());
				}
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
			if (mStartedBT) {
				mStartedBT = false;
				mBtAdapter.disable();
			}
		}

		@Override
		public void pairDevice(String address) throws RemoteException {
			requestWrite(address, null);
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
					// if pending...
					if (mStartedBT) {
						if ((mQueueAddress != null) && (mQueueState != null))
							requestWrite(mQueueAddress, mQueueState);
						else if (mRequestDiscovery && !mBtAdapter.isDiscovering())
							mBtAdapter.startDiscovery();
					}
				} else if (state == BluetoothAdapter.STATE_TURNING_OFF) {
					stopThreads();
					if (mUIInterface == null)
						RemoteAuthClientService.this.stopSelf();
				}
				// When discovery finds a device
			} else if (BluetoothDevice.ACTION_FOUND.equals(action)) {
				// Get the BluetoothDevice object from the Intent
				BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
				if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
					// connect if configured
					String address = device.getAddress();
					for (String d : mDevices) {
						String[] parts = RemoteAuthClientUI.parseDeviceString(d);
						if (parts[RemoteAuthClientUI.DEVICE_ADDRESS].equals(address)) {
							// if queued
							mDeviceFound = (mQueueAddress != null) && mQueueAddress.equals(address) && (mQueueState != null);
							break;
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
					requestWrite(mQueueAddress, mQueueState);
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
				requestWrite(address, Integer.toString(RemoteAuthClientUI.STATE_TOGGLE));
			} else if (AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(action)) {
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
			} else if (AppWidgetManager.ACTION_APPWIDGET_DELETED.equals(action)) {
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
		if (mBtAdapter.isEnabled()) {
			synchronized (mThreadLock) {
				if (mConnectThread != null)
					mConnectThread.shutdown();
				// attempt connect
				mConnectThread = new ConnectThread(address, state);
				mConnectThread.start();
			}
			mQueueAddress = null;
			mQueueState = null;
		} else {
			mQueueAddress = address;
			mQueueState = state;
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
		private String mState;

		public ConnectThread(String address, String state) {
			mState = state;
			mAddress = address;
		}

		public void run() {
			mBtAdapter.cancelDiscovery();
			BluetoothDevice device = mBtAdapter.getRemoteDevice(mAddress);
			try {
				mSocket = device.createRfcommSocketToServiceRecord(sRemoteAuthServerUUID);
				mSocket.connect();
			} catch (IOException e) {
				Log.d(TAG, e.toString());
				mMessage = "failed to get socket, or connect";
				mHandler.post(mRunnable);
				shutdown();
				return;
			}
			if (mState == null) {
				//pairing successful
				mPairedDevice = device.getName() + " " + mAddress;
				mHandler.post(mRunnable);
			} else {
				// Get the BluetoothSocket input and output streams
				try {
					inStream = mSocket.getInputStream();
					outStream = mSocket.getOutputStream();
				} catch (IOException e) {
					mMessage = "failed to get streams";
					mHandler.post(mRunnable);
					shutdown();
					return;
				}
				byte[] buffer = new byte[1024];
				int readBytes = -1;
				try {
					readBytes = inStream.read(buffer);
				} catch (IOException e1) {
					mMessage = "failed to read input stream";
					mHandler.post(mRunnable);
				}
				if (readBytes != -1) {
					// construct a string from the valid bytes in the buffer
					String message = new String(buffer, 0, readBytes);
					// listen for challenge, then process a response
					String challenge = null;
					if ((message.length() > 10) && (message.substring(0, 9).equals("challenge")))
						challenge = message.substring(10);
					// get passphrase
					String passphrase = null;
					for (String d : mDevices) {
						String[] parts = RemoteAuthClientUI.parseDeviceString(d);
						if (parts[RemoteAuthClientUI.DEVICE_ADDRESS].equals(mAddress)) {
							if ((passphrase = parts[RemoteAuthClientUI.DEVICE_PASSPHRASE]) != null) {
								if (challenge != null) {
									try {
										String request = getHashString(challenge + passphrase + mState);
										outStream.write(request.getBytes());
									} catch (NoSuchAlgorithmException e) {
										Log.e(TAG, e.toString());
										mMessage = "failed to get hash string";
										mHandler.post(mRunnable);
									} catch (UnsupportedEncodingException e) {
										Log.e(TAG, e.toString());
										mMessage = "failed to get hash string";
										mHandler.post(mRunnable);
									} catch (IOException e) {
										Log.e(TAG, e.toString());
										mMessage = "failed to write to output stream";
										mHandler.post(mRunnable);
									}
								} else {
									mMessage = "failed to receive a challenge";
									mHandler.post(mRunnable);
								}
							} else {
								mMessage = "no passphrase";
								mHandler.post(mRunnable);
							}
							break;
						}
					}
				} else {
					mMessage = "failed to read input stream";
					mHandler.post(mRunnable);
				}
			}
			shutdown();
		}

		public void shutdown() {
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
			mMessage = null;
			mHandler.post(mRunnable);
			mConnectThread = null;
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
