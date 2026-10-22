CREATE TABLE IF NOT EXISTS lyrics_candidate (id uuid PRIMARY KEY, "songId" uuid NOT NULL, provider TEXT NOT NULL, status VARCHAR(16) NOT NULL, sync_level VARCHAR(16) DEFAULT 'PLAIN' NOT NULL, instrumental BOOLEAN DEFAULT FALSE NOT NULL, "match" VARCHAR(16) NOT NULL, duration_gap_sec INT NULL, "rank" INT DEFAULT 0 NOT NULL, "content" bytea NULL, plain TEXT NULL, fetched_at BIGINT NOT NULL);
CREATE INDEX lyrics_candidate_songid ON lyrics_candidate ("songId");
ALTER TABLE lyrics_candidate ADD CONSTRAINT lyrics_candidate_songid_provider_unique UNIQUE ("songId", provider);
ALTER TABLE lyrics_candidate ADD CONSTRAINT fk_lyrics_candidate_songid__id FOREIGN KEY ("songId") REFERENCES song(id) ON DELETE CASCADE ON UPDATE RESTRICT;
ALTER TABLE song ADD preferred_lyrics_candidate uuid NULL;
ALTER TABLE song ADD CONSTRAINT fk_song_preferred_lyrics_candidate__id FOREIGN KEY (preferred_lyrics_candidate) REFERENCES lyrics_candidate(id) ON DELETE SET NULL ON UPDATE RESTRICT;
CREATE INDEX IF NOT EXISTS lyrics_candidate_plain_fts_idx ON lyrics_candidate USING gin (to_tsvector('simple', COALESCE(plain, '')));
