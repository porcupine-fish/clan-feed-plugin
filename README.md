# OSRS Runelite Clan Feed Plugin
A simple websockets subscriber to allow clan members to receive clan events messages in their clan chat

<img width="238" height="240" alt="image" src="https://github.com/user-attachments/assets/86ecb228-e597-48e5-962a-3cd54c3e1eb5" />

Payload shape for the websocket subscriber is:
```
{ 
    message: string
}
```
The max length of the message is 500 characters.

The websockets secret key must be configured in the payload header, on the server-side, using the key`X-WS-Key`.
