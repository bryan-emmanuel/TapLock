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
#include <iostream>
#include <WinSock2.h>
#include <WS2tcpip.h>
#include <stdio.h>
#include <WinNT.h>
#include "TapLockListener.h"
#include "TapLockProvider.h"

using namespace std;

TapLockListener::TapLockListener():
    _cRef(1),
	_pProvider(NULL),
	_ListenSocket(NULL)
{
}

TapLockListener::~TapLockListener()
{
	if (_ListenSocket)
		closesocket(_ListenSocket);
	if (_pProvider)
	{
		_pProvider->Release();
		_pProvider = NULL;
	}
	WSACleanup();
}

HRESULT TapLockListener::Initialize(TapLockProvider *pProvider)
{
	HRESULT hr = S_OK;
	if (_pProvider)
		_pProvider->Release();
	_pProvider = pProvider;
	_pProvider->AddRef();
	if (_ListenSocket)
		closesocket(_ListenSocket);
	if (GetServerSocket())
	{
		HANDLE hThread = ::CreateThread(NULL, 0, TapLockListener::ListenThread, (void*) this, 0, NULL);
		if (!hThread)
			hr = HRESULT_FROM_WIN32(::GetLastError());
	}
	else
		hr = E_FAIL;
	return hr;
}

bool TapLockListener::GetServerSocket()
{
	WSADATA _wsadata;
	if (WSAStartup(MAKEWORD(2, 2), &_wsadata) != NO_ERROR)
		WSACleanup();
	else
	{
		_ListenSocket = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
		if (_ListenSocket == INVALID_SOCKET)
		{
			_ListenSocket = NULL;
			WSACleanup();
		}
		else
		{
			struct sockaddr_in server;
			server.sin_family = AF_INET;
			server.sin_addr.s_addr = inet_addr(DEFAULT_IP);
			server.sin_port = htons(DEFAULT_PORT);
			if (bind(_ListenSocket,(SOCKADDR*) &server, sizeof(server)) == SOCKET_ERROR)
			{
				closesocket(_ListenSocket);
				_ListenSocket = NULL;
				WSACleanup();
			}
			else
			{
				if (listen(_ListenSocket, SOMAXCONN) == SOCKET_ERROR)
				{
					closesocket(_ListenSocket);
					_ListenSocket = NULL;
					WSACleanup();
				}
			}
		}
	}
	return HasServerSocket();
}

bool TapLockListener::HasServerSocket()
{
	return _ListenSocket && (_ListenSocket != INVALID_SOCKET);
}

DWORD WINAPI TapLockListener::ListenThread(void* Param)
{
	TapLockListener* _pTapLockListener = (TapLockListener*) Param;
	while (_pTapLockListener->HasServerSocket())
	{
		SOCKET clientSocket = accept(_pTapLockListener->_ListenSocket, NULL, NULL);
		if (clientSocket != INVALID_SOCKET)
		{
			// send the version
			send(clientSocket, TAPLOCK_VERSION, 1, 0);
			char recvbuf[BUFLEN];
			int readBytes;
			do
			{
				readBytes = recv(clientSocket, recvbuf, BUFLEN, 0);
				// only receiving credentials
				if (readBytes == BUFLEN)
				{
					//setlocale(LC_ALL, LOC_EN);
					char cUsername[USERLEN + 1];
					for (int i = 0; i < USERLEN; i++)
						cUsername[i] = recvbuf[i];
					cUsername[USERLEN] = 0;//null terminated
					char cPassword[PASSLEN + 1];
					for (int i = 0; i < PASSLEN; i++)
						cPassword[i] = recvbuf[USERLEN + i];
					cPassword[PASSLEN] = 0;//null terminated
					LPWSTR wcUsername;
					if (cUsername)
					{
						size_t sChanged = 0;
						size_t sSize = 1 + strlen(cUsername);
						wcUsername = new wchar_t[sSize];
						mbstowcs_s(&sChanged, wcUsername, sSize, cUsername, _TRUNCATE);
					}
					else
						wcUsername = NULL;
					LPWSTR wcPassword;
					if (cPassword)
					{
						size_t sChanged = 0;
						size_t sSize = 1 + strlen(cPassword);
						wcPassword = new wchar_t[sSize];
						mbstowcs_s(&sChanged, wcPassword, sSize, cPassword, _TRUNCATE);
					}
					else
						wcPassword = NULL;
					HRESULT hr = _pTapLockListener->_pProvider->OnCredentialsReceived(wcUsername, wcPassword);
					if (SUCCEEDED(hr))
					{
						send(clientSocket, GOOD_SOCKET_REQUEST, 1, 0);
					}
					else
					{
						send(clientSocket, BAD_SOCKET_REQUEST, 1, 0);
					}
					readBytes = -1;
				}
				else
					send(clientSocket, BAD_SOCKET_REQUEST, 1, 0);
			}
			while (readBytes > 0);
		}
		if (clientSocket != NULL)
		{
			closesocket(clientSocket);
			clientSocket = NULL;
		}
	}
	return 0;
}