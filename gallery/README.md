# Look gallery

`looks.json` is the list shown in Resona's look gallery (Looks > Browse the
look gallery). Every push publishes it to the `gallery` pre-release, where the
app downloads it.

Each entry:

```json
{"name": "Look name", "by": "Who made it", "about": "One line about it", "code": "resona://look/..."}
```

Get a look's code in Resona from Looks > Share this look (or a saved look's menu)
> Send as link. People can suggest looks from the gallery's "Suggest yours"
button, which opens an issue here with the code filled in. Codes carry only
settings (no pictures), so every look shows on the viewer's own picture.
