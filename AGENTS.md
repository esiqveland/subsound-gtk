Subsound is a gtk4 only streaming music player. 
It is written in Java, and is a client for the subsonic streaming music server, but primarily with Navidrome in mind.
We try to keep as much offline access support as reasonably possible.

GTK4 is used via the Java FFM package java-gi which has bindings to GTK, Glib and GStreamer.

## Database

We only use SQLITE for local database.
All sqlite migrations must be versioned.
Database code is written in the database package.


