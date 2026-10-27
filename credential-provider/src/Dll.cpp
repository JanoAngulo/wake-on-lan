#include <windows.h>
#include <new>

#include <initguid.h>  // makes DEFINE_GUID in Guids.h allocate CLSID_WakeCredentialProvider here

#include "ClassFactory.h"
#include "Guids.h"

// Module handle, used by Credential to load the tile bitmap.
HINSTANCE g_hInst = nullptr;

// Outstanding COM objects and server locks. DllCanUnloadNow returns S_OK only at zero.
static LONG g_cDllRef = 0;

void WakeDllAddRef() {
    InterlockedIncrement(&g_cDllRef);
}

void WakeDllRelease() {
    InterlockedDecrement(&g_cDllRef);
}

STDAPI DllCanUnloadNow() {
    return (g_cDllRef == 0) ? S_OK : S_FALSE;
}

STDAPI DllGetClassObject(REFCLSID rclsid, REFIID riid, void** ppv) {
    try {
        if (!ppv) return E_POINTER;
        *ppv = nullptr;
        if (rclsid != CLSID_WakeCredentialProvider) return CLASS_E_CLASSNOTAVAILABLE;
        auto* factory = new (std::nothrow) wake::ClassFactory();
        if (!factory) return E_OUTOFMEMORY;
        HRESULT hr = factory->QueryInterface(riid, ppv);
        factory->Release();
        return hr;
    } catch (...) {
        return E_UNEXPECTED;
    }
}

BOOL WINAPI DllMain(HINSTANCE hInstance, DWORD dwReason, LPVOID) {
    if (dwReason == DLL_PROCESS_ATTACH) {
        g_hInst = hInstance;
        DisableThreadLibraryCalls(hInstance);
    }
    return TRUE;
}
