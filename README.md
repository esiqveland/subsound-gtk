
todo:

- [X] songlist: duration, tracknumber, starred
- [ ] songlist: bitrate, size, format
- [ ] songlist: currently playing icon on the left
- [ ] Favorites page
- [X] cache thumbnails on disk
  - [ ] load from file
  - [ ] reuse pixbuf for albumart
- [X] cache mp3 variant on disk
  - [X] fix song cache storing with original suffix instead of transcode format suffix
    - This caused us to save mp3 transcoded flac data with .flac extension
- [ ] find a way to handle difference between original and stream (transcoded) data better
- [X] replace gtk.image with gtk.picture? https://docs.gtk.org/gtk4/class.Picture.html
- [X] PlayerBar: title/album
- [X] PlayerBar: wire up now-playing to playerState
- [X] PlayerBar: CoverArt
- [X] PlayerBar: play/pause button
- [X] PlayerBar: find icons for buttons
  - [X] PlayerBar: Playing ProgressBar
- [ ] Make ArtistInfo page coverart prettier with:
  - [ ] a box shadow?
  - [ ] a blurred paintable background ?
    - Something like: https://github.com/neithern/g4music/blob/master/src/ui/paintables.vala#L357
- [ ] implement a in-memory play queue
- [ ] set up a sqlite database with migrations
  - perhaps there is a good android library for migrating sqlite ?
  - [ ] record what we have downloaded / want to keep offline
  - 




