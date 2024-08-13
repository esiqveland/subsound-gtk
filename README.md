
TODO:

- [X] songlist: duration, tracknumber, starred
- [X] songlist: bitrate, size, format
- [X] songlist: currently playing icon on the left
- [X] songlist: self-updating playing icon on the left
- [X] songlist: Single click to select, double-click/enter to activate (start playback)
- [X] songlist: Hover-shows-play ?
  - [ ] songlist: hover play icon is not tab-able for focus when row is selected, only when hover...
- [X] Favorites page
  - [ ] display artist
  - [ ] display album?
- [X] cache thumbnails on disk
  - [ ] load from file
  - [ ] reuse pixbuf for albumart
  - [ ] use a paintable instead ?
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
- ArtistListing:
  - [ ] add a circular artist thumbnail in the left prefix area
  - [ ] convert to using a ~AdwLeaflet~ `AdwNavigationSplitView`
- [ ] Make ArtistInfo page coverart prettier with:
  - [ ] a box shadow?
  - [ ] a blurred paintable background ?
    - Something like: https://github.com/neithern/g4music/blob/master/src/ui/paintables.vala#L357
- [X] Make downloading song async
- [X] PlayQueue: implement a in-memory play queue
  - [X] PlayQueue: auto playback of next queue item
  - [X] PlayQueue: prev with position >= 4.0 seconds played means seekToStart
  - [ ] PlayQueue: enqueue a song that will be added after the current playing song in the playqueue
  - [ ] PlayQueue: Gapless playback of queue using gstreamer soon-finished signal / message + setting next-uri property
- [ ] Proper navigation
  - AdwNavigationView ?
  - AdwLeaflet + AdwClamp? see https://gitlab.gnome.org/GNOME/gnome-music/-/blob/master/data/ui/ArtistAlbumsWidget.ui
- [ ] set up a sqlite database with migrations
  - perhaps there is a good android library for migrating sqlite ?
  - [ ] record what we have downloaded / want to keep offline
- [ ] Create a persistent store for server settings / authentication
- [ ] Implement star/unstar
  - [ ] Optimistically update the local copy of Starred list based on star / unstar actions
- [ ] Playlist support
  - browsing playlists
  - add song to playlist
  - remove song from playlist
  - play as playqueue
- [ ] Mpris support
    - https://github.com/NGMusic/mpris-java/blob/master/extensions/src/xerus/mpris/AbstractMPRISPlayer.kt
    - https://github.com/NGMusic/moodplayer/blob/master/src/desktop/java/xerus/mpris/MPRIS.kt
    - https://github.com/NGMusic/mpris-java/blob/master/extensions/test/xerus/mpris/MPRISPlayer.kt
    - https://github.com/NGMusic/mpris-java/blob/master/src/main/org/mpris/MediaPlayer2/DBusPlayer.kt
    - [ ] thumbnail
    - [ ] play/pause
    - [ ] skip


My notes:

Adw CSS classes: https://gnome.pages.gitlab.gnome.org/libadwaita/doc/main/style-classes.html
https://docs.gtk.org/gtk4/css-properties.html
https://docs.gtk.org/gtk4/css-overview.html

AdwNavigationView:
https://gnome.pages.gitlab.gnome.org/libadwaita/doc/main/class.NavigationView.html
- push / pop AdwNavigationPage

AdwLeaflet:
https://gnome.pages.gitlab.gnome.org/libadwaita/doc/main/class.Leaflet.html

AdwClamp:
https://gnome.pages.gitlab.gnome.org/libadwaita/doc/main/class.Clamp.html
