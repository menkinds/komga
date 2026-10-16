-- Fork-only archive. No foreign keys: rows must survive trash empty and hard deletes.
CREATE TABLE HASH_ARCHIVE
(
    FILE_HASH    varchar NOT NULL PRIMARY KEY,
    LIBRARY_ID   varchar NOT NULL,
    SERIES_ID    varchar NOT NULL,
    PAYLOAD      text    NOT NULL,
    UPDATED_DATE varchar NOT NULL
);

CREATE INDEX idx__hash_archive__library_id ON HASH_ARCHIVE (LIBRARY_ID);
CREATE INDEX idx__hash_archive__series_id ON HASH_ARCHIVE (SERIES_ID);
