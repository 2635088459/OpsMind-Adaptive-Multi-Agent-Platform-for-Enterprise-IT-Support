# Email and Outlook setup

## Setting up Outlook on a new laptop

On a managed laptop, open Outlook and it will detect your mailbox automatically from your signed‑in account. Enter your password and approve the multi‑factor prompt if asked. Do not add the account as an IMAP or POP account; only the automatic Exchange setup is supported.

## Outlook keeps asking for a password

If Outlook repeatedly prompts for your password, your cached credentials are stale. Close Outlook, open the Windows Credential Manager, remove any entries that start with "MicrosoftOffice" or "MS.Outlook", then reopen Outlook and sign in once. On macOS, remove the "Exchange" entries from Keychain Access instead.

## Email on your phone

Install the official mobile mail app from the company software portal and sign in with your work account. Personal mail apps are not allowed to connect to company email. The mobile app enforces a device passcode and can be remotely wiped if the phone is lost.

## Sent mail not appearing on other devices

If messages you send from one device do not show up in Sent on another, the account is probably misconfigured as POP. Remove it and re‑add it using the automatic Exchange setup, which keeps Sent, Drafts, and folders in sync everywhere.

## Free up space so Outlook works offline

Outlook stores an offline copy of recent mail. If your laptop disk is nearly full, Outlook may stop syncing. Clear space, then in Outlook go to Account Settings and reduce "Mail to keep offline" to 3 months.
