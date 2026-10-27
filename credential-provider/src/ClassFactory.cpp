#include "ClassFactory.h"
#include "Trace.h"

#include <new>

#include "Provider.h"

// Keep the DLL loaded while the factory lives (defined in Dll.cpp).
void WakeDllAddRef();
void WakeDllRelease();

namespace wake {

ClassFactory::ClassFactory() {
    WakeDllAddRef();
}

ClassFactory::~ClassFactory() {
    WakeDllRelease();
}

IFACEMETHODIMP ClassFactory::QueryInterface(REFIID riid, void** ppv) {
    try {
        if (!ppv) return E_POINTER;
        if (riid == IID_IUnknown || riid == IID_IClassFactory) {
            *ppv = static_cast<IClassFactory*>(this);
            AddRef();
            return S_OK;
        }
        *ppv = nullptr;
        return E_NOINTERFACE;
    } catch (...) {
        return E_UNEXPECTED;
    }
}

IFACEMETHODIMP_(ULONG) ClassFactory::AddRef() {
    return InterlockedIncrement(&ref_);
}

IFACEMETHODIMP_(ULONG) ClassFactory::Release() {
    LONG n = InterlockedDecrement(&ref_);
    if (n == 0) delete this;
    return n;
}

IFACEMETHODIMP ClassFactory::CreateInstance(IUnknown* pUnkOuter, REFIID riid, void** ppv) {
    try {
        if (!ppv) return E_POINTER;
        *ppv = nullptr;
        if (pUnkOuter) return CLASS_E_NOAGGREGATION;
        auto* provider = new (std::nothrow) Provider();
        if (!provider) return E_OUTOFMEMORY;
        HRESULT hr = provider->QueryInterface(riid, ppv);
        Trace(L"CreateInstance hr=0x%08X", static_cast<unsigned>(hr));
        provider->Release();
        return hr;
    } catch (...) {
        return E_UNEXPECTED;
    }
}

IFACEMETHODIMP ClassFactory::LockServer(BOOL fLock) {
    try {
        if (fLock)
            WakeDllAddRef();
        else
            WakeDllRelease();
        return S_OK;
    } catch (...) {
        return E_UNEXPECTED;
    }
}

}  // namespace wake
