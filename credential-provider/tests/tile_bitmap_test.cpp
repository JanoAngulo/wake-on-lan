// The tile bitmap must load with LoadImageW the way Credential::GetBitmapValue loads it; a format
// LoadImageW rejects leaves an empty slot under "Sign-in options".
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
    // LogonUI ignores alpha here, so the badge is opaque: a white glyph on the blue background.
    int white = 0, background = 0;
    if (ok) {
        const BYTE* px = static_cast<const BYTE*>(dib.dsBm.bmBits);
        for (int i = 0; i < 128 * 128; ++i) {
            const BYTE b = px[i * 4], g = px[i * 4 + 1], r = px[i * 4 + 2];
            if (r > 240 && g > 240 && b > 240) {
                ++white;
            } else if (b > r) {
                ++background;
            }
        }
    }
    DeleteObject(bmp);
    if (!ok || white == 0 || background == 0) {
        std::printf("FAIL: format ok=%d white=%d background=%d\n", ok, white, background);
        return 1;
    }
    std::printf("ok\n");
    return 0;
}
