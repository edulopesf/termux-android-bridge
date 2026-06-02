"""
Termux Android Bridge - Python HTTP Client Library

A simple Python library to interact with the Termux Bridge Android app
via HTTP endpoints running on localhost.

Usage:
    from bridge_client import TermuxBridge

    bridge = TermuxBridge()
    result = bridge.sms_send("+351912345678", "Hello!")
"""

import requests
from typing import Optional, Dict, Any


class TermuxBridge:
    """Python client for Termux Android Bridge HTTP server."""

    DEFAULT_HOST = "127.0.0.1"
    DEFAULT_PORT = 8080

    def __init__(self, host: str = DEFAULT_HOST, port: int = DEFAULT_PORT):
        """
        Initialize the bridge client.

        Args:
            host: The host where the Termux Bridge server is running (default: 127.0.0.1)
            port: The port where the Termux Bridge server is listening (default: 8080)
        """
        self.base_url = f"http://{host}:{port}"

    def _post(self, endpoint: str, data: Dict[str, Any]) -> Dict[str, Any]:
        """
        Internal helper to make POST requests to the bridge server.

        Args:
            endpoint: The API endpoint (e.g., "/sms/send")
            data: Dictionary to send as JSON body

        Returns:
            Parsed JSON response from server

        Raises:
            requests.RequestException: On network errors
            ValueError: On invalid JSON response
        """
        url = f"{self.base_url}{endpoint}"
        response = requests.post(url, json=data, timeout=30)
        response.raise_for_status()
        return response.json()

    def _get(self, endpoint: str) -> Dict[str, Any]:
        """
        Internal helper to make GET requests to the bridge server.

        Args:
            endpoint: The API endpoint (e.g., "/health")

        Returns:
            Parsed JSON response from server

        Raises:
            requests.RequestException: On network errors
            ValueError: On invalid JSON response
        """
        url = f"{self.base_url}{endpoint}"
        response = requests.get(url, timeout=30)
        response.raise_for_status()
        return response.json()

    # -------------------------------------------------------------------------
    # SMS Operations
    # -------------------------------------------------------------------------

    def sms_send(self, to: str, message: str) -> Dict[str, Any]:
        """
        Send an SMS message.

        Args:
            to: Recipient phone number with country code (e.g., "+351912345678")
            message: SMS text content

        Returns:
            dict with keys:
                - success (bool): True if message was sent
                - to (str): Recipient number
                - parts (int): Number of SMS parts sent (multi-part split)

        Raises:
            requests.HTTPError: If server returns error status code

        Example:
            >>> bridge = TermuxBridge()
            >>> result = bridge.sms_send("+351912345678", "Hello from Termux!")
            >>> print(result)
            {'success': True, 'to': '+351912345678', 'parts': 1}
        """
        return self._post("/sms/send", {"to": to, "message": message})

    # -------------------------------------------------------------------------
    # Notification Operations
    # -------------------------------------------------------------------------

    def notification(
        self,
        title: str = "Alert",
        body: str = "",
        channel: str = "alerts",
        priority: int = 0
    ) -> Dict[str, Any]:
        """
        Send a native notification to the Android notification bar.

        Args:
            title: Notification title
            body: Notification body text
            channel: Channel name ("alerts", "info", "silent")
            priority: Priority level (Android NotificationCompat priority values)

        Returns:
            dict with keys:
                - success (bool): True if notification was sent

        Raises:
            requests.HTTPError: If server returns error status code
        """
        return self._post("/notification", {
            "title": title,
            "body": body,
            "channel": channel,
            "priority": priority
        })

    # -------------------------------------------------------------------------
    # Text-to-Speech Operations
    # -------------------------------------------------------------------------

    def tts_speak(
        self,
        text: str,
        lang: str = "pt-PT",
        rate: float = 1.0
    ) -> Dict[str, Any]:
        """
        Make the Android device speak text using TTS engine.

        Args:
            text: Text to speak
            lang: Language code (e.g., "pt-PT", "en-US", "es-ES")
            rate: Speech rate (1.0 = normal speed, 0.5 = half, 2.0 = double)

        Returns:
            dict with keys:
                - success (bool): True if TTS was triggered
                - text (str): The text that will be spoken

        Raises:
            requests.HTTPError: If server returns error status code
        """
        return self._post("/tts", {
            "text": text,
            "lang": lang,
            "rate": rate
        })

    # -------------------------------------------------------------------------
    # Clipboard Operations
    # -------------------------------------------------------------------------

    def clipboard_get(self) -> Dict[str, Any]:
        """
        Read the current Android clipboard content.

        Returns:
            dict with keys:
                - content (str): Current clipboard text

        Raises:
            requests.HTTPError: If server returns error status code
        """
        return self._get("/clipboard")

    def clipboard_set(self, content: str) -> Dict[str, Any]:
        """
        Set the Android clipboard content.

        Args:
            content: Text to copy to clipboard

        Returns:
            dict with keys:
                - success (bool): True if clipboard was set

        Raises:
            requests.HTTPError: If server returns error status code
        """
        return self._post("/clipboard", {"content": content})

    # -------------------------------------------------------------------------
    # Health Check
    # -------------------------------------------------------------------------

    def health(self) -> Dict[str, Any]:
        """
        Check server health status.

        Returns:
            dict with keys:
                - status (str): "ok" if server is healthy
                - port (int): Server port number

        Raises:
            requests.HTTPError: If server returns error status code
        """
        return self._get("/health")