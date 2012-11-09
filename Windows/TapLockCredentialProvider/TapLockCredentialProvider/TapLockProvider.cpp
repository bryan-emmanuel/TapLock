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
#include <windows.h>
#include <strsafe.h>
#include "helpers.h"
#include "guid.h"
#include "Dll.h"
#include "TapLockProvider.h"
#include "TapLockCredential.h"
#include "TapLockListener.h"

TapLockProvider::TapLockProvider():
    _cRef(1),
	_pcpe(NULL),
	_upAdviseContext(NULL),
	_bHasCredentials(false),
	_ptlc(NULL),
	_ptll(NULL)
{
    DllAddRef();
}

TapLockProvider::~TapLockProvider()
{
	if (_ptlc != NULL)
	{
		_ptlc->Release();
	}
    if (_ptll != NULL)
	{
        delete _ptll;
	}
    DllRelease();
}

HRESULT TapLockProvider::OnCredentialsReceived(PWSTR wcUsername, PWSTR wcPassword)
{
	// store credentials and trigger LogonUI to re-enumerate tiles
	HRESULT hr = S_OK;
    if ((_pcpe != NULL) && _upAdviseContext)
	{
		hr = _ptlc->SetCredentials(wcUsername, wcPassword);
		if (SUCCEEDED(hr))
		{
			_bHasCredentials = true;
			hr = _pcpe->CredentialsChanged(_upAdviseContext);
		}
	}
	return hr;
}

// SetUsageScenario is the provider's cue that it's going to be asked for tiles
// in a subsequent call.  
//
// This sample only handles the logon and unlock scenarios as those are the most common.
HRESULT TapLockProvider::SetUsageScenario(
    CREDENTIAL_PROVIDER_USAGE_SCENARIO cpus,
    DWORD dwFlags
    )
{
    UNREFERENCED_PARAMETER(dwFlags);
    HRESULT hr;
    // Decide which scenarios to support here. Returning E_NOTIMPL simply tells the caller
    // that we're not designed for that scenario.
    switch (cpus)
    {
    case CPUS_LOGON:
    case CPUS_UNLOCK_WORKSTATION:
		hr = S_OK;
		if (!_ptll)
		{
			// open the socket for TapLockServer to connect and pass credentials
			_ptll = new TapLockListener();
			_ptll->Initialize(this);
		}
		// if new credentials are passed in, release the stale credential
		if (!_ptlc)
		{
			_cpus = cpus;
			_ptlc = new TapLockCredential();
			if (_ptlc != NULL)
				hr = _ptlc->Initialize(_cpus, s_rgCredProvFieldDescriptors, s_rgFieldStatePairs);
			else
				hr = E_OUTOFMEMORY;
			if (!SUCCEEDED(hr))
			{
				_ptlc->Release();
				_ptlc = NULL;
			}
		}
		break;
    case CPUS_CREDUI:
    case CPUS_CHANGE_PASSWORD:
        hr = E_NOTIMPL;
        break;
    default:
        hr = E_INVALIDARG;
        break;
    }
    return hr;
}

STDMETHODIMP TapLockProvider::SetSerialization(
    const CREDENTIAL_PROVIDER_CREDENTIAL_SERIALIZATION* pcpcs
    )
{
	UNREFERENCED_PARAMETER(pcpcs);
	return E_NOTIMPL;
}

// Called by LogonUI to give you a callback.  Providers often use the callback if they
// some event would cause them to need to change the set of tiles that they enumerated
HRESULT TapLockProvider::Advise(
    ICredentialProviderEvents* pcpe,
    UINT_PTR upAdviseContext
    )
{
    if (_pcpe)
        _pcpe->Release();
    _pcpe = pcpe;
    _pcpe->AddRef();
    _upAdviseContext = upAdviseContext;
    return S_OK;
}

// Called by LogonUI when the ICredentialProviderEvents callback is no longer valid.
HRESULT TapLockProvider::UnAdvise()
{
    if (_pcpe)
        _pcpe->Release();
    _upAdviseContext = NULL;
    return S_OK;
}

// Called by LogonUI to determine the number of fields in your tiles.  This
// does mean that all your tiles must have the same number of fields.
// This number must include both visible and invisible fields. If you want a tile
// to have different fields from the other tiles you enumerate for a given usage
// scenario you must include them all in this count and then hide/show them as desired 
// using the field descriptors.
HRESULT TapLockProvider::GetFieldDescriptorCount(
    DWORD* pdwCount
    )
{
    *pdwCount = SFI_NUM_FIELDS;
    return S_OK;
}
// Gets the field descriptor for a particular field
HRESULT TapLockProvider::GetFieldDescriptorAt(
    DWORD dwIndex, 
    CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR** ppcpfd
    )
{    
    HRESULT hr;
    // Verify dwIndex is a valid field.
    if ((dwIndex < SFI_NUM_FIELDS) && ppcpfd)
        hr = FieldDescriptorCoAllocCopy(s_rgCredProvFieldDescriptors[dwIndex], ppcpfd);
    else
        hr = E_INVALIDARG;
    return hr;
}

HRESULT TapLockProvider::GetCredentialCount(
    DWORD* pdwCount,
    DWORD* pdwDefault,
    BOOL* pbAutoLogonWithDefault
    )
{
	HRESULT hr = S_OK;
    *pdwCount = _ptlc ? 1 : 0; 
    if (*pdwCount > 0)
    {
		if (_bHasCredentials)
		{
			*pdwDefault = 0;
			*pbAutoLogonWithDefault = true;
			_bHasCredentials = false;
		}
		else
		{
			*pdwDefault = CREDENTIAL_PROVIDER_NO_DEFAULT;
			*pbAutoLogonWithDefault = false;
		}
    }
    else
    {
        // no tiles, clear out out params
        *pdwDefault = CREDENTIAL_PROVIDER_NO_DEFAULT;
        *pbAutoLogonWithDefault = FALSE;
        hr = E_FAIL;
    }
    return hr;
}

// Returns the credential at the index specified by dwIndex. This function is called by logonUI to enumerate
// the tiles.
HRESULT TapLockProvider::GetCredentialAt(
    DWORD dwIndex, 
    ICredentialProviderCredential** ppcpc
    )
{
    HRESULT hr = S_OK;
	if ((dwIndex == 0) && ppcpc && _ptlc)
		hr = _ptlc->QueryInterface(IID_ICredentialProviderCredential, reinterpret_cast<void**>(ppcpc));
    else
        hr = E_INVALIDARG;
    return hr;
}

HRESULT TapLockProvider_CreateInstance(REFIID riid, void** ppv)
{
    HRESULT hr = S_OK;
    TapLockProvider* ptlp = new TapLockProvider();
    if (ptlp != NULL)
    {
        hr = ptlp->QueryInterface(riid, ppv);
        ptlp->Release();
    }
    else
        hr = E_OUTOFMEMORY;
    return hr;
}

