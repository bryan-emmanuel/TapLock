package com.piusvelte.remoteauthclient;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class RemoteAuthClientReceiver extends BroadcastReceiver {

	@Override
	public void onReceive(Context context, Intent intent) {
		String action = intent.getAction();
		if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
			int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF);
			if (state == BluetoothAdapter.STATE_ON) {
				context.startService(intent.setClass(context, RemoteAuthClientService.class));
			}
		}
	}

}
