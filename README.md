
todo:

- [X] songlist: duration, tracknumber, starred
- [ ] songlist: bitrate, size, format
- [ ] songlist: currently playing icon on the left
- [X] songlist: Single click to select, double-click/enter to activate (start playback)
- [X] songlist: Hover-shows-play ?
  - [ ] songlist: hover play icon is not tab-able for focus when row is selected, only when hover...
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
- [ ] Icons for top bar pages
- [ ] Make ArtistInfo page coverart prettier with:
  - [ ] a box shadow?
  - [ ] a blurred paintable background ?
    - Something like: https://github.com/neithern/g4music/blob/master/src/ui/paintables.vala#L357
- [ ] implement a in-memory play queue
  - [ ] Gapless playback of queue
- [ ] 
- [ ] set up a sqlite database with migrations
  - perhaps there is a good android library for migrating sqlite ?
  - [ ] record what we have downloaded / want to keep offline
  - 


My notes:

Adw CSS classes: https://gnome.pages.gitlab.gnome.org/libadwaita/doc/main/style-classes.html
https://docs.gtk.org/gtk4/css-properties.html
https://docs.gtk.org/gtk4/css-overview.html


