CREATE TABLE IF NOT EXISTS listen_song_redirect (id uuid PRIMARY KEY, "userId" uuid NOT NULL, "fromSongId" uuid NOT NULL, "toSongId" uuid NOT NULL, "createdAt" BIGINT NOT NULL, CONSTRAINT fk_listen_song_redirect_userid__id FOREIGN KEY ("userId") REFERENCES "user"(id) ON DELETE CASCADE ON UPDATE RESTRICT, CONSTRAINT fk_listen_song_redirect_fromsongid__id FOREIGN KEY ("fromSongId") REFERENCES song(id) ON DELETE CASCADE ON UPDATE RESTRICT, CONSTRAINT fk_listen_song_redirect_tosongid__id FOREIGN KEY ("toSongId") REFERENCES song(id) ON DELETE CASCADE ON UPDATE RESTRICT);
ALTER TABLE listen_song_redirect ADD CONSTRAINT listen_song_redirect_userid_fromsongid_unique UNIQUE ("userId", "fromSongId");
CREATE INDEX listen_song_redirect_tosongid ON listen_song_redirect ("toSongId");
ALTER TABLE "listen" ADD "playedSongId" uuid NULL;
CREATE INDEX listen_playedsongid ON "listen" ("playedSongId");
ALTER TABLE "listen" ADD CONSTRAINT fk_listen_playedsongid__id FOREIGN KEY ("playedSongId") REFERENCES song(id) ON DELETE SET NULL ON UPDATE RESTRICT;
