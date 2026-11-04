# Drittanbieter

- `tsf.h` – TinySoundFont (MIT, https://github.com/schellingb/TinySoundFont): SoundFont-2-Synthesizer.
- `shared/src/commonMain/composeResources/files/setlist.sf2` – Auszug aus **GeneralUser GS 2.0.3**
  (Lizenz: `GeneralUser-GS-LICENSE.txt`, https://github.com/mrbumpy409/GeneralUser-GS), reduziert mit
  `audio/tools/make_soundfont.py` auf Steel/Clean/Distortion Guitar (25, 27, 30), Finger/Pick Bass (33, 34),
  Synth Voice (54), Saw Lead (81) und das Standard-Drumkit (Bank 128). Neu erzeugen:

      python3 audio/tools/make_soundfont.py GeneralUser-GS.sf2 shared/src/commonMain/composeResources/files/setlist.sf2 \
          0:25 0:27 0:30 0:33 0:34 0:54 0:81 128:0
