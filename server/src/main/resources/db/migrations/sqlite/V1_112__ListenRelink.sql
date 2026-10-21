CREATE TABLE IF NOT EXISTS listen_song_redirect (id BINARY(16) NOT NULL PRIMARY KEY, userId BINARY(16) NOT NULL, fromSongId BINARY(16) NOT NULL, toSongId BINARY(16) NOT NULL, createdAt BIGINT NOT NULL, CONSTRAINT fk_listen_song_redirect_userId__id FOREIGN KEY (userId) REFERENCES "user"(id) ON DELETE CASCADE ON UPDATE RESTRICT, CONSTRAINT fk_listen_song_redirect_fromSongId__id FOREIGN KEY (fromSongId) REFERENCES song(id) ON DELETE CASCADE ON UPDATE RESTRICT, CONSTRAINT fk_listen_song_redirect_toSongId__id FOREIGN KEY (toSongId) REFERENCES song(id) ON DELETE CASCADE ON UPDATE RESTRICT);
CREATE UNIQUE INDEX listen_song_redirect_userId_fromSongId ON listen_song_redirect (userId, fromSongId);
CREATE INDEX listen_song_redirect_toSongId ON listen_song_redirect (toSongId);
ALTER TABLE listen ADD playedSongId BINARY(16) NULL;
CREATE INDEX listen_playedSongId ON listen (playedSongId);
