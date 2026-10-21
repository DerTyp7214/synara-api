CREATE TABLE IF NOT EXISTS listen_share (id BINARY(16) NOT NULL PRIMARY KEY, userA BINARY(16) NOT NULL, userB BINARY(16) NOT NULL, useForRecommendations BOOLEAN DEFAULT 0 NOT NULL, createdAt BIGINT NOT NULL, CONSTRAINT fk_listen_share_userA__id FOREIGN KEY (userA) REFERENCES "user"(id) ON DELETE CASCADE ON UPDATE RESTRICT, CONSTRAINT fk_listen_share_userB__id FOREIGN KEY (userB) REFERENCES "user"(id) ON DELETE CASCADE ON UPDATE RESTRICT);
CREATE UNIQUE INDEX listen_share_userA_userB ON listen_share (userA, userB);
CREATE INDEX listen_share_userB ON listen_share (userB);
