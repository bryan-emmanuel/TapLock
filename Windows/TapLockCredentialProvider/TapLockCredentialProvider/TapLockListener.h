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
#pragma once

#pragma comment(lib, "Ws2_32.lib")

#define DEFAULT_PORT 1491
#define DEFAULT_IP "127.0.0.1"
#define BUFLEN 64
#define USERLEN 32
#define PASSLEN 32

const char TAPLOCK_VERSION[] = {"1"};
const char BAD_SOCKET_REQUEST[] = {"1"};
const char GOOD_SOCKET_REQUEST[] = {"0"};

class TapLockProvider;

class TapLockListener
{
public:
    STDMETHOD_(ULONG, AddRef)()
    {
        return _cRef++;
    }
    
    STDMETHOD_(ULONG, Release)()
    {
        LONG cRef = _cRef--;
        if (!cRef)
        {
            delete this;
        }
        return cRef;
    }
	TapLockListener(void);
	~TapLockListener(void);
	HRESULT Initialize(TapLockProvider *pProvider);
	HRESULT TapLockListener::StartListener(void);
private:
    LONG            _cRef;
	TapLockProvider *_pProvider;
	SOCKET          _ListenSocket;
	static DWORD WINAPI ListenThread(void*);
	DWORD Listen(void);
	bool GetServerSocket(void);
	bool HasServerSocket(void);
};