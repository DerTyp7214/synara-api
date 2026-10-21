CREATE TABLE IF NOT EXISTS listen_share (id uuid PRIMARY KEY, "userA" uuid NOT NULL, "userB" uuid NOT NULL, "useForRecommendations" BOOLEAN DEFAULT FALSE NOT NULL, "createdAt" BIGINT NOT NULL, CONSTRAINT fk_listen_share_usera__id FOREIGN KEY ("userA") REFERENCES "user"(id) ON DELETE CASCADE ON UPDATE RESTRICT, CONSTRAINT fk_listen_share_userb__id FOREIGN KEY ("userB") REFERENCES "user"(id) ON DELETE CASCADE ON UPDATE RESTRICT);
ALTER TABLE listen_share ADD CONSTRAINT listen_share_usera_userb_unique UNIQUE ("userA", "userB");
CREATE INDEX listen_share_userb ON listen_share ("userB");
