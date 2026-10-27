// The tile bitmap must load with LoadImageW the way Credential::GetBitmapValue loads it; a format
// LoadImageW rejects leaves an empty, transparent slot under "Sign-in options".
#include <windows.h>

#include <cstdio>

int main() {
    HBITMAP bmp = static_cast<HBITMAP>(
        LoadImageW(nullptr, WAKE_TILE_BMP, IMAGE_BITMAP, 0, 0, LR_LOADFROMFILE | LR_CREATEDIBSECTION));
    if (!bmp) {
        std::printf("FAIL: LoadImageW error %lu\n", GetLastError());
        return 1;
    }
    DIBSECTION dib{};
    const bool ok = GetObjectW(bmp, sizeof(dib), &dib) == sizeof(dib) && dib.dsBm.bmBitsPixel == 32 &&
                    dib.dsBm.bmWidth == 128 && dib.dsBm.bmHeight == 128;
    // Some pixels fully opaque (the glyph), some fully transparent (around it).
    int opaque = 0, clear = 0;
    if (ok) {
        const BYTE* px = static_cast<const BYTE*>(dib.dsBm.bmBits);
        for (int i = 0; i < 128 * 128; ++i) {
            if (px[i * 4 + 3] == 255) ++opaque;
            if (px[i * 4 + 3] == 0) ++clear;
        }
    }
    DeleteObject(bmp);
    if (!ok || opaque == 0 || clear == 0) {
        std::printf("FAIL: format ok=%d opaque=%d transparent=%d\n", ok, opaque, clear);
        return 1;
    }
    std::printf("ok\n");
    return 0;
}
