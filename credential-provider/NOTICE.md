# Third-party notices

The structure of this credential provider (the `ICredentialProvider` /
`ICredentialProviderCredential2` shape, the field-descriptor tables, and the
`KERB_INTERACTIVE_UNLOCK_LOGON` packing helpers in `UnlockLogon.cpp`, and the
local versus Microsoft-account serialization and password protection in
`Credential.cpp`) follows
Microsoft's **Windows-classic-samples** `Samples/CredentialProvider` sample.
The code here was written from the public `credentialprovider.h` API rather than
copied, but it mirrors that sample closely enough that its licence is reproduced
below for safety.

## Windows-classic-samples (MIT License)

Copyright (c) Microsoft Corporation.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
