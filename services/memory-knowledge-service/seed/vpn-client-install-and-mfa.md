# VPN client installation and MFA enrollment

## Installing the VPN client

Install the VPN client from the company software portal, not from the vendor website. Open the software portal, search for "VPN Client", and choose Install. The managed build is pre‑configured with the correct connection profiles, so you do not need to enter any server address by hand.

## Enrolling a multi-factor device before your first VPN login

Before your first VPN login you must enroll a multi‑factor authentication device. Go to the identity portal, open Security, and add an authenticator app or a hardware key. VPN login will keep failing until at least one MFA method is registered on your account.

## Approving the MFA prompt during VPN login

When you start the VPN you will get a push notification or a code request on your enrolled device. Approve it within 60 seconds. If no prompt arrives, open your authenticator app manually and type the current 6‑digit code into the VPN client.

## Replacing a lost or wiped MFA device for VPN

If you lost the phone that had your VPN authenticator, you cannot re‑enroll yourself. Contact the service desk to verify your identity and issue a temporary bypass code, then enroll the new device immediately in the identity portal.

## Supported platforms

The VPN client is supported on managed Windows and macOS laptops. Personal or unmanaged devices are not permitted to run the corporate VPN; use the browser‑based remote portal instead for limited web access.
