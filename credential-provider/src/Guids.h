#pragma once

#include <guiddef.h>

// CLSID of the Wake credential provider. Must match the installer's registry entries.
// {8C5D3B0E-6A41-4C7E-9F2B-5D1A7E3C9B04}
DEFINE_GUID(CLSID_WakeCredentialProvider, 0x8C5D3B0E, 0x6A41, 0x4C7E, 0x9F, 0x2B, 0x5D, 0x1A, 0x7E,
            0x3C, 0x9B, 0x04);
