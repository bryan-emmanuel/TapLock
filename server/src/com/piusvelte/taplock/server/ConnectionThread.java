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
package com.piusvelte.taplock.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.security.NoSuchAlgorithmException;

import javax.bluetooth.DiscoveryAgent;
import javax.bluetooth.LocalDevice;
import javax.bluetooth.UUID;
import javax.microedition.io.Connector;
import javax.microedition.io.StreamConnection;
import javax.microedition.io.StreamConnectionNotifier;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

public class ConnectionThread extends Thread {
	private static final String sSPD = "TapLock";
	private static final UUID sTapLockUUID = new UUID("0000110100001000800000805F9B34FB", false);
	private LocalDevice local = null;
	private StreamConnectionNotifier notifier;
	private StreamConnection connection = null;
	private InputStream inStream = null;
	private OutputStream outStream = null;

	public ConnectionThread() {
	}

	@SuppressWarnings("unchecked")
	@Override
	public void run() {
		TapLockServer.writeLog("ConnectionThread started");
		// retrieve the local Bluetooth device object
		// setup the server to listen for connection
		try {
			local = LocalDevice.getLocalDevice();
			local.setDiscoverable(DiscoveryAgent.GIAC);
			//			String url = "btspp://localhost:" + mRemoteAuthServerUUID.toString() + ";master=false;encrypt=false;authenticate=false;name=" + sSPD;
			String url = "btspp://localhost:" + sTapLockUUID.toString() + ";name=" + sSPD;
			notifier = (StreamConnectionNotifier) Connector.open(url);
		} catch (Exception e) {
			TapLockServer.writeLog("notifier init: " + e.getMessage());
			return;
		}
		JSONParser jsonParser = new JSONParser();
		while (notifier != null) {
			TapLockServer.writeLog("waiting for connection...");
			try {
				connection = notifier.acceptAndOpen();
			} catch (IOException e) {
				TapLockServer.writeLog("notifier.acceptAndOpen: " + e.getMessage());
				connection = null;
			}
			if (connection != null) {
				TapLockServer.writeLog("new connection...");
				try {
					inStream = connection.openInputStream();
					outStream = connection.openOutputStream();
				} catch (IOException e) {
					TapLockServer.writeLog("inStream and outStream open: " + e.getMessage());
				}
				if ((inStream != null) && (outStream != null)) {
					// send the challenge
					String challenge = Long.toString(System.currentTimeMillis());
					TapLockServer.writeLog("init challenge: " + challenge);
					JSONObject responseJObj = new JSONObject();
					responseJObj.put(TapLockServer.PARAM_CHALLENGE, challenge);
					String responseStr = responseJObj.toJSONString();
					try {
						outStream.write(responseStr.getBytes());
					} catch (IOException e) {
						TapLockServer.writeLog("outStream.write: " + e.getMessage());
					}
					// prepare to receive data
					byte[] buffer = new byte[1024];
					int readBytes = -1;
					try {
						readBytes = inStream.read(buffer);
					} catch (IOException e) {
						TapLockServer.writeLog("inStream.read: " + e.getMessage());
					}
					while (readBytes != -1) {
						responseJObj.clear();
						String requestStr = new String(buffer, 0, readBytes);
						TapLockServer.writeLog("request: " + requestStr);
						JSONObject requestJObj = null;
						try {
							requestJObj = (JSONObject) jsonParser.parse(requestStr);
						} catch (ParseException e) {
							TapLockServer.writeLog("jsonParser.parse: " + e.getMessage());
						}
						if (requestJObj != null) {
							if ((requestJObj != null) && requestJObj.containsKey(TapLockServer.PARAM_ACTION) && requestJObj.containsKey(TapLockServer.PARAM_HMAC)) {
								String requestAction = (String) requestJObj.get(TapLockServer.PARAM_ACTION);
								TapLockServer.writeLog("action: " + requestAction);
								String requestPassphrase = (String) requestJObj.get(TapLockServer.PARAM_PASSPHRASE);
								if (requestPassphrase == null)
									requestPassphrase = "";
								String requestHMAC = (String) requestJObj.get(TapLockServer.PARAM_HMAC);
								String validHMAC = null;
								try {
									validHMAC = TapLockServer.getHashString(challenge + TapLockServer.sPassphrase + requestAction + requestPassphrase);
								} catch (NoSuchAlgorithmException e) {
									TapLockServer.writeLog("getHashString: " + e.getMessage());
								} catch (UnsupportedEncodingException e) {
									TapLockServer.writeLog("getHashString: " + e.getMessage());
								}
								if (requestHMAC.equals(validHMAC)) {
									if (TapLockServer.ACTION_PASSPHRASE.equals(requestAction))
										TapLockServer.setPassphrase(requestPassphrase);
									else {
										if (TapLockServer.ACTION_TOGGLE.equals(requestAction))
											requestAction = TapLockServer.getToggleAction();
										String command = null;
										if (TapLockServer.ACTION_LOCK.equals(requestAction)) {
											if (TapLockServer.OS == TapLockServer.OS_NIX)
												command = "gnome-screensaver-command -a";
											else if (TapLockServer.OS == TapLockServer.OS_WIN)
												command = "rundll32.exe user32.dll, LockWorkStation";
										} else if (TapLockServer.ACTION_UNLOCK.equals(requestAction)) {
											if (TapLockServer.OS == TapLockServer.OS_NIX)
												command = "gnome-screensaver-command -d";
//											else if (TapLockServer.OS == TapLockServer.OS_WIN)
//												command = "rundll32.exe user32.dll, UnLockWorkStation";
										}
										if (command != null) {
											TapLockServer.writeLog("command: " + command);
											Process p = null;
											try {
												p = Runtime.getRuntime().exec(command);
											} catch (IOException e) {
												TapLockServer.writeLog("Runtime.getRuntime().exec: " + e.getMessage());
											}
											if (p != null) {
												BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream())); 
												String line = null;
												try {
													line = reader.readLine();
												} catch (IOException e) {
													TapLockServer.writeLog("reader.readLine: " + e.getMessage());
												} 
												while (line != null) { 
													TapLockServer.writeLog(line);
													try {
														line = reader.readLine();
													} catch (IOException e) {
														TapLockServer.writeLog("reader.readLine: " + e.getMessage());
													} 
												}
												if (TapLockServer.ACTION_LOCK.equals(requestAction))
													TapLockServer.sState = TapLockServer.STATE_LOCKED;
												else if (TapLockServer.ACTION_UNLOCK.equals(requestAction))
													TapLockServer.sState = TapLockServer.STATE_UNLOCKED;
											}
										}
									}
								} else {
									TapLockServer.writeLog("authentication failed");
									responseJObj.put(TapLockServer.PARAM_ERROR, "authentication failed");
								}
							} else {
								TapLockServer.writeLog("invalid request");
								responseJObj.put(TapLockServer.PARAM_ERROR, "invalid request");
							}
						} else {
							TapLockServer.writeLog("failed to parse request");
							responseJObj.put(TapLockServer.PARAM_ERROR, "failed to parse request");
						}
						// send the new challenge
						challenge = Long.toString(System.currentTimeMillis());
						TapLockServer.writeLog("next challenge: " + challenge);
						responseJObj.put(TapLockServer.PARAM_CHALLENGE, challenge);
						responseStr = responseJObj.toJSONString();
						try {
							outStream.write(responseStr.getBytes());
						} catch (IOException e) {
							TapLockServer.writeLog("outStream.write: " + e.getMessage());
						}
						try {
							readBytes = inStream.read(buffer);
						} catch (IOException e) {
							TapLockServer.writeLog("inStream.read: " + e.getMessage());
						}
					}
					if (inStream != null) {
						try {
							inStream.close();
						} catch (IOException e) {
							TapLockServer.writeLog("inStream.close: " + e.getMessage());
						}
					}
					if (outStream != null) {
						try {
							outStream.close();
						} catch (IOException e) {
							TapLockServer.writeLog("outStream.close: " + e.getMessage());
						}
					}
				}
				if (connection != null) {
					try {
						connection.close();
					} catch (IOException e) {
						TapLockServer.writeLog("connection.close: " + e.getMessage());
					}
					connection = null;
				}
			}
		}
	}

	public void shutdown() {
		if (notifier != null) {
			try {
				notifier.close();
			} catch (IOException e) {
				TapLockServer.writeLog("notifier.close: " + e.getMessage());
			}
			notifier = null;
		}
		if (inStream != null) {
			try {
				inStream.close();
			} catch (IOException e) {
				TapLockServer.writeLog("inStream.close: " + e.getMessage());
			}
		}
		if (outStream != null) {
			try {
				outStream.close();
			} catch (IOException e) {
				TapLockServer.writeLog("outStream.close: " + e.getMessage());
			}
		}
		if (connection != null) {
			try {
				connection.close();
			} catch (IOException e) {
				TapLockServer.writeLog("connection.close: " + e.getMessage());
			}
			connection = null;
		}
		if (local != null)
			local = null;
	}
}
