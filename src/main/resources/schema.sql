CREATE TABLE IF NOT EXISTS team_member
(
    id
    INTEGER
    PRIMARY
    KEY,
    name
    TEXT
    NOT
    NULL
    UNIQUE
);
CREATE TABLE IF NOT EXISTS video_source
(
    id
    INTEGER
    PRIMARY
    KEY,
    root_path
    TEXT
    NOT
    NULL
    UNIQUE,
    enabled
    INTEGER
    NOT
    NULL
    DEFAULT
    1,
    created_at
    TEXT
    NOT
    NULL,
    updated_at
    TEXT
    NOT
    NULL
);
CREATE TABLE IF NOT EXISTS video_asset
(
    id
    INTEGER
    PRIMARY
    KEY,
    source_id
    INTEGER
    NULL
    REFERENCES
    video_source
(
    id
),
    absolute_path TEXT NOT NULL UNIQUE, display_name TEXT NOT NULL,
    file_size INTEGER NOT NULL, modified_at INTEGER NOT NULL,
    origin_type TEXT NOT NULL CHECK
(
    origin_type
    IN
(
    'SCANNED',
    'PICKED'
)),
    created_at TEXT NOT NULL, updated_at TEXT NOT NULL
    );
CREATE TABLE IF NOT EXISTS match_project
(
    id
    INTEGER
    PRIMARY
    KEY,
    name
    TEXT
    NOT
    NULL,
    video_asset_id
    INTEGER
    NOT
    NULL
    REFERENCES
    video_asset
(
    id
), duration_ms INTEGER NOT NULL CHECK
(
    duration_ms >
    0
),
    player_a TEXT NOT NULL, player_b TEXT NOT NULL, target_wins INTEGER NOT NULL CHECK
(
    target_wins >= 1
),
    scoreboard_template TEXT NOT NULL DEFAULT 'CLASSIC' CHECK
(
    scoreboard_template
    IN
(
    'CLASSIC',
    'MODERN'
)),
    first_server TEXT NOT NULL DEFAULT 'A' CHECK
(
    first_server
    IN
(
    'A',
    'B'
)),
    created_at TEXT NOT NULL, updated_at TEXT NOT NULL
    );
CREATE TABLE IF NOT EXISTS score_event
(
    id
    INTEGER
    PRIMARY
    KEY,
    match_id
    INTEGER
    NOT
    NULL
    REFERENCES
    match_project
(
    id
) ON DELETE CASCADE,
    video_time_ms INTEGER NOT NULL CHECK
(
    video_time_ms >= 0
),
    player_side TEXT NOT NULL CHECK
(
    player_side
    IN
(
    'A',
    'B'
)), sequence_no INTEGER NOT NULL,
    created_at TEXT NOT NULL, updated_at TEXT NOT NULL, UNIQUE
(
    match_id,
    sequence_no
)
    );
CREATE INDEX IF NOT EXISTS idx_score_event_match_timeline ON score_event(match_id, video_time_ms, sequence_no);
CREATE TABLE IF NOT EXISTS render_job
(
    id
    INTEGER
    PRIMARY
    KEY,
    match_id
    INTEGER
    NOT
    NULL
    REFERENCES
    match_project
(
    id
),
    template_code TEXT NOT NULL, status TEXT NOT NULL,
    progress_percent INTEGER NOT NULL DEFAULT 0,
    output_path TEXT NULL, failure_message TEXT NULL,
    created_at TEXT NOT NULL, started_at TEXT NULL, finished_at TEXT NULL
    );
CREATE INDEX IF NOT EXISTS idx_render_job_status ON render_job(status);
CREATE TABLE IF NOT EXISTS app_settings
(
    id INTEGER PRIMARY KEY CHECK
(
    id = 1
),
    player_a_name TEXT NOT NULL DEFAULT '甲',
    player_b_name TEXT NOT NULL DEFAULT '乙'
    );
CREATE TABLE IF NOT EXISTS scoreboard_template_config
(
    id
    INTEGER
    PRIMARY
    KEY,
    name
    TEXT
    NOT
    NULL,
    opacity
    INTEGER
    NOT
    NULL
    DEFAULT
    80
    CHECK
(
    opacity
    >=
    0
    AND
    opacity
    <=
    100
),
    bg_color TEXT NOT NULL DEFAULT '#000000',
    text_color TEXT NOT NULL DEFAULT '#ffffff'
    );
