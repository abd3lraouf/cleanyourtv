# The built-in adhan recording

`app/src/main/res/raw/adhan.m4a` is made from:

| | |
|---|---|
| Title | Beautiful adhan |
| Page | https://commons.wikimedia.org/wiki/File:Beautiful_adhan.ogg |
| File | https://upload.wikimedia.org/wikipedia/commons/b/b0/Beautiful_adhan.ogg |
| Author | Adam-synagda (own work), uploaded 2022-04-29 |
| Licence | CC0 1.0 Universal (public domain dedication): https://creativecommons.org/publicdomain/zero/1.0/ |

The original is a 154 s Ogg Vorbis stereo file. It was cut of leading and trailing silence, loudness-normalised
(EBU R128, -16 LUFS, -1.5 dBTP) and encoded to mono AAC-LC at 64 kbps, 44.1 kHz (152.8 s, 1.26 MB). AAC in MP4
rather than Ogg Opus because Android plays Opus in Ogg only from Android 10, and the add-on runs from Android 8.

```bash
# equivalent command
ffmpeg -i Beautiful_adhan.ogg -af "silenceremove=start_periods=1:start_threshold=-50dB,areverse,silenceremove=start_periods=1:start_threshold=-50dB,areverse,loudnorm=I=-16:TP=-1.5" \
  -ac 1 -ar 44100 -c:a aac -b:a 64k adhan.m4a
```

It is the regular adhan, without the Fajr phrase ("as-salātu khayrun mina n-nawm"): no freely licensed Fajr
recording was found. For Fajr, put a recording of your own at `/sdcard/Adhan/fajr.mp3` (see README.md); without
it Fajr uses this one.

Candidates looked at and not used: `Adhan.ogg` (CC0, 42 s, poor quality), `Azan.ogg` (CC BY-SA 4.0, attribution
and share-alike), `Adhan wiki.oga` (CC BY-SA 3.0), and a Sabah Fakhry recording whose public-domain claim rests on
a YouTube upload.
