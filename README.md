
todo:

- [ ] songlist: duration, bitrate, size, format
- [ ] cache thumbnails on disk
- [X] fix song cache storing with original suffix instead of transcode format suffix
  - This caused us to save mp3 transcoded flac data with .flac extension
- [ ] find a way to handle difference between original and stream (transcoded) data better
- [ ] replace gtk.image with gtk.picture? https://docs.gtk.org/gtk4/class.Picture.html
- [ ] PlayerBar: title/album
- [ ] PlayerBar: wire up now-playing to playerState
- [ ] PlayerBar: coverart
- [ ] PlayerBar: Play ProgressBar
- [ ] set up a sqlite database with migrations
  - perhaps there is a good android library for migrating sqlite ?




