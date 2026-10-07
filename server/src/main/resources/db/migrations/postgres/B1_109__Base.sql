CREATE FUNCTION queue_for_search_indexing(p_entity_type character varying, p_entity_id uuid) RETURNS void
    LANGUAGE plpgsql
    AS $$
BEGIN
    INSERT INTO search_index_queue (entity_type, entity_id)
    VALUES (p_entity_type, p_entity_id)
    ON CONFLICT (entity_type, entity_id) DO NOTHING;
END;
$$;

CREATE FUNCTION trigger_on_album_artist_change() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF (TG_OP = 'DELETE') THEN
        PERFORM queue_for_search_indexing('ALBUM', OLD."albumId");
        PERFORM queue_for_search_indexing('SONG', s.id) FROM song s WHERE s."albumId" = OLD."albumId";
    ELSE
        PERFORM queue_for_search_indexing('ALBUM', NEW."albumId");
        PERFORM queue_for_search_indexing('SONG', s.id) FROM song s WHERE s."albumId" = NEW."albumId";
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION trigger_on_album_change() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF (TG_OP = 'DELETE') THEN
        PERFORM queue_for_search_indexing('SONG', s.id) FROM song s WHERE s."albumId" = OLD.id;
    ELSE
        IF (TG_OP = 'INSERT' OR OLD.name IS DISTINCT FROM NEW.name OR OLD.title_tags IS DISTINCT FROM NEW.title_tags) THEN
            PERFORM queue_for_search_indexing('ALBUM', NEW.id);
            PERFORM queue_for_search_indexing('SONG', s.id) FROM song s WHERE s."albumId" = NEW.id;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION trigger_on_album_mb_change() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF (TG_OP = 'DELETE') THEN
        PERFORM queue_for_search_indexing('ALBUM', OLD."albumId");
        PERFORM queue_for_search_indexing('SONG', s.id) FROM song s WHERE s."albumId" = OLD."albumId";
    ELSE
        PERFORM queue_for_search_indexing('ALBUM', NEW."albumId");
        PERFORM queue_for_search_indexing('SONG', s.id) FROM song s WHERE s."albumId" = NEW."albumId";
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION trigger_on_artist_alias_change() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF (TG_OP = 'DELETE') THEN
        PERFORM queue_for_search_indexing('ARTIST', OLD."artistId");
        PERFORM queue_for_search_indexing('SONG', sa."songId") FROM songartist sa WHERE sa."artistId" = OLD."artistId";
        PERFORM queue_for_search_indexing('ALBUM', aa."albumId") FROM albumartist aa WHERE aa."artistId" = OLD."artistId";
    ELSE
        PERFORM queue_for_search_indexing('ARTIST', NEW."artistId");
        PERFORM queue_for_search_indexing('SONG', sa."songId") FROM songartist sa WHERE sa."artistId" = NEW."artistId";
        PERFORM queue_for_search_indexing('ALBUM', aa."albumId") FROM albumartist aa WHERE aa."artistId" = NEW."artistId";
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION trigger_on_artist_change() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF (TG_OP = 'DELETE') THEN
        PERFORM queue_for_search_indexing('SONG', sa."songId") FROM songartist sa WHERE sa."artistId" = OLD.id;
        PERFORM queue_for_search_indexing('ALBUM', aa."albumId") FROM albumartist aa WHERE aa."artistId" = OLD.id;
    ELSE
        IF (TG_OP = 'INSERT' OR OLD.name IS DISTINCT FROM NEW.name) THEN
            PERFORM queue_for_search_indexing('ARTIST', NEW.id);
            PERFORM queue_for_search_indexing('SONG', sa."songId") FROM songartist sa WHERE sa."artistId" = NEW.id;
            PERFORM queue_for_search_indexing('ALBUM', aa."albumId") FROM albumartist aa WHERE aa."artistId" = NEW.id;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION trigger_on_artist_mb_change() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF (TG_OP = 'DELETE') THEN
        PERFORM queue_for_search_indexing('ARTIST', OLD."artistId");
        PERFORM queue_for_search_indexing('SONG', sa."songId") FROM songartist sa WHERE sa."artistId" = OLD."artistId";
        PERFORM queue_for_search_indexing('ALBUM', aa."albumId") FROM albumartist aa WHERE aa."artistId" = OLD."artistId";
    ELSE
        PERFORM queue_for_search_indexing('ARTIST', NEW."artistId");
        PERFORM queue_for_search_indexing('SONG', sa."songId") FROM songartist sa WHERE sa."artistId" = NEW."artistId";
        PERFORM queue_for_search_indexing('ALBUM', aa."albumId") FROM albumartist aa WHERE aa."artistId" = NEW."artistId";
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION trigger_on_song_artist_change() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF (TG_OP = 'DELETE') THEN
        PERFORM queue_for_search_indexing('SONG', OLD."songId");
    ELSE
        PERFORM queue_for_search_indexing('SONG', NEW."songId");
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION trigger_on_song_change() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF (TG_OP = 'DELETE') THEN
        DELETE FROM search_index_queue WHERE entity_type = 'SONG' AND entity_id = OLD.id;
    ELSE
        IF (TG_OP = 'INSERT' OR OLD.title IS DISTINCT FROM NEW.title OR OLD.title_tags IS DISTINCT FROM NEW.title_tags OR OLD."albumId" IS DISTINCT FROM NEW."albumId") THEN
            PERFORM queue_for_search_indexing('SONG', NEW.id);
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION trigger_on_song_mb_change() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF (TG_OP = 'DELETE') THEN
        PERFORM queue_for_search_indexing('SONG', OLD."songId");
    ELSE
        PERFORM queue_for_search_indexing('SONG', NEW."songId");
    END IF;
    RETURN NEW;
END;
$$;

CREATE TABLE album (
    id uuid NOT NULL,
    name text NOT NULL,
    title_tags text DEFAULT '[]'::text NOT NULL,
    "releaseDate" character varying(128),
    "releaseDateEstimated" boolean DEFAULT false NOT NULL,
    "songCount" integer DEFAULT 0 NOT NULL,
    cover uuid,
    "animatedCover" uuid,
    "originalId" text,
    barcode character varying(32),
    "lastMetadataCheck" bigint DEFAULT 0 NOT NULL,
    "lastProviderEnrichment" bigint DEFAULT 0 NOT NULL,
    search_vector tsvector,
    "versionGroupId" uuid
);

CREATE TABLE album_genre (
    "albumId" uuid NOT NULL,
    "genreId" uuid NOT NULL
);

CREATE TABLE album_musicbrainz (
    "albumId" uuid NOT NULL,
    "musicBrainzId" character varying(36),
    "lastCheck" bigint DEFAULT 0 NOT NULL
);

CREATE TABLE album_provider (
    "albumId" uuid NOT NULL,
    provider character varying(64) NOT NULL,
    "externalId" text DEFAULT ''::text NOT NULL,
    type character varying(32),
    "rawUrl" text NOT NULL,
    "addedAt" bigint NOT NULL
);

CREATE TABLE album_title_tag (
    "albumId" uuid NOT NULL,
    kind character varying(32) NOT NULL
);

CREATE TABLE album_version_group (
    id uuid NOT NULL
);

CREATE TABLE albumartist (
    "albumId" uuid NOT NULL,
    "artistId" uuid NOT NULL,
    "creditedAliasId" uuid,
    "position" integer DEFAULT 0 NOT NULL,
    "joinPhrase" text
);

CREATE TABLE animated_image (
    id uuid NOT NULL,
    path text NOT NULL,
    hash character varying(255) NOT NULL,
    origin text NOT NULL,
    format character varying(32),
    image_id uuid
);

CREATE TABLE apikey (
    id uuid NOT NULL,
    "keyHash" character varying(64) NOT NULL,
    "rawKey" text,
    "userId" uuid NOT NULL,
    label character varying(255) DEFAULT ''::character varying NOT NULL,
    "createdAt" bigint NOT NULL,
    "lastUsed" bigint,
    "expiresAt" bigint,
    "isRevoked" boolean DEFAULT false NOT NULL,
    scopes text DEFAULT ''::text NOT NULL
);

CREATE TABLE artist (
    id uuid NOT NULL,
    name text NOT NULL,
    "group" boolean DEFAULT false NOT NULL,
    about text DEFAULT ''::text NOT NULL,
    image uuid,
    "lastImageCheck" bigint DEFAULT 0 NOT NULL,
    "lastMetadataCheck" bigint DEFAULT 0 NOT NULL,
    search_vector tsvector
);

CREATE TABLE artist_genre (
    "artistId" uuid NOT NULL,
    "genreId" uuid NOT NULL
);

CREATE TABLE artist_member (
    "artistId" uuid NOT NULL,
    "groupId" uuid NOT NULL
);

CREATE TABLE artist_musicbrainz (
    "artistId" uuid NOT NULL,
    "musicBrainzId" character varying(36),
    "lastCheck" bigint DEFAULT 0 NOT NULL,
    "lastReleaseCheck" bigint DEFAULT 0 NOT NULL
);

CREATE TABLE artist_provider (
    "artistId" uuid NOT NULL,
    provider character varying(64) NOT NULL,
    "externalId" text DEFAULT ''::text NOT NULL,
    type character varying(32),
    "rawUrl" text NOT NULL,
    "addedAt" bigint NOT NULL
);

CREATE TABLE artist_source_rule (
    "artistId" uuid NOT NULL,
    provider character varying(64) NOT NULL,
    kind character varying(32) NOT NULL,
    value character varying(255) NOT NULL,
    rule character varying(16) NOT NULL,
    "createdBy" uuid,
    "createdAt" bigint NOT NULL
);

CREATE TABLE artistalias (
    id uuid NOT NULL,
    "artistId" uuid NOT NULL,
    name text NOT NULL
);

CREATE TABLE artistsplitalias (
    name text NOT NULL,
    "artistId" uuid NOT NULL
);

CREATE TABLE clientdevice (
    "userId" uuid NOT NULL,
    "deviceId" character varying(64) NOT NULL,
    name text DEFAULT ''::text NOT NULL,
    platform character varying(32) DEFAULT ''::character varying NOT NULL,
    "createdAt" bigint NOT NULL,
    "lastSeenAt" bigint NOT NULL
);

CREATE TABLE clientsetting (
    "userId" uuid NOT NULL,
    scope character varying(16) NOT NULL,
    "deviceId" character varying(64) DEFAULT ''::character varying NOT NULL,
    key character varying(255) NOT NULL,
    value text,
    deleted boolean DEFAULT false NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    "modifiedAt" bigint DEFAULT 0 NOT NULL,
    "modifiedByDeviceId" character varying(64)
);

CREATE TABLE clientsettinghistory (
    "userId" uuid NOT NULL,
    scope character varying(16) NOT NULL,
    "deviceId" character varying(64) DEFAULT ''::character varying NOT NULL,
    key character varying(255) NOT NULL,
    version bigint NOT NULL,
    value text,
    deleted boolean DEFAULT false NOT NULL,
    "modifiedAt" bigint DEFAULT 0 NOT NULL,
    "modifiedByDeviceId" character varying(64)
);

CREATE TABLE clientsettingscope (
    "userId" uuid NOT NULL,
    scope character varying(16) NOT NULL,
    "deviceId" character varying(64) DEFAULT ''::character varying NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    "modifiedAt" bigint DEFAULT 0 NOT NULL,
    "purgedVersion" bigint DEFAULT 0 NOT NULL
);

CREATE TABLE collection (
    id uuid NOT NULL,
    name text NOT NULL,
    description text,
    creator uuid NOT NULL,
    "imageId" uuid,
    "imageSource" character varying(16),
    "coverStyle" character varying(32),
    "coverSeed" bigint
);

CREATE TABLE collectionalbum (
    "collectionId" uuid NOT NULL,
    "albumId" uuid NOT NULL,
    "addedAt" bigint NOT NULL
);

CREATE TABLE collectionartist (
    "collectionId" uuid NOT NULL,
    "artistId" uuid NOT NULL,
    "addedAt" bigint NOT NULL
);

CREATE TABLE collectionplaylist (
    "collectionId" uuid NOT NULL,
    "playlistId" uuid NOT NULL,
    "addedAt" bigint NOT NULL
);

CREATE TABLE collectionsong (
    "collectionId" uuid NOT NULL,
    "songId" uuid NOT NULL,
    "addedAt" bigint NOT NULL
);

CREATE TABLE custommigration (
    id character varying(255) NOT NULL,
    "executedAt" bigint NOT NULL
);

CREATE TABLE entity_change (
    id uuid NOT NULL,
    "entityType" character varying(16) NOT NULL,
    "entityId" uuid NOT NULL,
    aspect character varying(16) NOT NULL,
    kind character varying(16) NOT NULL,
    "changedAt" bigint NOT NULL
);

CREATE TABLE entity_change_scope (
    "changeId" uuid NOT NULL,
    "scopeType" character varying(16) NOT NULL,
    "scopeId" uuid NOT NULL
);

CREATE TABLE entity_change_tracking (
    id integer NOT NULL,
    "startedAt" bigint NOT NULL
);

CREATE TABLE favsync (
    "userId" uuid NOT NULL,
    "serviceName" character varying(16) NOT NULL,
    "syncedAt" bigint NOT NULL
);

CREATE TABLE flac_info (
    "songId" uuid NOT NULL,
    "sampleRate" integer NOT NULL,
    "bitDepth" integer NOT NULL,
    channels integer NOT NULL,
    duration double precision NOT NULL,
    "fileSize" bigint NOT NULL,
    "bitrateAvg" integer NOT NULL,
    "seekpointCount" integer NOT NULL,
    "seekIntervalMax" double precision NOT NULL,
    "paddingBytes" integer NOT NULL,
    "audioMd5" character varying(32) NOT NULL,
    "lastAnalyzed" bigint NOT NULL
);

CREATE TABLE followed_artist (
    "userId" uuid NOT NULL,
    "artistId" uuid NOT NULL,
    "lastCheck" bigint DEFAULT 0 NOT NULL
);

CREATE TABLE genre (
    id uuid NOT NULL,
    name character varying(255) NOT NULL
);

CREATE TABLE hidden_release (
    "releaseGroupId" character varying(36),
    "providerReleaseId" uuid,
    "artistId" uuid NOT NULL,
    "hiddenBy" uuid,
    "hiddenAt" bigint NOT NULL
);

CREATE TABLE hue_bridge (
    id uuid NOT NULL,
    "bridgeId" character varying(32) NOT NULL,
    ip character varying(64) NOT NULL,
    name character varying(255) NOT NULL,
    "modelId" character varying(32),
    "applicationKey" text NOT NULL,
    "clientKey" text,
    "certFingerprint" character varying(95),
    "userId" uuid,
    "createdAt" bigint NOT NULL,
    "lastSeen" bigint,
    "lastError" text
);

CREATE TABLE hue_user_link (
    "userId" uuid NOT NULL,
    "bridgeId" uuid NOT NULL,
    enabled boolean DEFAULT false NOT NULL,
    targets text DEFAULT '[]'::text NOT NULL,
    intensity character varying(16) DEFAULT 'MEDIUM'::character varying NOT NULL,
    "transitionMode" character varying(16) DEFAULT 'FIXED'::character varying NOT NULL,
    "transitionMs" integer DEFAULT 400 NOT NULL,
    "onStop" character varying(16) DEFAULT 'KEEP'::character varying NOT NULL,
    "updatedAt" bigint NOT NULL,
    motion character varying(16) DEFAULT 'OFF'::character varying NOT NULL,
    "latencyMs" integer DEFAULT 150 NOT NULL,
    "stopScenes" text DEFAULT '[]'::text NOT NULL
);

CREATE TABLE image (
    id uuid NOT NULL,
    path text NOT NULL,
    hash character varying(255) NOT NULL,
    origin text NOT NULL,
    blur_hash text,
    last_analysis_attempt bigint,
    analysis_unrecoverable boolean DEFAULT false NOT NULL
);

CREATE TABLE image_metadata (
    "imageId" uuid NOT NULL,
    width integer NOT NULL,
    height integer NOT NULL,
    byte_size bigint NOT NULL,
    primary_color integer NOT NULL,
    red integer NOT NULL,
    green integer NOT NULL,
    blue integer NOT NULL,
    luminance double precision NOT NULL,
    hue double precision,
    saturation double precision,
    lightness double precision,
    lab_l double precision,
    lab_a double precision,
    lab_b double precision,
    color1 integer,
    color2 integer,
    color3 integer,
    color4 integer,
    color5 integer
);

CREATE TABLE listen (
    id uuid NOT NULL,
    "listenBrainzUserId" uuid,
    "userId" uuid,
    "songId" uuid,
    "recordingMbid" uuid,
    "recordingMsid" uuid,
    "releaseMbid" uuid,
    isrcs text,
    "artistMbids" text,
    "trackName" text,
    "artistName" text,
    "releaseName" text,
    "listenedAt" bigint NOT NULL,
    "listenSource" character varying(16) DEFAULT 'LOCAL'::character varying NOT NULL,
    "msPlayed" bigint,
    "updatedAt" bigint DEFAULT 0 NOT NULL
);

CREATE TABLE listen_backup_config (
    key text NOT NULL,
    enabled boolean DEFAULT false NOT NULL,
    url text DEFAULT ''::text NOT NULL,
    "apiKey" text,
    "batchSize" integer DEFAULT 1000 NOT NULL,
    "serverId" uuid NOT NULL,
    "lastSyncedUpdatedAt" bigint DEFAULT 0 NOT NULL,
    "lastSyncAt" bigint,
    "lastSyncedCount" integer DEFAULT 0 NOT NULL,
    "lastError" text
);

CREATE TABLE listen_link (
    id uuid NOT NULL,
    "userId" uuid NOT NULL,
    "songId" uuid NOT NULL,
    "recordingMbid" uuid,
    "recordingMsid" uuid,
    "createdAt" bigint NOT NULL
);

CREATE TABLE listenbrainz_user (
    id uuid NOT NULL,
    username character varying(255) NOT NULL,
    token text,
    "lastListenedAt" bigint,
    "lastSyncedAt" bigint,
    "createdAt" bigint NOT NULL
);

CREATE TABLE mb_area (
    id character varying(36) NOT NULL,
    name text NOT NULL,
    "sortName" text NOT NULL,
    type character varying(128),
    disambiguation text
);

CREATE TABLE mb_artist (
    id character varying(36) NOT NULL,
    name text NOT NULL,
    "sortName" text NOT NULL,
    type character varying(128),
    disambiguation text,
    country character varying(2),
    area character varying(36),
    "beginArea" character varying(36),
    "lifeSpanBegin" character varying(64),
    "lifeSpanEnd" character varying(64),
    "lifeSpanEnded" boolean,
    score integer,
    "lastUpdate" bigint DEFAULT 0 NOT NULL
);

CREATE TABLE mb_artist_alias (
    id bigint NOT NULL,
    "artistId" character varying(36) NOT NULL,
    name text NOT NULL,
    "sortName" text NOT NULL,
    locale character varying(16),
    type character varying(128),
    "primary" boolean DEFAULT false NOT NULL,
    "beginDate" character varying(64),
    "endDate" character varying(64)
);

CREATE SEQUENCE mb_artist_alias_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE mb_artist_alias_id_seq OWNED BY mb_artist_alias.id;

CREATE TABLE mb_artist_tag (
    "artistId" character varying(36) NOT NULL,
    name character varying(255) NOT NULL,
    count integer DEFAULT 0 NOT NULL
);

CREATE TABLE mb_media (
    id bigint NOT NULL,
    "releaseId" character varying(36) NOT NULL,
    "position" integer NOT NULL,
    format character varying(128),
    "trackCount" integer DEFAULT 0 NOT NULL
);

CREATE SEQUENCE mb_media_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE mb_media_id_seq OWNED BY mb_media.id;

CREATE TABLE mb_recording (
    id character varying(36) NOT NULL,
    title text NOT NULL,
    length bigint,
    video boolean,
    score integer,
    "lastUpdate" bigint DEFAULT 0 NOT NULL
);

CREATE TABLE mb_recording_artist_credit (
    "recordingId" character varying(36) NOT NULL,
    "artistId" character varying(36) NOT NULL,
    name text NOT NULL,
    "joinPhrase" text,
    "position" integer NOT NULL
);

CREATE TABLE mb_recording_isrc (
    "recordingId" character varying(36) NOT NULL,
    isrc character varying(12) NOT NULL
);

CREATE TABLE mb_recording_release (
    "recordingId" character varying(36) NOT NULL,
    "releaseId" character varying(36) NOT NULL
);

CREATE TABLE mb_relation (
    id character varying(36) NOT NULL,
    "ownerId" character varying(36) NOT NULL,
    type character varying(64) NOT NULL,
    resource text NOT NULL
);

CREATE TABLE mb_relation_provider (
    "ownerId" character varying(36) NOT NULL,
    provider character varying(64) NOT NULL,
    "externalId" text DEFAULT ''::text NOT NULL,
    type character varying(32),
    "rawUrl" text NOT NULL
);

CREATE TABLE mb_release (
    id character varying(36) NOT NULL,
    title text NOT NULL,
    status character varying(128),
    quality character varying(128),
    barcode character varying(128),
    country character varying(128),
    date character varying(128),
    disambiguation text,
    "releaseGroupId" character varying(36),
    score integer,
    "lastUpdate" bigint DEFAULT 0 NOT NULL
);

CREATE TABLE mb_release_artist_credit (
    "releaseId" character varying(36) NOT NULL,
    "artistId" character varying(36) NOT NULL,
    name text NOT NULL,
    "joinPhrase" text,
    "position" integer NOT NULL
);

CREATE TABLE mb_release_group (
    id character varying(36) NOT NULL,
    title text NOT NULL,
    "primaryType" character varying(128),
    "secondaryTypes" text,
    disambiguation text,
    "firstReleaseDate" character varying(128),
    score integer,
    "lastUpdate" bigint DEFAULT 0 NOT NULL
);

CREATE TABLE mb_release_group_artist_credit (
    "releaseGroupId" character varying(36) NOT NULL,
    "artistId" character varying(36) NOT NULL,
    name text NOT NULL,
    "joinPhrase" text,
    "position" integer NOT NULL
);

CREATE TABLE mb_release_group_cover (
    "releaseGroupId" character varying(36) NOT NULL,
    "imageId" uuid,
    "lastFetch" bigint DEFAULT 0 NOT NULL
);

CREATE TABLE mb_track (
    id character varying(36) NOT NULL,
    "mediaId" bigint NOT NULL,
    "position" integer,
    number character varying(32),
    title text,
    "recordingId" character varying(36)
);

CREATE TABLE pcm_info (
    "songId" uuid NOT NULL,
    container character varying(8) NOT NULL,
    "sampleRate" integer NOT NULL,
    "bitDepth" integer NOT NULL,
    channels integer NOT NULL,
    duration double precision NOT NULL,
    "fileSize" bigint NOT NULL,
    "bitrateAvg" integer NOT NULL,
    codec character varying(32) NOT NULL,
    "isFloat" boolean NOT NULL,
    "isBigEndian" boolean NOT NULL,
    "dataOffset" bigint NOT NULL,
    "dataSize" bigint NOT NULL,
    "hasId3" boolean NOT NULL,
    "hasInfoChunk" boolean NOT NULL,
    "audioMd5" character varying(32) NOT NULL,
    "lastAnalyzed" bigint NOT NULL
);

CREATE TABLE person (
    id uuid NOT NULL,
    name text NOT NULL
);

CREATE TABLE playlist (
    id uuid NOT NULL,
    name character varying(255) NOT NULL,
    "imageId" uuid
);

CREATE TABLE playlistsong (
    "playlistId" uuid NOT NULL,
    "songId" uuid NOT NULL,
    "position" integer NOT NULL
);

CREATE TABLE pluginsetting (
    "pluginId" character varying(255) NOT NULL,
    key character varying(255) NOT NULL,
    value text NOT NULL,
    "updatedAt" bigint NOT NULL
);

CREATE TABLE podcastepisode (
    id uuid NOT NULL,
    "showId" uuid NOT NULL,
    guid text NOT NULL,
    "guidKey" character varying(64) NOT NULL,
    title text NOT NULL,
    description text DEFAULT ''::text NOT NULL,
    link text,
    "publishedAt" bigint NOT NULL,
    "durationMs" bigint,
    "enclosureUrl" text,
    "enclosureType" character varying(64),
    "enclosureLength" bigint,
    "filePath" text,
    "fileSize" bigint,
    format character varying(8),
    "imageId" uuid,
    "seasonNumber" integer,
    "episodeNumber" integer,
    "episodeType" character varying(16) DEFAULT 'FULL'::character varying NOT NULL,
    explicit boolean DEFAULT false NOT NULL,
    "importState" character varying(16) DEFAULT 'NONE'::character varying NOT NULL,
    "importAttempts" integer DEFAULT 0 NOT NULL,
    "importError" text,
    "importedAt" bigint,
    "createdAt" bigint NOT NULL,
    "updatedAt" bigint NOT NULL
);

CREATE TABLE podcastepisodeprogress (
    "userId" uuid NOT NULL,
    "episodeId" uuid NOT NULL,
    "positionMs" bigint DEFAULT 0 NOT NULL,
    "durationMs" bigint,
    completed boolean DEFAULT false NOT NULL,
    "lastPlayedAt" bigint NOT NULL,
    "updatedAt" bigint NOT NULL,
    "deviceId" character varying(64)
);

CREATE TABLE podcastshow (
    id uuid NOT NULL,
    source character varying(16) NOT NULL,
    "sourceKey" character varying(72) NOT NULL,
    "feedUrl" text,
    "localPath" text,
    title text NOT NULL,
    description text DEFAULT ''::text NOT NULL,
    author text,
    language character varying(16),
    link text,
    "imageId" uuid,
    "imageUrl" text,
    explicit boolean DEFAULT false NOT NULL,
    "deliveryMode" character varying(16) DEFAULT 'STREAM'::character varying NOT NULL,
    "keepEpisodes" integer,
    retention character varying(16) DEFAULT 'NEWEST'::character varying NOT NULL,
    etag character varying(255),
    "lastModified" character varying(64),
    "lastFetchedAt" bigint,
    "lastFetchError" text,
    "orphanedAt" bigint,
    "createdAt" bigint NOT NULL,
    "updatedAt" bigint NOT NULL
);

CREATE TABLE podcastsubscription (
    "userId" uuid NOT NULL,
    "showId" uuid NOT NULL,
    "createdAt" bigint NOT NULL
);

CREATE TABLE podcasttranscript (
    id uuid NOT NULL,
    "episodeId" uuid NOT NULL,
    "sourceKey" character varying(64) NOT NULL,
    url text,
    "filePath" text,
    type character varying(64) NOT NULL,
    language character varying(16),
    rel character varying(32),
    content text,
    "fetchedAt" bigint,
    "fetchError" text,
    "createdAt" bigint NOT NULL
);

CREATE TABLE provider_enrichment_check (
    "entityId" uuid NOT NULL,
    provider character varying(64) NOT NULL,
    type character varying(16) NOT NULL,
    "lastCheck" bigint NOT NULL
);

CREATE TABLE provider_link (
    id uuid NOT NULL,
    provider character varying(64) NOT NULL,
    "externalId" text DEFAULT ''::text NOT NULL,
    type character varying(32),
    "rawUrl" text NOT NULL,
    "addedAt" bigint NOT NULL
);

CREATE TABLE provider_release (
    id uuid NOT NULL,
    provider character varying(64) NOT NULL,
    "externalId" text NOT NULL,
    "artistId" uuid NOT NULL,
    "artistName" text DEFAULT 'Unknown Artist'::text NOT NULL,
    title text NOT NULL,
    "releaseDate" bigint,
    type character varying(50) DEFAULT 'Unknown'::character varying NOT NULL,
    single boolean DEFAULT false NOT NULL,
    complete boolean DEFAULT true NOT NULL,
    compilation boolean DEFAULT false NOT NULL,
    "trackCount" integer,
    upc character varying(128),
    url text DEFAULT ''::text NOT NULL,
    "artworkUrl" text,
    "imageId" uuid,
    "releaseGroupId" character varying(36),
    "albumId" uuid,
    "songId" uuid,
    links_resolved_at bigint,
    last_image_fetch bigint,
    last_update bigint,
    "recordLabel" text,
    copyright text,
    "copyrightHolder" character varying(255),
    "isrcRegistrants" text,
    suspect boolean DEFAULT false NOT NULL,
    "suspectReason" text,
    "addedAt" bigint NOT NULL
);

CREATE TABLE provider_release_link (
    "providerReleaseId" uuid NOT NULL,
    "linkId" uuid NOT NULL
);

CREATE TABLE queuesyncdevice (
    "sessionId" uuid NOT NULL,
    "userId" uuid NOT NULL,
    "deviceName" text DEFAULT ''::text NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    "lastSyncedVersion" bigint DEFAULT 0 NOT NULL,
    "lastSyncAt" bigint DEFAULT 0 NOT NULL
);

CREATE TABLE radiochannel (
    id uuid NOT NULL,
    name text NOT NULL,
    description text,
    "imageId" uuid,
    enabled boolean DEFAULT false NOT NULL,
    "position" integer DEFAULT 0 NOT NULL,
    discovery boolean DEFAULT false NOT NULL,
    "createdBy" uuid,
    "createdAt" bigint NOT NULL
);

CREATE TABLE radiochannelalbum (
    "channelId" uuid NOT NULL,
    "albumId" uuid NOT NULL,
    "addedAt" bigint NOT NULL
);

CREATE TABLE radiochannelartist (
    "channelId" uuid NOT NULL,
    "artistId" uuid NOT NULL,
    "addedAt" bigint NOT NULL
);

CREATE TABLE radiochannelsong (
    "channelId" uuid NOT NULL,
    "songId" uuid NOT NULL,
    "addedAt" bigint NOT NULL
);

CREATE TABLE recent_release (
    "releaseId" character varying(36) NOT NULL,
    "artistId" uuid NOT NULL,
    "artistName" text DEFAULT 'Unknown Artist'::text NOT NULL,
    title text NOT NULL,
    "releaseDate" bigint,
    type character varying(50) DEFAULT 'Unknown'::character varying NOT NULL,
    "imageId" uuid,
    links text DEFAULT '[]'::text NOT NULL,
    "albumId" uuid,
    "songId" uuid,
    last_image_fetch bigint,
    last_update bigint
);

CREATE TABLE recent_release_link (
    "releaseId" character varying(36) NOT NULL,
    "linkId" uuid NOT NULL
);

CREATE TABLE refreshtoken (
    id uuid NOT NULL,
    "tokenHash" character varying(255) NOT NULL,
    "userId" uuid NOT NULL,
    "sessionId" uuid,
    "isRevoked" boolean DEFAULT false NOT NULL,
    "expiresAt" bigint NOT NULL
);

CREATE TABLE release_artist (
    "releaseGroupId" character varying(36),
    "providerReleaseId" uuid,
    "artistId" uuid NOT NULL
);

CREATE TABLE rpc_call_event (
    id uuid NOT NULL,
    service character varying(255) NOT NULL,
    method character varying(255) NOT NULL,
    username character varying(255) DEFAULT ''::character varying NOT NULL,
    "timestamp" bigint NOT NULL
);

CREATE TABLE rpc_call_stats (
    id uuid NOT NULL,
    service character varying(255) NOT NULL,
    method character varying(255) NOT NULL,
    username character varying(255) DEFAULT ''::character varying NOT NULL,
    "bucketStart" bigint NOT NULL,
    count bigint DEFAULT 0 NOT NULL
);

CREATE TABLE rpc_call_totals (
    id uuid NOT NULL,
    service character varying(255) NOT NULL,
    method character varying(255) NOT NULL,
    username character varying(255) DEFAULT ''::character varying NOT NULL,
    count bigint DEFAULT 0 NOT NULL
);

CREATE TABLE scheduled_task_configuration (
    key text NOT NULL,
    name text NOT NULL,
    enabled boolean NOT NULL,
    trigger text NOT NULL
);

CREATE TABLE scheduled_task_log (
    id uuid NOT NULL,
    "taskName" text NOT NULL,
    "startTime" bigint NOT NULL,
    "endTime" bigint NOT NULL,
    status character varying(20) NOT NULL,
    message text,
    details bytea,
    progress double precision DEFAULT 0.0 NOT NULL,
    logs text DEFAULT ''::text NOT NULL,
    "logTime" bigint NOT NULL
);

CREATE TABLE search_index_queue (
    id integer NOT NULL,
    entity_type character varying(20) NOT NULL,
    entity_id uuid NOT NULL
);

CREATE SEQUENCE search_index_queue_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE search_index_queue_id_seq OWNED BY search_index_queue.id;

CREATE TABLE session (
    id uuid NOT NULL,
    "userId" uuid NOT NULL,
    "isActive" boolean DEFAULT true NOT NULL,
    "lastActive" bigint NOT NULL,
    "userAgent" text DEFAULT ''::text NOT NULL,
    "ipAddress" text DEFAULT ''::text NOT NULL
);

CREATE TABLE song (
    id uuid NOT NULL,
    title text DEFAULT ''::text NOT NULL,
    title_tags text DEFAULT '[]'::text NOT NULL,
    "albumId" uuid NOT NULL,
    duration bigint DEFAULT 0 NOT NULL,
    "releaseDate" character varying(128),
    lyrics text DEFAULT ''::text NOT NULL,
    explicit boolean DEFAULT false NOT NULL,
    "filePath" text DEFAULT ''::text NOT NULL,
    format character varying(8) DEFAULT 'flac'::character varying NOT NULL,
    cover uuid,
    "animatedCover" uuid,
    "originalUrl" text DEFAULT ''::text NOT NULL,
    isrc character varying(32),
    "trackNumber" integer DEFAULT 1 NOT NULL,
    "discNumber" integer DEFAULT 1 NOT NULL,
    copyright text DEFAULT ''::text NOT NULL,
    "sampleRate" integer DEFAULT 0 NOT NULL,
    "bitsPerSample" integer DEFAULT 0 NOT NULL,
    "bitRate" bigint DEFAULT 0 NOT NULL,
    "fileSize" bigint DEFAULT 0 NOT NULL,
    "audioStartMs" bigint,
    channels integer DEFAULT 0 NOT NULL,
    inserted bigint NOT NULL,
    "lastMetadataCheck" bigint DEFAULT 0 NOT NULL,
    "lastLyricsFetchAttempt" bigint DEFAULT 0 NOT NULL,
    "lastProviderEnrichment" bigint DEFAULT 0 NOT NULL,
    search_vector tsvector
);

CREATE TABLE song_acoustid (
    "songId" uuid NOT NULL,
    fingerprint text NOT NULL,
    duration integer NOT NULL,
    "acoustId" character varying(64),
    score double precision,
    "lastCheck" bigint DEFAULT 0 NOT NULL
);

CREATE TABLE song_audio_data (
    "songId" uuid NOT NULL,
    bpm double precision,
    key character varying(16),
    scale character varying(16),
    loudness double precision,
    energy double precision,
    valence double precision,
    danceability double precision,
    acousticness double precision,
    instrumentalness double precision,
    speechiness double precision
);

CREATE TABLE song_audio_embedding (
    "songId" uuid NOT NULL,
    vector bytea NOT NULL,
    dim integer NOT NULL,
    "modelVersion" character varying(64) NOT NULL,
    "updatedAt" bigint NOT NULL
);

CREATE TABLE song_audio_timeline (
    "songId" uuid NOT NULL,
    version integer NOT NULL,
    status character varying(16) NOT NULL,
    source character varying(16) NOT NULL,
    "analyzedAt" bigint NOT NULL,
    beats bytea,
    "beatsCount" integer,
    "onsetRate" double precision,
    "beatsLoudnessMean" double precision,
    "beatsLoudnessMax" double precision,
    envelope bytea,
    "bassEnvelope" bytea,
    bands bytea,
    "bandHz" integer DEFAULT 0 NOT NULL,
    "bandCount" integer DEFAULT 0 NOT NULL,
    "envelopeHz" integer DEFAULT 10 NOT NULL,
    "envelopeMinDb" double precision DEFAULT '-70.0'::numeric NOT NULL,
    "envelopeMaxDb" double precision DEFAULT 0.0 NOT NULL,
    "loudnessRange" double precision,
    "dynamicComplexity" double precision
);

CREATE TABLE song_composer (
    "songId" uuid NOT NULL,
    "personId" uuid NOT NULL
);

CREATE TABLE song_embedding (
    "songId" uuid NOT NULL,
    vector bytea NOT NULL,
    dim integer NOT NULL,
    "clusterId" integer,
    mood character varying(128),
    "modelVersion" character varying(64) NOT NULL,
    "updatedAt" bigint NOT NULL
);

CREATE TABLE song_genre (
    "songId" uuid NOT NULL,
    "genreId" uuid NOT NULL
);

CREATE TABLE song_lyricist (
    "songId" uuid NOT NULL,
    "personId" uuid NOT NULL
);

CREATE TABLE song_musicbrainz (
    "songId" uuid NOT NULL,
    "musicBrainzId" character varying(36),
    "lastCheck" bigint DEFAULT 0 NOT NULL
);

CREATE TABLE song_producer (
    "songId" uuid NOT NULL,
    "personId" uuid NOT NULL
);

CREATE TABLE song_provider (
    "songId" uuid NOT NULL,
    provider character varying(64) NOT NULL,
    "externalId" text DEFAULT ''::text NOT NULL,
    type character varying(32),
    "rawUrl" text NOT NULL,
    "addedAt" bigint NOT NULL
);

CREATE TABLE song_title_tag (
    "songId" uuid NOT NULL,
    kind character varying(32) NOT NULL
);

CREATE TABLE song_variant (
    "songId" uuid NOT NULL,
    kind character varying(16) NOT NULL,
    path text NOT NULL,
    codec character varying(8) DEFAULT ''::character varying NOT NULL,
    "sampleRate" integer DEFAULT 0 NOT NULL,
    "bitsPerSample" integer DEFAULT 0 NOT NULL,
    channels integer DEFAULT 0 NOT NULL,
    "bitRate" bigint DEFAULT 0 NOT NULL,
    "fileSize" bigint DEFAULT 0 NOT NULL
);

CREATE TABLE songartist (
    "songId" uuid NOT NULL,
    "artistId" uuid NOT NULL,
    "creditedAliasId" uuid,
    "position" integer DEFAULT 0 NOT NULL,
    "joinPhrase" text
);

CREATE TABLE subsoniccredential (
    "userId" uuid NOT NULL,
    secret character varying(64) NOT NULL,
    "createdAt" bigint NOT NULL
);

CREATE TABLE synced_lyrics (
    "songId" uuid NOT NULL,
    content bytea,
    raw_lyrics text,
    provider text DEFAULT 'whisperx_v1'::text NOT NULL
);

CREATE TABLE syncservice (
    name character varying(255) NOT NULL,
    "ownerId" uuid NOT NULL,
    scope text NOT NULL,
    "accessToken" text NOT NULL,
    "refreshToken" text NOT NULL,
    "expiresIn" integer NOT NULL,
    token_type text NOT NULL,
    "userId" bigint NOT NULL,
    "createdAt" bigint NOT NULL
);

CREATE TABLE timecodetag (
    id uuid NOT NULL,
    "userId" uuid NOT NULL,
    "songId" uuid NOT NULL,
    type character varying(16) NOT NULL,
    text text DEFAULT ''::text NOT NULL,
    "timestampMs" bigint NOT NULL,
    "endMs" bigint,
    "createdAt" bigint NOT NULL,
    "updatedAt" bigint NOT NULL,
    action character varying(16) DEFAULT 'NONE'::character varying NOT NULL,
    fade boolean DEFAULT false NOT NULL
);

CREATE TABLE transcodedsong (
    "songId" uuid NOT NULL,
    bitrate integer NOT NULL,
    format character varying(10) DEFAULT 'OPUS'::character varying NOT NULL,
    path text NOT NULL,
    "fileSize" bigint DEFAULT 0 NOT NULL
);

CREATE TABLE "user" (
    id uuid NOT NULL,
    username character varying(255) NOT NULL,
    "displayName" character varying(255),
    "passwordHash" character varying(255) NOT NULL,
    "isAdmin" boolean DEFAULT false NOT NULL,
    "profileImageId" uuid
);

CREATE TABLE user_capability (
    "userId" uuid NOT NULL,
    capability character varying(50) NOT NULL
);

CREATE TABLE user_entity_change (
    id uuid NOT NULL,
    "entityType" character varying(16) NOT NULL,
    "entityId" uuid NOT NULL,
    aspect character varying(16) NOT NULL,
    kind character varying(16) NOT NULL,
    "changedAt" bigint NOT NULL,
    "userId" uuid NOT NULL
);

CREATE TABLE user_listenbrainz_link (
    "userId" uuid NOT NULL,
    "listenBrainzUserId" uuid NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    "linkedAt" bigint NOT NULL
);

CREATE TABLE useralbum (
    "userId" uuid NOT NULL,
    "albumId" uuid NOT NULL,
    favourite boolean DEFAULT false NOT NULL,
    "createdAt" bigint NOT NULL,
    "updatedAt" bigint NOT NULL
);

CREATE TABLE userhomecard (
    "userId" uuid NOT NULL,
    "contributionId" character varying(255) NOT NULL,
    pinned boolean DEFAULT true NOT NULL,
    "position" integer DEFAULT 0 NOT NULL
);

CREATE TABLE userplaylist (
    id uuid NOT NULL,
    name text NOT NULL,
    description text NOT NULL,
    "customIdentifier" text,
    creator uuid NOT NULL,
    "imageId" uuid,
    origin text,
    "imageSource" character varying(16),
    "coverStyle" character varying(32),
    "coverSeed" bigint
);

CREATE TABLE userplaylistsong (
    "playlistId" uuid NOT NULL,
    "songId" uuid NOT NULL,
    "addedAt" bigint NOT NULL,
    id uuid NOT NULL
);

CREATE TABLE userqueue (
    "userId" uuid NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    "modifiedAt" bigint NOT NULL,
    "modifiedBySessionId" uuid,
    "modifiedByDeviceName" text,
    "currentIndex" integer DEFAULT 0 NOT NULL,
    "shuffleMode" boolean DEFAULT false NOT NULL,
    "repeatMode" character varying(16) DEFAULT 'OFF'::character varying NOT NULL,
    "sourceId" text
);

CREATE TABLE userqueueentry (
    "userId" uuid NOT NULL,
    "queueId" bigint NOT NULL,
    "songId" uuid NOT NULL,
    "position" integer NOT NULL,
    "shuffledPosition" integer,
    explicit boolean DEFAULT false NOT NULL
);

CREATE TABLE usersong (
    "userId" uuid NOT NULL,
    "songId" uuid NOT NULL,
    favourite boolean DEFAULT false NOT NULL,
    "superLikedAt" bigint,
    "createdAt" bigint NOT NULL,
    "updatedAt" bigint NOT NULL
);

ALTER TABLE ONLY mb_artist_alias ALTER COLUMN id SET DEFAULT nextval('mb_artist_alias_id_seq'::regclass);

ALTER TABLE ONLY mb_media ALTER COLUMN id SET DEFAULT nextval('mb_media_id_seq'::regclass);

ALTER TABLE ONLY search_index_queue ALTER COLUMN id SET DEFAULT nextval('search_index_queue_id_seq'::regclass);

ALTER TABLE ONLY album_musicbrainz
    ADD CONSTRAINT album_musicbrainz_pkey PRIMARY KEY ("albumId");

ALTER TABLE ONLY album
    ADD CONSTRAINT album_pkey PRIMARY KEY (id);

ALTER TABLE ONLY album_version_group
    ADD CONSTRAINT album_version_group_pkey PRIMARY KEY (id);

ALTER TABLE ONLY animated_image
    ADD CONSTRAINT animated_image_pkey PRIMARY KEY (id);

ALTER TABLE ONLY apikey
    ADD CONSTRAINT apikey_keyhash_unique UNIQUE ("keyHash");

ALTER TABLE ONLY apikey
    ADD CONSTRAINT apikey_pkey PRIMARY KEY (id);

ALTER TABLE ONLY artist_musicbrainz
    ADD CONSTRAINT artist_musicbrainz_pkey PRIMARY KEY ("artistId");

ALTER TABLE ONLY artist
    ADD CONSTRAINT artist_pkey PRIMARY KEY (id);

ALTER TABLE ONLY artistalias
    ADD CONSTRAINT artistalias_pkey PRIMARY KEY (id);

ALTER TABLE ONLY collection
    ADD CONSTRAINT collection_pkey PRIMARY KEY (id);

ALTER TABLE ONLY custommigration
    ADD CONSTRAINT custommigration_pkey PRIMARY KEY (id);

ALTER TABLE ONLY entity_change
    ADD CONSTRAINT entity_change_entitytype_entityid_aspect_unique UNIQUE ("entityType", "entityId", aspect);

ALTER TABLE ONLY entity_change
    ADD CONSTRAINT entity_change_pkey PRIMARY KEY (id);

ALTER TABLE ONLY entity_change_tracking
    ADD CONSTRAINT entity_change_tracking_pkey PRIMARY KEY (id);

ALTER TABLE ONLY flac_info
    ADD CONSTRAINT flac_info_pkey PRIMARY KEY ("songId");

ALTER TABLE ONLY genre
    ADD CONSTRAINT genre_name_unique UNIQUE (name);

ALTER TABLE ONLY genre
    ADD CONSTRAINT genre_pkey PRIMARY KEY (id);

ALTER TABLE ONLY hidden_release
    ADD CONSTRAINT hidden_release_providerreleaseid_unique UNIQUE ("providerReleaseId");

ALTER TABLE ONLY hidden_release
    ADD CONSTRAINT hidden_release_releasegroupid_unique UNIQUE ("releaseGroupId");

ALTER TABLE ONLY hue_bridge
    ADD CONSTRAINT hue_bridge_pkey PRIMARY KEY (id);

ALTER TABLE ONLY hue_bridge
    ADD CONSTRAINT hue_bridge_userid_bridgeid_unique UNIQUE ("userId", "bridgeId");

ALTER TABLE ONLY image_metadata
    ADD CONSTRAINT image_metadata_pkey PRIMARY KEY ("imageId");

ALTER TABLE ONLY image
    ADD CONSTRAINT image_pkey PRIMARY KEY (id);

ALTER TABLE ONLY listen_backup_config
    ADD CONSTRAINT listen_backup_config_pkey PRIMARY KEY (key);

ALTER TABLE ONLY listen_link
    ADD CONSTRAINT listen_link_pkey PRIMARY KEY (id);

ALTER TABLE ONLY listen
    ADD CONSTRAINT listen_listenbrainzuserid_listenedat_unique UNIQUE ("listenBrainzUserId", "listenedAt");

ALTER TABLE ONLY listen
    ADD CONSTRAINT listen_pkey PRIMARY KEY (id);

ALTER TABLE ONLY listenbrainz_user
    ADD CONSTRAINT listenbrainz_user_pkey PRIMARY KEY (id);

ALTER TABLE ONLY listenbrainz_user
    ADD CONSTRAINT listenbrainz_user_username_unique UNIQUE (username);

ALTER TABLE ONLY mb_area
    ADD CONSTRAINT mb_area_pkey PRIMARY KEY (id);

ALTER TABLE ONLY mb_artist_alias
    ADD CONSTRAINT mb_artist_alias_pkey PRIMARY KEY (id);

ALTER TABLE ONLY mb_artist
    ADD CONSTRAINT mb_artist_pkey PRIMARY KEY (id);

ALTER TABLE ONLY mb_media
    ADD CONSTRAINT mb_media_pkey PRIMARY KEY (id);

ALTER TABLE ONLY mb_recording
    ADD CONSTRAINT mb_recording_pkey PRIMARY KEY (id);

ALTER TABLE ONLY mb_release_group_cover
    ADD CONSTRAINT mb_release_group_cover_pkey PRIMARY KEY ("releaseGroupId");

ALTER TABLE ONLY mb_release_group
    ADD CONSTRAINT mb_release_group_pkey PRIMARY KEY (id);

ALTER TABLE ONLY mb_release
    ADD CONSTRAINT mb_release_pkey PRIMARY KEY (id);

ALTER TABLE ONLY mb_track
    ADD CONSTRAINT mb_track_pkey PRIMARY KEY (id);

ALTER TABLE ONLY pcm_info
    ADD CONSTRAINT pcm_info_pkey PRIMARY KEY ("songId");

ALTER TABLE ONLY person
    ADD CONSTRAINT person_name_unique UNIQUE (name);

ALTER TABLE ONLY person
    ADD CONSTRAINT person_pkey PRIMARY KEY (id);

ALTER TABLE ONLY album_genre
    ADD CONSTRAINT pk_album_genre PRIMARY KEY ("albumId", "genreId");

ALTER TABLE ONLY album_provider
    ADD CONSTRAINT pk_album_provider PRIMARY KEY ("albumId", provider, "externalId");

ALTER TABLE ONLY album_title_tag
    ADD CONSTRAINT pk_album_title_tag PRIMARY KEY ("albumId", kind);

ALTER TABLE ONLY albumartist
    ADD CONSTRAINT pk_albumartist PRIMARY KEY ("albumId", "artistId");

ALTER TABLE ONLY artist_genre
    ADD CONSTRAINT pk_artist_genre PRIMARY KEY ("artistId", "genreId");

ALTER TABLE ONLY artist_member
    ADD CONSTRAINT pk_artist_member PRIMARY KEY ("artistId", "groupId");

ALTER TABLE ONLY artist_provider
    ADD CONSTRAINT pk_artist_provider PRIMARY KEY ("artistId", provider, "externalId");

ALTER TABLE ONLY artist_source_rule
    ADD CONSTRAINT pk_artist_source_rule PRIMARY KEY ("artistId", provider, kind, value);

ALTER TABLE ONLY artistsplitalias
    ADD CONSTRAINT pk_artistsplitalias PRIMARY KEY (name, "artistId");

ALTER TABLE ONLY clientdevice
    ADD CONSTRAINT pk_clientdevice PRIMARY KEY ("userId", "deviceId");

ALTER TABLE ONLY clientsetting
    ADD CONSTRAINT pk_clientsetting PRIMARY KEY ("userId", scope, "deviceId", key);

ALTER TABLE ONLY clientsettinghistory
    ADD CONSTRAINT pk_clientsettinghistory PRIMARY KEY ("userId", scope, "deviceId", key, version);

ALTER TABLE ONLY clientsettingscope
    ADD CONSTRAINT pk_clientsettingscope PRIMARY KEY ("userId", scope, "deviceId");

ALTER TABLE ONLY collectionalbum
    ADD CONSTRAINT pk_collectionalbum PRIMARY KEY ("collectionId", "albumId");

ALTER TABLE ONLY collectionartist
    ADD CONSTRAINT pk_collectionartist PRIMARY KEY ("collectionId", "artistId");

ALTER TABLE ONLY collectionplaylist
    ADD CONSTRAINT pk_collectionplaylist PRIMARY KEY ("collectionId", "playlistId");

ALTER TABLE ONLY collectionsong
    ADD CONSTRAINT pk_collectionsong PRIMARY KEY ("collectionId", "songId");

ALTER TABLE ONLY entity_change_scope
    ADD CONSTRAINT pk_entity_change_scope PRIMARY KEY ("changeId", "scopeType", "scopeId");

ALTER TABLE ONLY favsync
    ADD CONSTRAINT pk_favsync PRIMARY KEY ("userId", "serviceName");

ALTER TABLE ONLY followed_artist
    ADD CONSTRAINT pk_followed_artist PRIMARY KEY ("userId", "artistId");

ALTER TABLE ONLY hue_user_link
    ADD CONSTRAINT pk_hue_user_link PRIMARY KEY ("userId", "bridgeId");

ALTER TABLE ONLY mb_artist_tag
    ADD CONSTRAINT pk_mb_artist_tag PRIMARY KEY ("artistId", name);

ALTER TABLE ONLY mb_recording_artist_credit
    ADD CONSTRAINT pk_mb_recording_artist_credit PRIMARY KEY ("recordingId", "artistId", "position");

ALTER TABLE ONLY mb_recording_isrc
    ADD CONSTRAINT pk_mb_recording_isrc PRIMARY KEY ("recordingId", isrc);

ALTER TABLE ONLY mb_recording_release
    ADD CONSTRAINT pk_mb_recording_release PRIMARY KEY ("recordingId", "releaseId");

ALTER TABLE ONLY mb_relation
    ADD CONSTRAINT pk_mb_relation PRIMARY KEY (id, "ownerId");

ALTER TABLE ONLY mb_relation_provider
    ADD CONSTRAINT pk_mb_relation_provider PRIMARY KEY ("ownerId", provider, "externalId");

ALTER TABLE ONLY mb_release_artist_credit
    ADD CONSTRAINT pk_mb_release_artist_credit PRIMARY KEY ("releaseId", "artistId", "position");

ALTER TABLE ONLY mb_release_group_artist_credit
    ADD CONSTRAINT pk_mb_release_group_artist_credit PRIMARY KEY ("releaseGroupId", "artistId", "position");

ALTER TABLE ONLY playlistsong
    ADD CONSTRAINT pk_playlistsong PRIMARY KEY ("playlistId", "songId");

ALTER TABLE ONLY pluginsetting
    ADD CONSTRAINT pk_pluginsetting PRIMARY KEY ("pluginId", key);

ALTER TABLE ONLY podcastepisodeprogress
    ADD CONSTRAINT pk_podcastepisodeprogress PRIMARY KEY ("userId", "episodeId");

ALTER TABLE ONLY podcastsubscription
    ADD CONSTRAINT pk_podcastsubscription PRIMARY KEY ("userId", "showId");

ALTER TABLE ONLY provider_enrichment_check
    ADD CONSTRAINT pk_provider_enrichment_check PRIMARY KEY ("entityId", provider, type);

ALTER TABLE ONLY provider_release_link
    ADD CONSTRAINT pk_provider_release_link PRIMARY KEY ("providerReleaseId", "linkId");

ALTER TABLE ONLY radiochannelalbum
    ADD CONSTRAINT pk_radiochannelalbum PRIMARY KEY ("channelId", "albumId");

ALTER TABLE ONLY radiochannelartist
    ADD CONSTRAINT pk_radiochannelartist PRIMARY KEY ("channelId", "artistId");

ALTER TABLE ONLY radiochannelsong
    ADD CONSTRAINT pk_radiochannelsong PRIMARY KEY ("channelId", "songId");

ALTER TABLE ONLY recent_release_link
    ADD CONSTRAINT pk_recent_release_link PRIMARY KEY ("releaseId", "linkId");

ALTER TABLE ONLY song_composer
    ADD CONSTRAINT pk_song_composer PRIMARY KEY ("songId", "personId");

ALTER TABLE ONLY song_genre
    ADD CONSTRAINT pk_song_genre PRIMARY KEY ("songId", "genreId");

ALTER TABLE ONLY song_lyricist
    ADD CONSTRAINT pk_song_lyricist PRIMARY KEY ("songId", "personId");

ALTER TABLE ONLY song_producer
    ADD CONSTRAINT pk_song_producer PRIMARY KEY ("songId", "personId");

ALTER TABLE ONLY song_provider
    ADD CONSTRAINT pk_song_provider PRIMARY KEY ("songId", provider, "externalId");

ALTER TABLE ONLY song_title_tag
    ADD CONSTRAINT pk_song_title_tag PRIMARY KEY ("songId", kind);

ALTER TABLE ONLY song_variant
    ADD CONSTRAINT pk_song_variant PRIMARY KEY ("songId", kind);

ALTER TABLE ONLY songartist
    ADD CONSTRAINT pk_songartist PRIMARY KEY ("songId", "artistId");

ALTER TABLE ONLY syncservice
    ADD CONSTRAINT pk_syncservice PRIMARY KEY (name, "ownerId");

ALTER TABLE ONLY user_capability
    ADD CONSTRAINT pk_user_capability PRIMARY KEY ("userId", capability);

ALTER TABLE ONLY useralbum
    ADD CONSTRAINT pk_useralbum PRIMARY KEY ("userId", "albumId");

ALTER TABLE ONLY userhomecard
    ADD CONSTRAINT pk_userhomecard PRIMARY KEY ("userId", "contributionId");

ALTER TABLE ONLY userplaylistsong
    ADD CONSTRAINT pk_userplaylistsong PRIMARY KEY ("playlistId", "songId", "addedAt", id);

ALTER TABLE ONLY userqueueentry
    ADD CONSTRAINT pk_userqueueentry PRIMARY KEY ("userId", "queueId");

ALTER TABLE ONLY usersong
    ADD CONSTRAINT pk_usersong PRIMARY KEY ("userId", "songId");

ALTER TABLE ONLY playlist
    ADD CONSTRAINT playlist_pkey PRIMARY KEY (id);

ALTER TABLE ONLY podcastepisode
    ADD CONSTRAINT podcastepisode_pkey PRIMARY KEY (id);

ALTER TABLE ONLY podcastepisode
    ADD CONSTRAINT podcastepisode_showid_guidkey_unique UNIQUE ("showId", "guidKey");

ALTER TABLE ONLY podcastshow
    ADD CONSTRAINT podcastshow_pkey PRIMARY KEY (id);

ALTER TABLE ONLY podcastshow
    ADD CONSTRAINT podcastshow_sourcekey_unique UNIQUE ("sourceKey");

ALTER TABLE ONLY podcasttranscript
    ADD CONSTRAINT podcasttranscript_episodeid_sourcekey_unique UNIQUE ("episodeId", "sourceKey");

ALTER TABLE ONLY podcasttranscript
    ADD CONSTRAINT podcasttranscript_pkey PRIMARY KEY (id);

ALTER TABLE ONLY provider_link
    ADD CONSTRAINT provider_link_pkey PRIMARY KEY (id);

ALTER TABLE ONLY provider_link
    ADD CONSTRAINT provider_link_provider_externalid_unique UNIQUE (provider, "externalId");

ALTER TABLE ONLY provider_release
    ADD CONSTRAINT provider_release_pkey PRIMARY KEY (id);

ALTER TABLE ONLY provider_release
    ADD CONSTRAINT provider_release_provider_externalid_unique UNIQUE (provider, "externalId");

ALTER TABLE ONLY queuesyncdevice
    ADD CONSTRAINT queuesyncdevice_pkey PRIMARY KEY ("sessionId");

ALTER TABLE ONLY radiochannel
    ADD CONSTRAINT radiochannel_pkey PRIMARY KEY (id);

ALTER TABLE ONLY recent_release
    ADD CONSTRAINT recent_release_pkey PRIMARY KEY ("releaseId");

ALTER TABLE ONLY refreshtoken
    ADD CONSTRAINT refreshtoken_pkey PRIMARY KEY (id);

ALTER TABLE ONLY refreshtoken
    ADD CONSTRAINT refreshtoken_tokenhash_unique UNIQUE ("tokenHash");

ALTER TABLE ONLY release_artist
    ADD CONSTRAINT release_artist_providerreleaseid_artistid_unique UNIQUE ("providerReleaseId", "artistId");

ALTER TABLE ONLY release_artist
    ADD CONSTRAINT release_artist_releasegroupid_artistid_unique UNIQUE ("releaseGroupId", "artistId");

ALTER TABLE ONLY rpc_call_event
    ADD CONSTRAINT rpc_call_event_pkey PRIMARY KEY (id);

ALTER TABLE ONLY rpc_call_stats
    ADD CONSTRAINT rpc_call_stats_pkey PRIMARY KEY (id);

ALTER TABLE ONLY rpc_call_stats
    ADD CONSTRAINT rpc_call_stats_service_method_username_bucketstart_unique UNIQUE (service, method, username, "bucketStart");

ALTER TABLE ONLY rpc_call_totals
    ADD CONSTRAINT rpc_call_totals_pkey PRIMARY KEY (id);

ALTER TABLE ONLY rpc_call_totals
    ADD CONSTRAINT rpc_call_totals_service_method_username_unique UNIQUE (service, method, username);

ALTER TABLE ONLY scheduled_task_configuration
    ADD CONSTRAINT scheduled_task_configuration_pkey PRIMARY KEY (key);

ALTER TABLE ONLY scheduled_task_log
    ADD CONSTRAINT scheduled_task_log_pkey PRIMARY KEY (id);

ALTER TABLE ONLY search_index_queue
    ADD CONSTRAINT search_index_queue_pkey PRIMARY KEY (id);

ALTER TABLE ONLY session
    ADD CONSTRAINT session_pkey PRIMARY KEY (id);

ALTER TABLE ONLY song_acoustid
    ADD CONSTRAINT song_acoustid_pkey PRIMARY KEY ("songId");

ALTER TABLE ONLY song_audio_data
    ADD CONSTRAINT song_audio_data_pkey PRIMARY KEY ("songId");

ALTER TABLE ONLY song_audio_embedding
    ADD CONSTRAINT song_audio_embedding_pkey PRIMARY KEY ("songId");

ALTER TABLE ONLY song_audio_timeline
    ADD CONSTRAINT song_audio_timeline_pkey PRIMARY KEY ("songId");

ALTER TABLE ONLY song_embedding
    ADD CONSTRAINT song_embedding_pkey PRIMARY KEY ("songId");

ALTER TABLE ONLY song_musicbrainz
    ADD CONSTRAINT song_musicbrainz_pkey PRIMARY KEY ("songId");

ALTER TABLE ONLY song
    ADD CONSTRAINT song_pkey PRIMARY KEY (id);

ALTER TABLE ONLY subsoniccredential
    ADD CONSTRAINT subsoniccredential_pkey PRIMARY KEY ("userId");

ALTER TABLE ONLY synced_lyrics
    ADD CONSTRAINT synced_lyrics_pkey PRIMARY KEY ("songId");

ALTER TABLE ONLY timecodetag
    ADD CONSTRAINT timecodetag_pkey PRIMARY KEY (id);

ALTER TABLE ONLY transcodedsong
    ADD CONSTRAINT transcodedsong_pkey PRIMARY KEY ("songId", bitrate, format);

ALTER TABLE ONLY search_index_queue
    ADD CONSTRAINT unique_pending_entity UNIQUE (entity_type, entity_id);

ALTER TABLE ONLY user_entity_change
    ADD CONSTRAINT user_entity_change_pkey PRIMARY KEY (id);

ALTER TABLE ONLY user_entity_change
    ADD CONSTRAINT user_entity_change_userid_entitytype_entityid_aspect_unique UNIQUE ("userId", "entityType", "entityId", aspect);

ALTER TABLE ONLY user_listenbrainz_link
    ADD CONSTRAINT user_listenbrainz_link_pkey PRIMARY KEY ("userId");

ALTER TABLE ONLY "user"
    ADD CONSTRAINT user_pkey PRIMARY KEY (id);

ALTER TABLE ONLY "user"
    ADD CONSTRAINT user_username_unique UNIQUE (username);

ALTER TABLE ONLY userplaylist
    ADD CONSTRAINT userplaylist_pkey PRIMARY KEY (id);

ALTER TABLE ONLY userqueue
    ADD CONSTRAINT userqueue_pkey PRIMARY KEY ("userId");

CREATE INDEX album_name_fts_idx ON album USING gin (to_tsvector('simple'::regconfig, COALESCE(name, ''::text)));

CREATE INDEX album_originalid ON album USING btree ("originalId");

CREATE INDEX album_provider_externalid_provider ON album_provider USING btree ("externalId", provider);

CREATE INDEX album_provider_rawurl ON album_provider USING btree ("rawUrl");

CREATE INDEX album_search_vector_idx ON album USING gin (search_vector);

CREATE INDEX album_title_tag_kind ON album_title_tag USING btree (kind);

CREATE INDEX album_versiongroupid ON album USING btree ("versionGroupId");

CREATE INDEX albumartist_artistid ON albumartist USING btree ("artistId");

CREATE INDEX apikey_userid ON apikey USING btree ("userId");

CREATE INDEX artist_alias_name_fts_idx ON artistalias USING gin (to_tsvector('simple'::regconfig, COALESCE(name, ''::text)));

CREATE INDEX artist_member_groupid ON artist_member USING btree ("groupId");

CREATE INDEX artist_name_fts_idx ON artist USING gin (to_tsvector('simple'::regconfig, COALESCE(name, ''::text)));

CREATE INDEX artist_search_vector_idx ON artist USING gin (search_vector);

CREATE INDEX artistalias_artistid ON artistalias USING btree ("artistId");

CREATE INDEX clientdevice_lastseenat ON clientdevice USING btree ("lastSeenAt");

CREATE INDEX clientsetting_deleted_modifiedat ON clientsetting USING btree (deleted, "modifiedAt");

CREATE INDEX clientsetting_userid_scope_deviceid_version ON clientsetting USING btree ("userId", scope, "deviceId", version);

CREATE INDEX collectionalbum_albumid ON collectionalbum USING btree ("albumId");

CREATE INDEX collectionartist_artistid ON collectionartist USING btree ("artistId");

CREATE INDEX collectionplaylist_playlistid ON collectionplaylist USING btree ("playlistId");

CREATE INDEX collectionsong_songid ON collectionsong USING btree ("songId");

CREATE INDEX entity_change_changedat ON entity_change USING btree ("changedAt");

CREATE INDEX entity_change_scope_scopetype_scopeid ON entity_change_scope USING btree ("scopeType", "scopeId");

CREATE INDEX hidden_release_artistid ON hidden_release USING btree ("artistId");

CREATE INDEX listen_link_recordingmbid ON listen_link USING btree ("recordingMbid");

CREATE INDEX listen_link_recordingmsid ON listen_link USING btree ("recordingMsid");

CREATE INDEX listen_link_userid ON listen_link USING btree ("userId");

CREATE INDEX listen_listensource_updatedat ON listen USING btree ("listenSource", "updatedAt");

CREATE INDEX listen_recordingmbid ON listen USING btree ("recordingMbid");

CREATE INDEX listen_songid ON listen USING btree ("songId");

CREATE INDEX listen_userid ON listen USING btree ("userId");

CREATE INDEX listen_userid_listenedat ON listen USING btree ("userId", "listenedAt");

CREATE INDEX mb_artist_alias_name_fts_idx ON mb_artist_alias USING gin (to_tsvector('simple'::regconfig, COALESCE(name, ''::text)));

CREATE INDEX mb_artist_disambig_fts_idx ON mb_artist USING gin (to_tsvector('simple'::regconfig, COALESCE(disambiguation, ''::text)));

CREATE INDEX mb_artist_name_fts_idx ON mb_artist USING gin (to_tsvector('simple'::regconfig, COALESCE(name, ''::text)));

CREATE INDEX mb_recording_title_fts_idx ON mb_recording USING gin (to_tsvector('simple'::regconfig, COALESCE(title, ''::text)));

CREATE INDEX mb_relation_ownerid ON mb_relation USING btree ("ownerId");

CREATE INDEX mb_relation_provider_externalid_provider ON mb_relation_provider USING btree ("externalId", provider);

CREATE INDEX mb_relation_provider_ownerid ON mb_relation_provider USING btree ("ownerId");

CREATE INDEX mb_relation_provider_rawurl ON mb_relation_provider USING btree ("rawUrl");

CREATE INDEX mb_release_disambig_fts_idx ON mb_release USING gin (to_tsvector('simple'::regconfig, COALESCE(disambiguation, ''::text)));

CREATE INDEX mb_release_title_fts_idx ON mb_release USING gin (to_tsvector('simple'::regconfig, COALESCE(title, ''::text)));

CREATE INDEX playlist_name_fts_idx ON playlist USING gin (to_tsvector('simple'::regconfig, (COALESCE(name, ''::character varying))::text));

CREATE INDEX playlistsong_songid ON playlistsong USING btree ("songId");

CREATE INDEX podcastepisode_importstate ON podcastepisode USING btree ("importState");

CREATE INDEX podcastepisode_showid_publishedat ON podcastepisode USING btree ("showId", "publishedAt");

CREATE INDEX podcastepisodeprogress_userid_completed ON podcastepisodeprogress USING btree ("userId", completed);

CREATE INDEX podcastepisodeprogress_userid_lastplayedat ON podcastepisodeprogress USING btree ("userId", "lastPlayedAt");

CREATE INDEX podcastshow_source_orphanedat ON podcastshow USING btree (source, "orphanedAt");

CREATE INDEX podcastsubscription_showid ON podcastsubscription USING btree ("showId");

CREATE INDEX provider_release_artistid_copyrightholder ON provider_release USING btree ("artistId", "copyrightHolder");

CREATE INDEX provider_release_artistid_releasedate ON provider_release USING btree ("artistId", "releaseDate");

CREATE INDEX provider_release_link_linkid ON provider_release_link USING btree ("linkId");

CREATE INDEX provider_release_releasegroupid ON provider_release USING btree ("releaseGroupId");

CREATE INDEX queuesyncdevice_userid ON queuesyncdevice USING btree ("userId");

CREATE INDEX recent_release_link_linkid ON recent_release_link USING btree ("linkId");

CREATE INDEX refreshtoken_userid ON refreshtoken USING btree ("userId");

CREATE INDEX release_artist_artistid ON release_artist USING btree ("artistId");

CREATE INDEX rpc_call_event_timestamp ON rpc_call_event USING btree ("timestamp");

CREATE INDEX session_userid ON session USING btree ("userId");

CREATE INDEX song_albumid ON song USING btree ("albumId");

CREATE INDEX song_filepath ON song USING btree ("filePath");

CREATE INDEX song_genre_genreid ON song_genre USING btree ("genreId");

CREATE INDEX song_isrc ON song USING btree (isrc);

CREATE INDEX song_lyrics_fts_idx ON song USING gin (to_tsvector('simple'::regconfig, COALESCE(lyrics, ''::text)));

CREATE INDEX song_mbid_fts_idx ON song_musicbrainz USING gin (to_tsvector('simple'::regconfig, (COALESCE("musicBrainzId", ''::character varying))::text));

CREATE INDEX song_musicbrainz_musicbrainzid ON song_musicbrainz USING btree ("musicBrainzId");

CREATE INDEX song_originalurl ON song USING btree ("originalUrl");

CREATE INDEX song_provider_externalid_provider ON song_provider USING btree ("externalId", provider);

CREATE INDEX song_provider_rawurl ON song_provider USING btree ("rawUrl");

CREATE INDEX song_search_vector_idx ON song USING gin (search_vector);

CREATE INDEX song_title_fts_idx ON song USING gin (to_tsvector('simple'::regconfig, COALESCE(title, ''::text)));

CREATE INDEX song_title_tag_kind ON song_title_tag USING btree (kind);

CREATE INDEX songartist_artistid ON songartist USING btree ("artistId");

CREATE INDEX synced_lyrics_raw_fts_idx ON synced_lyrics USING gin (to_tsvector('simple'::regconfig, COALESCE(raw_lyrics, ''::text)));

CREATE INDEX timecodetag_userid_songid_timestampms ON timecodetag USING btree ("userId", "songId", "timestampMs");

CREATE INDEX timecodetag_userid_type_createdat ON timecodetag USING btree ("userId", type, "createdAt");

CREATE INDEX user_entity_change_changedat ON user_entity_change USING btree ("changedAt");

CREATE INDEX user_entity_change_entitytype_entityid ON user_entity_change USING btree ("entityType", "entityId");

CREATE INDEX user_entity_change_userid_changedat ON user_entity_change USING btree ("userId", "changedAt");

CREATE INDEX user_playlist_name_fts_idx ON userplaylist USING gin (to_tsvector('simple'::regconfig, COALESCE(name, ''::text)));

CREATE INDEX userplaylistsong_songid ON userplaylistsong USING btree ("songId");

CREATE INDEX userqueueentry_userid_position ON userqueueentry USING btree ("userId", "position");

CREATE TRIGGER album_artist_change_indexing_trigger AFTER INSERT OR DELETE OR UPDATE ON albumartist FOR EACH ROW EXECUTE FUNCTION trigger_on_album_artist_change();

CREATE TRIGGER album_change_indexing_trigger AFTER INSERT OR DELETE OR UPDATE ON album FOR EACH ROW EXECUTE FUNCTION trigger_on_album_change();

CREATE TRIGGER album_mb_change_indexing_trigger AFTER INSERT OR DELETE OR UPDATE ON album_musicbrainz FOR EACH ROW EXECUTE FUNCTION trigger_on_album_mb_change();

CREATE TRIGGER artist_alias_change_indexing_trigger AFTER INSERT OR DELETE OR UPDATE ON artistalias FOR EACH ROW EXECUTE FUNCTION trigger_on_artist_alias_change();

CREATE TRIGGER artist_change_indexing_trigger AFTER INSERT OR DELETE OR UPDATE ON artist FOR EACH ROW EXECUTE FUNCTION trigger_on_artist_change();

CREATE TRIGGER artist_mb_change_indexing_trigger AFTER INSERT OR DELETE OR UPDATE ON artist_musicbrainz FOR EACH ROW EXECUTE FUNCTION trigger_on_artist_mb_change();

CREATE TRIGGER song_artist_change_indexing_trigger AFTER INSERT OR DELETE OR UPDATE ON songartist FOR EACH ROW EXECUTE FUNCTION trigger_on_song_artist_change();

CREATE TRIGGER song_change_indexing_trigger AFTER INSERT OR DELETE OR UPDATE ON song FOR EACH ROW EXECUTE FUNCTION trigger_on_song_change();

CREATE TRIGGER song_mb_change_indexing_trigger AFTER INSERT OR DELETE OR UPDATE ON song_musicbrainz FOR EACH ROW EXECUTE FUNCTION trigger_on_song_mb_change();

ALTER TABLE ONLY album
    ADD CONSTRAINT fk_album_animatedcover__id FOREIGN KEY ("animatedCover") REFERENCES animated_image(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY album
    ADD CONSTRAINT fk_album_cover__id FOREIGN KEY (cover) REFERENCES image(id) ON UPDATE RESTRICT ON DELETE SET NULL;

ALTER TABLE ONLY album_genre
    ADD CONSTRAINT fk_album_genre_albumid__id FOREIGN KEY ("albumId") REFERENCES album(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY album_genre
    ADD CONSTRAINT fk_album_genre_genreid__id FOREIGN KEY ("genreId") REFERENCES genre(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY album_musicbrainz
    ADD CONSTRAINT fk_album_musicbrainz_albumid__id FOREIGN KEY ("albumId") REFERENCES album(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY album_musicbrainz
    ADD CONSTRAINT fk_album_musicbrainz_musicbrainzid__id FOREIGN KEY ("musicBrainzId") REFERENCES mb_release(id) ON UPDATE RESTRICT ON DELETE SET NULL;

ALTER TABLE ONLY album_provider
    ADD CONSTRAINT fk_album_provider_albumid__id FOREIGN KEY ("albumId") REFERENCES album(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY album_title_tag
    ADD CONSTRAINT fk_album_title_tag_albumid__id FOREIGN KEY ("albumId") REFERENCES album(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY album
    ADD CONSTRAINT fk_album_versiongroupid__id FOREIGN KEY ("versionGroupId") REFERENCES album_version_group(id) ON UPDATE RESTRICT ON DELETE SET NULL;

ALTER TABLE ONLY albumartist
    ADD CONSTRAINT fk_albumartist_albumid__id FOREIGN KEY ("albumId") REFERENCES album(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY albumartist
    ADD CONSTRAINT fk_albumartist_artistid__id FOREIGN KEY ("artistId") REFERENCES artist(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY albumartist
    ADD CONSTRAINT fk_albumartist_creditedaliasid__id FOREIGN KEY ("creditedAliasId") REFERENCES artistalias(id) ON UPDATE RESTRICT ON DELETE SET NULL;

ALTER TABLE ONLY animated_image
    ADD CONSTRAINT fk_animated_image_image_id__id FOREIGN KEY (image_id) REFERENCES image(id) ON UPDATE RESTRICT ON DELETE SET NULL;

ALTER TABLE ONLY apikey
    ADD CONSTRAINT fk_apikey_userid__id FOREIGN KEY ("userId") REFERENCES "user"(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY artist_genre
    ADD CONSTRAINT fk_artist_genre_artistid__id FOREIGN KEY ("artistId") REFERENCES artist(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY artist_genre
    ADD CONSTRAINT fk_artist_genre_genreid__id FOREIGN KEY ("genreId") REFERENCES genre(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY artist
    ADD CONSTRAINT fk_artist_image__id FOREIGN KEY (image) REFERENCES image(id) ON UPDATE RESTRICT ON DELETE SET NULL;

ALTER TABLE ONLY artist_member
    ADD CONSTRAINT fk_artist_member_artistid__id FOREIGN KEY ("artistId") REFERENCES artist(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY artist_member
    ADD CONSTRAINT fk_artist_member_groupid__id FOREIGN KEY ("groupId") REFERENCES artist(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY artist_musicbrainz
    ADD CONSTRAINT fk_artist_musicbrainz_artistid__id FOREIGN KEY ("artistId") REFERENCES artist(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY artist_musicbrainz
    ADD CONSTRAINT fk_artist_musicbrainz_musicbrainzid__id FOREIGN KEY ("musicBrainzId") REFERENCES mb_artist(id) ON UPDATE RESTRICT ON DELETE SET NULL;

ALTER TABLE ONLY artist_provider
    ADD CONSTRAINT fk_artist_provider_artistid__id FOREIGN KEY ("artistId") REFERENCES artist(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY artist_source_rule
    ADD CONSTRAINT fk_artist_source_rule_artistid__id FOREIGN KEY ("artistId") REFERENCES artist(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY artist_source_rule
    ADD CONSTRAINT fk_artist_source_rule_createdby__id FOREIGN KEY ("createdBy") REFERENCES "user"(id) ON UPDATE RESTRICT ON DELETE SET NULL;

ALTER TABLE ONLY artistalias
    ADD CONSTRAINT fk_artistalias_artistid__id FOREIGN KEY ("artistId") REFERENCES artist(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY artistsplitalias
    ADD CONSTRAINT fk_artistsplitalias_artistid__id FOREIGN KEY ("artistId") REFERENCES artist(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY clientdevice
    ADD CONSTRAINT fk_clientdevice_userid__id FOREIGN KEY ("userId") REFERENCES "user"(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY clientsetting
    ADD CONSTRAINT fk_clientsetting_userid__id FOREIGN KEY ("userId") REFERENCES "user"(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY clientsettinghistory
    ADD CONSTRAINT fk_clientsettinghistory_userid__id FOREIGN KEY ("userId") REFERENCES "user"(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY clientsettingscope
    ADD CONSTRAINT fk_clientsettingscope_userid__id FOREIGN KEY ("userId") REFERENCES "user"(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY collection
    ADD CONSTRAINT fk_collection_creator__id FOREIGN KEY (creator) REFERENCES "user"(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY collection
    ADD CONSTRAINT fk_collection_imageid__id FOREIGN KEY ("imageId") REFERENCES image(id) ON UPDATE RESTRICT ON DELETE SET NULL;

ALTER TABLE ONLY collectionalbum
    ADD CONSTRAINT fk_collectionalbum_albumid__id FOREIGN KEY ("albumId") REFERENCES album(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY collectionalbum
    ADD CONSTRAINT fk_collectionalbum_collectionid__id FOREIGN KEY ("collectionId") REFERENCES collection(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY collectionartist
    ADD CONSTRAINT fk_collectionartist_artistid__id FOREIGN KEY ("artistId") REFERENCES artist(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY collectionartist
    ADD CONSTRAINT fk_collectionartist_collectionid__id FOREIGN KEY ("collectionId") REFERENCES collection(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY collectionplaylist
    ADD CONSTRAINT fk_collectionplaylist_collectionid__id FOREIGN KEY ("collectionId") REFERENCES collection(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY collectionplaylist
    ADD CONSTRAINT fk_collectionplaylist_playlistid__id FOREIGN KEY ("playlistId") REFERENCES userplaylist(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY collectionsong
    ADD CONSTRAINT fk_collectionsong_collectionid__id FOREIGN KEY ("collectionId") REFERENCES collection(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY collectionsong
    ADD CONSTRAINT fk_collectionsong_songid__id FOREIGN KEY ("songId") REFERENCES song(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY entity_change_scope
    ADD CONSTRAINT fk_entity_change_scope_changeid__id FOREIGN KEY ("changeId") REFERENCES entity_change(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY favsync
    ADD CONSTRAINT fk_favsync_userid__id FOREIGN KEY ("userId") REFERENCES "user"(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY flac_info
    ADD CONSTRAINT fk_flac_info_songid__id FOREIGN KEY ("songId") REFERENCES song(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY followed_artist
    ADD CONSTRAINT fk_followed_artist_artistid__id FOREIGN KEY ("artistId") REFERENCES artist(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY followed_artist
    ADD CONSTRAINT fk_followed_artist_userid__id FOREIGN KEY ("userId") REFERENCES "user"(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY hidden_release
    ADD CONSTRAINT fk_hidden_release_artistid__id FOREIGN KEY ("artistId") REFERENCES artist(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY hidden_release
    ADD CONSTRAINT fk_hidden_release_hiddenby__id FOREIGN KEY ("hiddenBy") REFERENCES "user"(id) ON UPDATE RESTRICT ON DELETE SET NULL;

ALTER TABLE ONLY hidden_release
    ADD CONSTRAINT fk_hidden_release_providerreleaseid__id FOREIGN KEY ("providerReleaseId") REFERENCES provider_release(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY hidden_release
    ADD CONSTRAINT fk_hidden_release_releasegroupid__id FOREIGN KEY ("releaseGroupId") REFERENCES mb_release_group(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY hue_bridge
    ADD CONSTRAINT fk_hue_bridge_userid__id FOREIGN KEY ("userId") REFERENCES "user"(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY hue_user_link
    ADD CONSTRAINT fk_hue_user_link_bridgeid__id FOREIGN KEY ("bridgeId") REFERENCES hue_bridge(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY hue_user_link
    ADD CONSTRAINT fk_hue_user_link_userid__id FOREIGN KEY ("userId") REFERENCES "user"(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY image_metadata
    ADD CONSTRAINT fk_image_metadata_imageid__id FOREIGN KEY ("imageId") REFERENCES image(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY listen_link
    ADD CONSTRAINT fk_listen_link_songid__id FOREIGN KEY ("songId") REFERENCES song(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY listen_link
    ADD CONSTRAINT fk_listen_link_userid__id FOREIGN KEY ("userId") REFERENCES "user"(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY listen
    ADD CONSTRAINT fk_listen_listenbrainzuserid__id FOREIGN KEY ("listenBrainzUserId") REFERENCES listenbrainz_user(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY listen
    ADD CONSTRAINT fk_listen_songid__id FOREIGN KEY ("songId") REFERENCES song(id) ON UPDATE RESTRICT ON DELETE SET NULL;

ALTER TABLE ONLY listen
    ADD CONSTRAINT fk_listen_userid__id FOREIGN KEY ("userId") REFERENCES "user"(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY mb_artist_alias
    ADD CONSTRAINT fk_mb_artist_alias_artistid__id FOREIGN KEY ("artistId") REFERENCES mb_artist(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY mb_artist
    ADD CONSTRAINT fk_mb_artist_area__id FOREIGN KEY (area) REFERENCES mb_area(id) ON UPDATE RESTRICT ON DELETE SET NULL;

ALTER TABLE ONLY mb_artist
    ADD CONSTRAINT fk_mb_artist_beginarea__id FOREIGN KEY ("beginArea") REFERENCES mb_area(id) ON UPDATE RESTRICT ON DELETE SET NULL;

ALTER TABLE ONLY mb_artist_tag
    ADD CONSTRAINT fk_mb_artist_tag_artistid__id FOREIGN KEY ("artistId") REFERENCES mb_artist(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY mb_media
    ADD CONSTRAINT fk_mb_media_releaseid__id FOREIGN KEY ("releaseId") REFERENCES mb_release(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY mb_recording_artist_credit
    ADD CONSTRAINT fk_mb_recording_artist_credit_artistid__id FOREIGN KEY ("artistId") REFERENCES mb_artist(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY mb_recording_artist_credit
    ADD CONSTRAINT fk_mb_recording_artist_credit_recordingid__id FOREIGN KEY ("recordingId") REFERENCES mb_recording(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY mb_recording_isrc
    ADD CONSTRAINT fk_mb_recording_isrc_recordingid__id FOREIGN KEY ("recordingId") REFERENCES mb_recording(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY mb_recording_release
    ADD CONSTRAINT fk_mb_recording_release_recordingid__id FOREIGN KEY ("recordingId") REFERENCES mb_recording(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY mb_recording_release
    ADD CONSTRAINT fk_mb_recording_release_releaseid__id FOREIGN KEY ("releaseId") REFERENCES mb_release(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY mb_release_artist_credit
    ADD CONSTRAINT fk_mb_release_artist_credit_artistid__id FOREIGN KEY ("artistId") REFERENCES mb_artist(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY mb_release_artist_credit
    ADD CONSTRAINT fk_mb_release_artist_credit_releaseid__id FOREIGN KEY ("releaseId") REFERENCES mb_release(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY mb_release_group_artist_credit
    ADD CONSTRAINT fk_mb_release_group_artist_credit_artistid__id FOREIGN KEY ("artistId") REFERENCES mb_artist(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY mb_release_group_artist_credit
    ADD CONSTRAINT fk_mb_release_group_artist_credit_releasegroupid__id FOREIGN KEY ("releaseGroupId") REFERENCES mb_release_group(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY mb_release_group_cover
    ADD CONSTRAINT fk_mb_release_group_cover_imageid__id FOREIGN KEY ("imageId") REFERENCES image(id) ON UPDATE RESTRICT ON DELETE SET NULL;

ALTER TABLE ONLY mb_release_group_cover
    ADD CONSTRAINT fk_mb_release_group_cover_releasegroupid__id FOREIGN KEY ("releaseGroupId") REFERENCES mb_release_group(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY mb_release
    ADD CONSTRAINT fk_mb_release_releasegroupid__id FOREIGN KEY ("releaseGroupId") REFERENCES mb_release_group(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY mb_track
    ADD CONSTRAINT fk_mb_track_mediaid__id FOREIGN KEY ("mediaId") REFERENCES mb_media(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY mb_track
    ADD CONSTRAINT fk_mb_track_recordingid__id FOREIGN KEY ("recordingId") REFERENCES mb_recording(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY pcm_info
    ADD CONSTRAINT fk_pcm_info_songid__id FOREIGN KEY ("songId") REFERENCES song(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY playlist
    ADD CONSTRAINT fk_playlist_imageid__id FOREIGN KEY ("imageId") REFERENCES image(id) ON UPDATE RESTRICT ON DELETE SET NULL;

ALTER TABLE ONLY playlistsong
    ADD CONSTRAINT fk_playlistsong_playlistid__id FOREIGN KEY ("playlistId") REFERENCES playlist(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY playlistsong
    ADD CONSTRAINT fk_playlistsong_songid__id FOREIGN KEY ("songId") REFERENCES song(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY podcastepisode
    ADD CONSTRAINT fk_podcastepisode_imageid__id FOREIGN KEY ("imageId") REFERENCES image(id) ON UPDATE RESTRICT ON DELETE SET NULL;

ALTER TABLE ONLY podcastepisode
    ADD CONSTRAINT fk_podcastepisode_showid__id FOREIGN KEY ("showId") REFERENCES podcastshow(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY podcastepisodeprogress
    ADD CONSTRAINT fk_podcastepisodeprogress_episodeid__id FOREIGN KEY ("episodeId") REFERENCES podcastepisode(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY podcastepisodeprogress
    ADD CONSTRAINT fk_podcastepisodeprogress_userid__id FOREIGN KEY ("userId") REFERENCES "user"(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY podcastshow
    ADD CONSTRAINT fk_podcastshow_imageid__id FOREIGN KEY ("imageId") REFERENCES image(id) ON UPDATE RESTRICT ON DELETE SET NULL;

ALTER TABLE ONLY podcastsubscription
    ADD CONSTRAINT fk_podcastsubscription_showid__id FOREIGN KEY ("showId") REFERENCES podcastshow(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY podcastsubscription
    ADD CONSTRAINT fk_podcastsubscription_userid__id FOREIGN KEY ("userId") REFERENCES "user"(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY podcasttranscript
    ADD CONSTRAINT fk_podcasttranscript_episodeid__id FOREIGN KEY ("episodeId") REFERENCES podcastepisode(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY provider_release
    ADD CONSTRAINT fk_provider_release_albumid__id FOREIGN KEY ("albumId") REFERENCES album(id) ON UPDATE RESTRICT ON DELETE SET NULL;

ALTER TABLE ONLY provider_release
    ADD CONSTRAINT fk_provider_release_artistid__id FOREIGN KEY ("artistId") REFERENCES artist(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY provider_release
    ADD CONSTRAINT fk_provider_release_imageid__id FOREIGN KEY ("imageId") REFERENCES image(id) ON UPDATE RESTRICT ON DELETE SET NULL;

ALTER TABLE ONLY provider_release_link
    ADD CONSTRAINT fk_provider_release_link_linkid__id FOREIGN KEY ("linkId") REFERENCES provider_link(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY provider_release_link
    ADD CONSTRAINT fk_provider_release_link_providerreleaseid__id FOREIGN KEY ("providerReleaseId") REFERENCES provider_release(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY provider_release
    ADD CONSTRAINT fk_provider_release_releasegroupid__id FOREIGN KEY ("releaseGroupId") REFERENCES mb_release_group(id) ON UPDATE RESTRICT ON DELETE SET NULL;

ALTER TABLE ONLY provider_release
    ADD CONSTRAINT fk_provider_release_songid__id FOREIGN KEY ("songId") REFERENCES song(id) ON UPDATE RESTRICT ON DELETE SET NULL;

ALTER TABLE ONLY queuesyncdevice
    ADD CONSTRAINT fk_queuesyncdevice_sessionid__id FOREIGN KEY ("sessionId") REFERENCES session(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY queuesyncdevice
    ADD CONSTRAINT fk_queuesyncdevice_userid__id FOREIGN KEY ("userId") REFERENCES "user"(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY radiochannel
    ADD CONSTRAINT fk_radiochannel_createdby__id FOREIGN KEY ("createdBy") REFERENCES "user"(id) ON UPDATE RESTRICT ON DELETE SET NULL;

ALTER TABLE ONLY radiochannel
    ADD CONSTRAINT fk_radiochannel_imageid__id FOREIGN KEY ("imageId") REFERENCES image(id) ON UPDATE RESTRICT ON DELETE SET NULL;

ALTER TABLE ONLY radiochannelalbum
    ADD CONSTRAINT fk_radiochannelalbum_albumid__id FOREIGN KEY ("albumId") REFERENCES album(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY radiochannelalbum
    ADD CONSTRAINT fk_radiochannelalbum_channelid__id FOREIGN KEY ("channelId") REFERENCES radiochannel(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY radiochannelartist
    ADD CONSTRAINT fk_radiochannelartist_artistid__id FOREIGN KEY ("artistId") REFERENCES artist(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY radiochannelartist
    ADD CONSTRAINT fk_radiochannelartist_channelid__id FOREIGN KEY ("channelId") REFERENCES radiochannel(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY radiochannelsong
    ADD CONSTRAINT fk_radiochannelsong_channelid__id FOREIGN KEY ("channelId") REFERENCES radiochannel(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY radiochannelsong
    ADD CONSTRAINT fk_radiochannelsong_songid__id FOREIGN KEY ("songId") REFERENCES song(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY recent_release
    ADD CONSTRAINT fk_recent_release_albumid__id FOREIGN KEY ("albumId") REFERENCES album(id) ON UPDATE RESTRICT ON DELETE SET NULL;

ALTER TABLE ONLY recent_release
    ADD CONSTRAINT fk_recent_release_artistid__id FOREIGN KEY ("artistId") REFERENCES artist(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY recent_release
    ADD CONSTRAINT fk_recent_release_imageid__id FOREIGN KEY ("imageId") REFERENCES image(id) ON UPDATE RESTRICT ON DELETE SET NULL;

ALTER TABLE ONLY recent_release_link
    ADD CONSTRAINT fk_recent_release_link_linkid__id FOREIGN KEY ("linkId") REFERENCES provider_link(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY recent_release_link
    ADD CONSTRAINT fk_recent_release_link_releaseid__id FOREIGN KEY ("releaseId") REFERENCES mb_release_group(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY recent_release
    ADD CONSTRAINT fk_recent_release_releaseid__id FOREIGN KEY ("releaseId") REFERENCES mb_release_group(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY recent_release
    ADD CONSTRAINT fk_recent_release_songid__id FOREIGN KEY ("songId") REFERENCES song(id) ON UPDATE RESTRICT ON DELETE SET NULL;

ALTER TABLE ONLY refreshtoken
    ADD CONSTRAINT fk_refreshtoken_sessionid__id FOREIGN KEY ("sessionId") REFERENCES session(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY refreshtoken
    ADD CONSTRAINT fk_refreshtoken_userid__id FOREIGN KEY ("userId") REFERENCES "user"(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY release_artist
    ADD CONSTRAINT fk_release_artist_artistid__id FOREIGN KEY ("artistId") REFERENCES artist(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY release_artist
    ADD CONSTRAINT fk_release_artist_providerreleaseid__id FOREIGN KEY ("providerReleaseId") REFERENCES provider_release(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY release_artist
    ADD CONSTRAINT fk_release_artist_releasegroupid__releaseid FOREIGN KEY ("releaseGroupId") REFERENCES recent_release("releaseId") ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY session
    ADD CONSTRAINT fk_session_userid__id FOREIGN KEY ("userId") REFERENCES "user"(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY song_acoustid
    ADD CONSTRAINT fk_song_acoustid_songid__id FOREIGN KEY ("songId") REFERENCES song(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY song
    ADD CONSTRAINT fk_song_albumid__id FOREIGN KEY ("albumId") REFERENCES album(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY song
    ADD CONSTRAINT fk_song_animatedcover__id FOREIGN KEY ("animatedCover") REFERENCES animated_image(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY song_audio_data
    ADD CONSTRAINT fk_song_audio_data_songid__id FOREIGN KEY ("songId") REFERENCES song(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY song_audio_embedding
    ADD CONSTRAINT fk_song_audio_embedding_songid__id FOREIGN KEY ("songId") REFERENCES song(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY song_audio_timeline
    ADD CONSTRAINT fk_song_audio_timeline_songid__id FOREIGN KEY ("songId") REFERENCES song(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY song_composer
    ADD CONSTRAINT fk_song_composer_personid__id FOREIGN KEY ("personId") REFERENCES person(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY song_composer
    ADD CONSTRAINT fk_song_composer_songid__id FOREIGN KEY ("songId") REFERENCES song(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY song
    ADD CONSTRAINT fk_song_cover__id FOREIGN KEY (cover) REFERENCES image(id) ON UPDATE RESTRICT ON DELETE SET NULL;

ALTER TABLE ONLY song_embedding
    ADD CONSTRAINT fk_song_embedding_songid__id FOREIGN KEY ("songId") REFERENCES song(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY song_genre
    ADD CONSTRAINT fk_song_genre_genreid__id FOREIGN KEY ("genreId") REFERENCES genre(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY song_genre
    ADD CONSTRAINT fk_song_genre_songid__id FOREIGN KEY ("songId") REFERENCES song(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY song_lyricist
    ADD CONSTRAINT fk_song_lyricist_personid__id FOREIGN KEY ("personId") REFERENCES person(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY song_lyricist
    ADD CONSTRAINT fk_song_lyricist_songid__id FOREIGN KEY ("songId") REFERENCES song(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY song_musicbrainz
    ADD CONSTRAINT fk_song_musicbrainz_musicbrainzid__id FOREIGN KEY ("musicBrainzId") REFERENCES mb_recording(id) ON UPDATE RESTRICT ON DELETE SET NULL;

ALTER TABLE ONLY song_musicbrainz
    ADD CONSTRAINT fk_song_musicbrainz_songid__id FOREIGN KEY ("songId") REFERENCES song(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY song_producer
    ADD CONSTRAINT fk_song_producer_personid__id FOREIGN KEY ("personId") REFERENCES person(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY song_producer
    ADD CONSTRAINT fk_song_producer_songid__id FOREIGN KEY ("songId") REFERENCES song(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY song_provider
    ADD CONSTRAINT fk_song_provider_songid__id FOREIGN KEY ("songId") REFERENCES song(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY song_title_tag
    ADD CONSTRAINT fk_song_title_tag_songid__id FOREIGN KEY ("songId") REFERENCES song(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY song_variant
    ADD CONSTRAINT fk_song_variant_songid__id FOREIGN KEY ("songId") REFERENCES song(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY songartist
    ADD CONSTRAINT fk_songartist_artistid__id FOREIGN KEY ("artistId") REFERENCES artist(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY songartist
    ADD CONSTRAINT fk_songartist_creditedaliasid__id FOREIGN KEY ("creditedAliasId") REFERENCES artistalias(id) ON UPDATE RESTRICT ON DELETE SET NULL;

ALTER TABLE ONLY songartist
    ADD CONSTRAINT fk_songartist_songid__id FOREIGN KEY ("songId") REFERENCES song(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY subsoniccredential
    ADD CONSTRAINT fk_subsoniccredential_userid__id FOREIGN KEY ("userId") REFERENCES "user"(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY synced_lyrics
    ADD CONSTRAINT fk_synced_lyrics_songid__id FOREIGN KEY ("songId") REFERENCES song(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY syncservice
    ADD CONSTRAINT fk_syncservice_ownerid__id FOREIGN KEY ("ownerId") REFERENCES "user"(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY timecodetag
    ADD CONSTRAINT fk_timecodetag_songid__id FOREIGN KEY ("songId") REFERENCES song(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY timecodetag
    ADD CONSTRAINT fk_timecodetag_userid__id FOREIGN KEY ("userId") REFERENCES "user"(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY transcodedsong
    ADD CONSTRAINT fk_transcodedsong_songid__id FOREIGN KEY ("songId") REFERENCES song(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY user_capability
    ADD CONSTRAINT fk_user_capability_userid__id FOREIGN KEY ("userId") REFERENCES "user"(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY user_entity_change
    ADD CONSTRAINT fk_user_entity_change_userid__id FOREIGN KEY ("userId") REFERENCES "user"(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY user_listenbrainz_link
    ADD CONSTRAINT fk_user_listenbrainz_link_listenbrainzuserid__id FOREIGN KEY ("listenBrainzUserId") REFERENCES listenbrainz_user(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY user_listenbrainz_link
    ADD CONSTRAINT fk_user_listenbrainz_link_userid__id FOREIGN KEY ("userId") REFERENCES "user"(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY "user"
    ADD CONSTRAINT fk_user_profileimageid__id FOREIGN KEY ("profileImageId") REFERENCES image(id) ON UPDATE RESTRICT ON DELETE SET NULL;

ALTER TABLE ONLY useralbum
    ADD CONSTRAINT fk_useralbum_albumid__id FOREIGN KEY ("albumId") REFERENCES album(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY useralbum
    ADD CONSTRAINT fk_useralbum_userid__id FOREIGN KEY ("userId") REFERENCES "user"(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY userhomecard
    ADD CONSTRAINT fk_userhomecard_userid__id FOREIGN KEY ("userId") REFERENCES "user"(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY userplaylist
    ADD CONSTRAINT fk_userplaylist_creator__id FOREIGN KEY (creator) REFERENCES "user"(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY userplaylist
    ADD CONSTRAINT fk_userplaylist_imageid__id FOREIGN KEY ("imageId") REFERENCES image(id) ON UPDATE RESTRICT ON DELETE SET NULL;

ALTER TABLE ONLY userplaylistsong
    ADD CONSTRAINT fk_userplaylistsong_playlistid__id FOREIGN KEY ("playlistId") REFERENCES userplaylist(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY userplaylistsong
    ADD CONSTRAINT fk_userplaylistsong_songid__id FOREIGN KEY ("songId") REFERENCES song(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY userqueue
    ADD CONSTRAINT fk_userqueue_modifiedbysessionid__id FOREIGN KEY ("modifiedBySessionId") REFERENCES session(id) ON UPDATE RESTRICT ON DELETE SET NULL;

ALTER TABLE ONLY userqueue
    ADD CONSTRAINT fk_userqueue_userid__id FOREIGN KEY ("userId") REFERENCES "user"(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY userqueueentry
    ADD CONSTRAINT fk_userqueueentry_songid__id FOREIGN KEY ("songId") REFERENCES song(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY userqueueentry
    ADD CONSTRAINT fk_userqueueentry_userid__id FOREIGN KEY ("userId") REFERENCES "user"(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY usersong
    ADD CONSTRAINT fk_usersong_songid__id FOREIGN KEY ("songId") REFERENCES song(id) ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE ONLY usersong
    ADD CONSTRAINT fk_usersong_userid__id FOREIGN KEY ("userId") REFERENCES "user"(id) ON UPDATE RESTRICT ON DELETE CASCADE;
