package com.piusvelte.remoteauthclient;

interface IRemoteAuthClientService {
	void setCallback(in IBinder uiBinder);
	void write(String address, String message, boolean secure, String passphrase);
	void requestDiscovery();
	void stop();
}