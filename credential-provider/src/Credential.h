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
};
constexpr DWORD kFieldCount = 3;

// The field layout, shared with Provider's GetFieldDescriptorAt.
extern const CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR kFieldDescriptors[kFieldCount];

// The single tile shown while the lock screen is up. Holds the pending password and, when LogonUI
// asks, packs it into a Kerberos unlock logon. Advertises itself as ICredentialProviderCredential2
// so it carries the locked user's SID.
class Credential : public ICredentialProviderCredential2 {
  public:
    Credential(const std::wstring& qualifiedUserName, const std::wstring& userSid, PipeServer* pipe);

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

    // Called from the provider (pipe thread) to stage / drop the password. Thread-safe.
    void SetPassword(const std::wstring& password);
    void ClearPassword();
    bool HasPassword();

  private:
    ~Credential();

    LONG ref_ = 1;
    PipeServer* pipe_ = nullptr;  // not owned; outlives the credential
    std::wstring userName_;
    std::wstring userSid_;

    std::mutex mutex_;              // guards password_ against the pipe thread vs LogonUI
    std::wstring password_;
    bool reported_ = false;
};

}  // namespace wake
