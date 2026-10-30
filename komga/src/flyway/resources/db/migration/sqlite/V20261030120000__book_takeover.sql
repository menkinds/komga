-- Fork-only marker: a trashed book whose history was reattached to a live book.
-- No foreign keys: the row must outlive the old book until its series is deleted.
CREATE TABLE BOOK_TAKEOVER
(
    OLD_BOOK_ID   varchar NOT NULL PRIMARY KEY,
    OLD_SERIES_ID varchar NOT NULL,
    NEW_BOOK_ID   varchar NOT NULL,
    CREATED_DATE  varchar NOT NULL
);

CREATE INDEX idx__book_takeover__old_series_id ON BOOK_TAKEOVER (OLD_SERIES_ID);
