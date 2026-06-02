#!/usr/bin/env python3
"""
SMS Send Example - Termux Android Bridge

This example demonstrates how to send an SMS message using the TermuxBridge
Python client library.

Requirements:
    - Termux Bridge app installed on Android
    - HTTP server running on the Android device (default port 8080)
    - SEND_SMS permission granted to the app

Usage:
    python3 sms_send_example.py

Or interactively:
    python3 -i sms_send_example.py
    >>> send_sms("+351912345678", "Hello from Python!")
"""

from bridge_client import TermuxBridge


def send_sms(phone_number: str, message: str, debug: bool = True) -> bool:
    """
    Send an SMS message via Termux Bridge.

    Args:
        phone_number: Recipient phone number with country code
        message: SMS text content
        debug: Whether to print debug information

    Returns:
        True if message was sent successfully, False otherwise
    """
    bridge = TermuxBridge()

    if debug:
        print(f"[*] Sending SMS to: {phone_number}")
        print(f"[*] Message: {message}")
        print(f"[*] Bridge URL: {bridge.base_url}")

    try:
        # Check health first
        health = bridge.health()
        if debug:
            print(f"[+] Server health: {health}")

        # Send the SMS
        result = bridge.sms_send(phone_number, message)

        if debug:
            print(f"[+] SMS sent successfully!")
            print(f"    To: {result.get('to')}")
            print(f"    Parts: {result.get('parts', 1)}")

        return True

    except Exception as e:
        if debug:
            print(f"[-] Error sending SMS: {e}")
        return False


def main():
    """Interactive example with test numbers."""
    import sys

    # Demo: send to a test number
    # Replace with actual phone number for real testing
    TEST_NUMBER = "+351912345678"
    TEST_MESSAGE = "Hello from Termux Bridge Python client!"

    print("=" * 50)
    print("Termux Android Bridge - SMS Send Example")
    print("=" * 50)

    # Check if we have command line arguments
    if len(sys.argv) >= 3:
        phone_number = sys.argv[1]
        message = sys.argv[2]
        success = send_sms(phone_number, message)
        sys.exit(0 if success else 1)
    else:
        # Interactive mode
        print(f"\nDemo: Sending test SMS to {TEST_NUMBER}")
        print(f"Message: {TEST_MESSAGE}")
        print()

        choice = input("Proceed with sending? (y/n): ").strip().lower()
        if choice == 'y':
            success = send_sms(TEST_NUMBER, TEST_MESSAGE)
            if success:
                print("\n[+] SMS sent successfully!")
            else:
                print("\n[-] Failed to send SMS. Check the error above.")
        else:
            print("Cancelled.")


if __name__ == "__main__":
    main()