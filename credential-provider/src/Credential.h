#pragma once

#include <windows.h>
#include <credentialprovider.h>

#include <mutex>
#include <string>

namespace wake {

class PipeServer;

// Tile fields, in LogonUI's enumeration order.
enum FieldId {
    FI_TILE_IMAGE = 0,
    FI_LABEL = 1,
    FI_MESSAGE = 2,
    FI_PROVIDER_LABEL = 3,  // hidden; names the provider in "Sign-in options"
};
constexpr DWORD kFieldCount = 4;

// The field layout, shared with Provider's GetFieldDescriptorAt.
extern const CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR kFieldDescriptors[kFieldCount];

// The single tile shown while the lock screen is up. Holds the pending password and, when LogonUI
// asks, packs it for the locked user: a KERB_INTERACTIVE_UNLOCK_LOGON for a local account,
// CredPackAuthenticationBuffer for any other (Microsoft account, Azure AD, domain), as Microsoft's
// sample does. Advertises itself as ICredentialProviderCredential2 so it carries the user's SID.
// Holds a DLL reference for its whole life, so LogonUI can keep it after the provider is gone.
class Credential : public ICredentialProviderCredential2 {
  public:
    Credential(const std::wstring& qualifiedUserName, const std::wstring& userSid, bool isLocalUser);

    // IUnknown
    IFACEMETHODIMP QueryInterface(REFIID riid, void** ppv) override;
    IFACEMETHODIMP_(ULONG) AddRef() override;
    IFACEMETHODIMP_(ULONG) Release() override;

    // ICredentialProviderCredential
    IFACEMETHODIMP Advise(ICredentialProviderCredentialEvents* pcpce) override;
    IFACEMETHODIMP UnAdvise() override;
    IFACEMETHODIMP SetSelected(BOOL* pbAutoLogon) override;
    IFACEMETHODIMP SetDeselected() override;
    IFACEMETHODIMP GetFieldState(DWORD dwFieldID, CREDENTIAL_PROVIDER_FIELD_STATE* pcpfs,
                                 CREDENTIAL_PROVIDER_FIELD_INTERACTIVE_STATE* pcpfis) override;
    IFACEMETHODIMP GetStringValue(DWORD dwFieldID, LPWSTR* ppwsz) override;
    IFACEMETHODIMP GetBitmapValue(DWORD dwFieldID, HBITMAP* phbmp) override;
    IFACEMETHODIMP GetCheckboxValue(DWORD dwFieldID, BOOL* pbChecked, LPWSTR* ppwszLabel) override;
    IFACEMETHODIMP GetSubmitButtonValue(DWORD dwFieldID, DWORD* pdwAdjacentTo) override;
    IFACEMETHODIMP GetComboBoxValueCount(DWORD dwFieldID, DWORD* pcItems,
                                         DWORD* pdwSelectedItem) override;
    IFACEMETHODIMP GetComboBoxValueAt(DWORD dwFieldID, DWORD dwItem, LPWSTR* ppwszItem) override;
    IFACEMETHODIMP SetStringValue(DWORD dwFieldID, LPCWSTR pwz) override;
    IFACEMETHODIMP SetCheckboxValue(DWORD dwFieldID, BOOL bChecked) override;
    IFACEMETHODIMP SetComboBoxSelectedValue(DWORD dwFieldID, DWORD dwSelectedItem) override;
    IFACEMETHODIMP CommandLinkClicked(DWORD dwFieldID) override;
    IFACEMETHODIMP GetSerialization(CREDENTIAL_PROVIDER_GET_SERIALIZATION_RESPONSE* pcpgsr,
                                    CREDENTIAL_PROVIDER_CREDENTIAL_SERIALIZATION* pcpcs,
                                    LPWSTR* ppwszOptionalStatusText,
                                    CREDENTIAL_PROVIDER_STATUS_ICON* pcpsiOptionalStatusIcon) override;
    IFACEMETHODIMP ReportResult(NTSTATUS ntsStatus, NTSTATUS ntsSubstatus,
                                LPWSTR* ppwszOptionalStatusText,
                                CREDENTIAL_PROVIDER_STATUS_ICON* pcpsiOptionalStatusIcon) override;

    // ICredentialProviderCredential2
    IFACEMETHODIMP GetUserSid(LPWSTR* ppwszSid) override;

    // Called from the provider to stage / drop the password and to update the user. Thread-safe.
    void SetPassword(const std::wstring& password);
    void ClearPassword();
    void SetUser(const std::wstring& qualifiedUserName, const std::wstring& userSid, bool isLocalUser);
    // The provider attaches its running pipe server and detaches it (under our lock) before the
    // server stops or goes away, so the credential never calls into a dead PipeServer.
    void AttachPipe(PipeServer* pipe);
    void DetachPipe();

  private:
    ~Credential();
    // Caller holds mutex_. True while a phone request is in flight and its password is staged.
    bool RequestPendingLocked() const;

    LONG ref_ = 1;

    std::mutex mutex_;            // guards everything below (pipe thread vs LogonUI's thread)
    PipeServer* pipe_ = nullptr;  // not owned
    std::wstring userName_;
    std::wstring userSid_;
    bool isLocalUser_ = true;
    std::wstring password_;
};

}  // namespace wake
