/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Original file: /home/bemmanuel/Documents/development/android/TapLock/client/core/src/com/piusvelte/taplock/client/core/ITapLockUI.aidl
 */
package com.piusvelte.taplock.client.core;
public interface ITapLockUI extends android.os.IInterface
{
/** Local-side IPC implementation stub class. */
public static abstract class Stub extends android.os.Binder implements com.piusvelte.taplock.client.core.ITapLockUI
{
private static final java.lang.String DESCRIPTOR = "com.piusvelte.taplock.client.core.ITapLockUI";
/** Construct the stub at attach it to the interface. */
public Stub()
{
this.attachInterface(this, DESCRIPTOR);
}
/**
 * Cast an IBinder object into an com.piusvelte.taplock.client.core.ITapLockUI interface,
 * generating a proxy if needed.
 */
public static com.piusvelte.taplock.client.core.ITapLockUI asInterface(android.os.IBinder obj)
{
if ((obj==null)) {
return null;
}
android.os.IInterface iin = (android.os.IInterface)obj.queryLocalInterface(DESCRIPTOR);
if (((iin!=null)&&(iin instanceof com.piusvelte.taplock.client.core.ITapLockUI))) {
return ((com.piusvelte.taplock.client.core.ITapLockUI)iin);
}
return new com.piusvelte.taplock.client.core.ITapLockUI.Stub.Proxy(obj);
}
public android.os.IBinder asBinder()
{
return this;
}
@Override public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException
{
switch (code)
{
case INTERFACE_TRANSACTION:
{
reply.writeString(DESCRIPTOR);
return true;
}
case TRANSACTION_setMessage:
{
data.enforceInterface(DESCRIPTOR);
java.lang.String _arg0;
_arg0 = data.readString();
this.setMessage(_arg0);
reply.writeNoException();
return true;
}
case TRANSACTION_setUnpairedDevice:
{
data.enforceInterface(DESCRIPTOR);
java.lang.String _arg0;
_arg0 = data.readString();
this.setUnpairedDevice(_arg0);
reply.writeNoException();
return true;
}
case TRANSACTION_setDiscoveryFinished:
{
data.enforceInterface(DESCRIPTOR);
this.setDiscoveryFinished();
reply.writeNoException();
return true;
}
case TRANSACTION_setStateFinished:
{
data.enforceInterface(DESCRIPTOR);
boolean _arg0;
_arg0 = (0!=data.readInt());
this.setStateFinished(_arg0);
reply.writeNoException();
return true;
}
case TRANSACTION_setPairingResult:
{
data.enforceInterface(DESCRIPTOR);
java.lang.String _arg0;
_arg0 = data.readString();
java.lang.String _arg1;
_arg1 = data.readString();
this.setPairingResult(_arg0, _arg1);
reply.writeNoException();
return true;
}
case TRANSACTION_setPassphrase:
{
data.enforceInterface(DESCRIPTOR);
java.lang.String _arg0;
_arg0 = data.readString();
java.lang.String _arg1;
_arg1 = data.readString();
this.setPassphrase(_arg0, _arg1);
reply.writeNoException();
return true;
}
}
return super.onTransact(code, data, reply, flags);
}
private static class Proxy implements com.piusvelte.taplock.client.core.ITapLockUI
{
private android.os.IBinder mRemote;
Proxy(android.os.IBinder remote)
{
mRemote = remote;
}
public android.os.IBinder asBinder()
{
return mRemote;
}
public java.lang.String getInterfaceDescriptor()
{
return DESCRIPTOR;
}
public void setMessage(java.lang.String message) throws android.os.RemoteException
{
android.os.Parcel _data = android.os.Parcel.obtain();
android.os.Parcel _reply = android.os.Parcel.obtain();
try {
_data.writeInterfaceToken(DESCRIPTOR);
_data.writeString(message);
mRemote.transact(Stub.TRANSACTION_setMessage, _data, _reply, 0);
_reply.readException();
}
finally {
_reply.recycle();
_data.recycle();
}
}
public void setUnpairedDevice(java.lang.String device) throws android.os.RemoteException
{
android.os.Parcel _data = android.os.Parcel.obtain();
android.os.Parcel _reply = android.os.Parcel.obtain();
try {
_data.writeInterfaceToken(DESCRIPTOR);
_data.writeString(device);
mRemote.transact(Stub.TRANSACTION_setUnpairedDevice, _data, _reply, 0);
_reply.readException();
}
finally {
_reply.recycle();
_data.recycle();
}
}
public void setDiscoveryFinished() throws android.os.RemoteException
{
android.os.Parcel _data = android.os.Parcel.obtain();
android.os.Parcel _reply = android.os.Parcel.obtain();
try {
_data.writeInterfaceToken(DESCRIPTOR);
mRemote.transact(Stub.TRANSACTION_setDiscoveryFinished, _data, _reply, 0);
_reply.readException();
}
finally {
_reply.recycle();
_data.recycle();
}
}
public void setStateFinished(boolean pass) throws android.os.RemoteException
{
android.os.Parcel _data = android.os.Parcel.obtain();
android.os.Parcel _reply = android.os.Parcel.obtain();
try {
_data.writeInterfaceToken(DESCRIPTOR);
_data.writeInt(((pass)?(1):(0)));
mRemote.transact(Stub.TRANSACTION_setStateFinished, _data, _reply, 0);
_reply.readException();
}
finally {
_reply.recycle();
_data.recycle();
}
}
public void setPairingResult(java.lang.String name, java.lang.String address) throws android.os.RemoteException
{
android.os.Parcel _data = android.os.Parcel.obtain();
android.os.Parcel _reply = android.os.Parcel.obtain();
try {
_data.writeInterfaceToken(DESCRIPTOR);
_data.writeString(name);
_data.writeString(address);
mRemote.transact(Stub.TRANSACTION_setPairingResult, _data, _reply, 0);
_reply.readException();
}
finally {
_reply.recycle();
_data.recycle();
}
}
public void setPassphrase(java.lang.String address, java.lang.String passphrase) throws android.os.RemoteException
{
android.os.Parcel _data = android.os.Parcel.obtain();
android.os.Parcel _reply = android.os.Parcel.obtain();
try {
_data.writeInterfaceToken(DESCRIPTOR);
_data.writeString(address);
_data.writeString(passphrase);
mRemote.transact(Stub.TRANSACTION_setPassphrase, _data, _reply, 0);
_reply.readException();
}
finally {
_reply.recycle();
_data.recycle();
}
}
}
static final int TRANSACTION_setMessage = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
static final int TRANSACTION_setUnpairedDevice = (android.os.IBinder.FIRST_CALL_TRANSACTION + 1);
static final int TRANSACTION_setDiscoveryFinished = (android.os.IBinder.FIRST_CALL_TRANSACTION + 2);
static final int TRANSACTION_setStateFinished = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3);
static final int TRANSACTION_setPairingResult = (android.os.IBinder.FIRST_CALL_TRANSACTION + 4);
static final int TRANSACTION_setPassphrase = (android.os.IBinder.FIRST_CALL_TRANSACTION + 5);
}
public void setMessage(java.lang.String message) throws android.os.RemoteException;
public void setUnpairedDevice(java.lang.String device) throws android.os.RemoteException;
public void setDiscoveryFinished() throws android.os.RemoteException;
public void setStateFinished(boolean pass) throws android.os.RemoteException;
public void setPairingResult(java.lang.String name, java.lang.String address) throws android.os.RemoteException;
public void setPassphrase(java.lang.String address, java.lang.String passphrase) throws android.os.RemoteException;
}
