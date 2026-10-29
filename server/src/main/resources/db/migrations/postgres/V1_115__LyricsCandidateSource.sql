ALTER TABLE lyrics_candidate ADD source_candidate uuid NULL;
ALTER TABLE lyrics_candidate ADD CONSTRAINT fk_lyrics_candidate_source_candidate__id FOREIGN KEY (source_candidate) REFERENCES lyrics_candidate(id) ON DELETE CASCADE ON UPDATE RESTRICT;
