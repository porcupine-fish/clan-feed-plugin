# OSRS Runelite Clan Feed Plugin
A simple websockets subscriber to allow clan members to receive clan events messages in their clan chat

<img width="238" height="240" alt="image" src="https://github.com/user-attachments/assets/86ecb228-e597-48e5-962a-3cd54c3e1eb5" />

## Server-side
- The JSON payload shape for the websocket subscriber is:
```
{ 
    message: string
}
```
- The max length of the message is 500 characters.
- The websockets secret key must be configured in the payload header, on the server-side, using the key `X-WS-Key`.

## Security
- The broadcasts over the websocket connection must be authenticated first using the secret key specified in the Clan Feed plugin settings, the authentication check happens on the server side before the websocket connection is established otherwise it won't connect or receive broadcasts. 
- You should only give the secret key to people in your clan who are using the Clan Feed plugin.
- Ideally you give people unique secret keys so that you can manage joiners/leavers to the clan effectively by managing a set of keys per user or per user group, that way you have control what is public and what is private to clan.
- It's good practice to change secret keys regularly too.

## UX
- If the plugin has an incorrect secret key, it will not try to reconnect to the websocket endpoint unless you change the secret key field in the plugin settings or restart the client.
