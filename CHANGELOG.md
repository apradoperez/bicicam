# Changelog

## 0.6.0 (November 2026)

First public version.

- Control a DJI Osmo Action from the Karoo over Bluetooth: record and stop, switch off, battery, time left on the card, highlight markers and the Karoo's GPS stored with the video. Tested with an Osmo Action 6 in real rides on 13 and 22 November 2026.
- Eight data fields: navy when ready, red while recording, amber when they need your attention, light grey when not available. The REC fields carry no title, so REC and the counter are as large as the slot allows, with the tally dot above the word when that makes it bigger. The camera battery goes from green to yellow to red.
- The REC field warns in amber when the camera is not in video mode (photo, timelapse, slow motion) and when a recording camera stops responding.
- A tap made while the camera is disconnected never stops a recording: if it comes back still recording, nothing is sent.
- A red frame around the whole Karoo screen while the camera records, in three widths.
- In-ride alerts: low camera battery, card almost full, recording stopped without you, camera not responding.
- Auto-stop after 3, 6 or 9 minutes. Changing the setting never cuts the clip in progress.
- Optional: keep the camera in video mode ("video only" or "video or night"; checked with an Osmo Action 6 on 24 November 2026), and send GPS only while recording to save camera battery (saving not measured yet).
- English and Spanish.
- Insta360 and GoPro: the code is in the project but switched off. With an Insta360 Ace Pro 2 the camera does not reconnect after the link drops; GoPro has not been tested with a camera.
- The time stored with the GPS in DJI clips is now UTC (clips recorded with test builds up to 0.5.0 carry a time 7 hours ahead).

Primera versión pública. Maneja una DJI Osmo Action desde el Karoo (probada con la Osmo Action 6 en rutas reales el 13 y el 22 de noviembre de 2026): grabar y parar, apagar, batería, tarjeta, marcadores y GPS. Ocho campos de datos, con aviso en ámbar si la cámara no está en vídeo o deja de responder, marco rojo al grabar, avisos durante la ruta, parada automática, en español e inglés. Un toque hecho sin conexión nunca para una grabación. Insta360 y GoPro tienen su código en el proyecto, pero apagado: aún no son fiables.
